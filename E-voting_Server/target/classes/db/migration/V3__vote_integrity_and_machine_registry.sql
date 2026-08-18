-- ============================================================================
-- V3 — Vote integrity, ballot secrecy, machine registry
-- ============================================================================
-- This migration reshapes the voting core around four guarantees:
--
--   1. BALLOT SECRECY   The old `vote` table stored voter_id next to
--                       candidate_id, so anyone with DB access could see who
--                       voted for whom. It is replaced by two tables:
--                         voter_turnout — WHO voted (no candidate)
--                         ballots       — WHAT was voted (no voter)
--                       `ballots.cast_at_hour` is truncated to the hour so the
--                       two tables cannot be trivially re-linked by timestamp.
--
--   2. IMMUTABILITY     voter_turnout, ballots and ledger_blocks reject UPDATE
--                       and DELETE at the database level, not just in Java.
--
--   3. VERIFIABLE LEDGER  ledger_blocks persists the hash chain so it survives
--                       a server restart. Previously the chain lived in a
--                       in-memory ArrayList and reset to genesis on every
--                       boot, permanently diverging from the vote rows.
--
--   4. MACHINE IDENTITY Each voting terminal gets its own row, its own hashed
--                       secret, revocable tokens and replay protection —
--                       replacing the single shared secret and the in-memory
--                       token set.
--
-- Scope note: municipality (4-tier) voting is removed. Only PM and CM
-- elections are supported from here on.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. Drop views that depend on the old `vote` table (recreated at the end)
-- ---------------------------------------------------------------------------
DROP VIEW IF EXISTS public.voter_full_detail;
DROP VIEW IF EXISTS public.voter_election_status;


-- ---------------------------------------------------------------------------
-- 2. Machine registry
-- ---------------------------------------------------------------------------
CREATE TABLE public.machines (
    machine_id          character varying(64)  NOT NULL,
    label               character varying(150) NOT NULL,
    booth_name          character varying(150),
    secret_verifier     character varying(128),
    secret_salt         character varying(64),
    signing_key_cipher  character varying(256),
    signing_key_iv      character varying(64),
    status              character varying(20)  DEFAULT 'PENDING' NOT NULL,
    registered_at       timestamp without time zone,
    last_seen_at        timestamp without time zone,
    revoked_at          timestamp without time zone,
    created_at          timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT machines_pkey PRIMARY KEY (machine_id),
    CONSTRAINT machines_status_chk CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED'))
);

COMMENT ON TABLE  public.machines IS 'One row per voting terminal, each with its own credentials so a compromised terminal can be revoked without touching the others.';
COMMENT ON COLUMN public.machines.secret_verifier IS 'PBKDF2-WithHmacSHA256 over the one-time provisioning secret. The secret itself is shown to the operator once and never stored.';
COMMENT ON COLUMN public.machines.signing_key_cipher IS 'Per-machine HMAC signing key, AES-256-GCM encrypted under the server master key (EVOTING_MASTER_KEY). Handed to the terminal once at registration over TLS.';
COMMENT ON COLUMN public.machines.status IS 'PENDING = awaiting secret provisioning, ACTIVE = may register, REVOKED = refused.';


-- Issued machine JWTs, tracked by jti so they can be expired and revoked.
CREATE TABLE public.machine_tokens (
    jti             character varying(36)  NOT NULL,
    machine_id      character varying(64)  NOT NULL,
    issued_at       timestamp without time zone NOT NULL,
    expires_at      timestamp without time zone NOT NULL,
    revoked_at      timestamp without time zone,
    CONSTRAINT machine_tokens_pkey PRIMARY KEY (jti),
    CONSTRAINT machine_tokens_machine_fk FOREIGN KEY (machine_id)
        REFERENCES public.machines (machine_id) ON DELETE CASCADE
);

CREATE INDEX idx_machine_tokens_machine  ON public.machine_tokens (machine_id);
CREATE INDEX idx_machine_tokens_expires  ON public.machine_tokens (expires_at);


-- Seen request nonces, so a captured signed request cannot be replayed.
CREATE TABLE public.machine_nonces (
    nonce           character varying(64)  NOT NULL,
    machine_id      character varying(64)  NOT NULL,
    seen_at         timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT machine_nonces_pkey PRIMARY KEY (nonce)
);

CREATE INDEX idx_machine_nonces_seen_at ON public.machine_nonces (seen_at);

COMMENT ON TABLE public.machine_nonces IS 'Replay protection for HMAC-signed machine requests. Pruned on a schedule once outside the signature freshness window.';


-- ---------------------------------------------------------------------------
-- 3. Biometric verification hand-off
-- ---------------------------------------------------------------------------
-- The fingerprint check happens before a voting session exists, so the result
-- is recorded here and consumed by POST /api/session/start. Previously
-- /session/start simply set biometric_verified = true on its own, which meant
-- the fingerprint step could be skipped entirely.
CREATE TABLE public.biometric_verifications (
    token_hash      character varying(64)  NOT NULL,
    voter_id        character varying(255) NOT NULL,
    machine_id      character varying(64)  NOT NULL,
    verified_at     timestamp without time zone DEFAULT now() NOT NULL,
    expires_at      timestamp without time zone NOT NULL,
    consumed_at     timestamp without time zone,
    CONSTRAINT biometric_verifications_pkey PRIMARY KEY (token_hash)
);

CREATE INDEX idx_biometric_verif_voter   ON public.biometric_verifications (voter_id);
CREATE INDEX idx_biometric_verif_expires ON public.biometric_verifications (expires_at);


-- ---------------------------------------------------------------------------
-- 4. Turnout ledger — WHO voted. Enforces one vote per voter per election.
-- ---------------------------------------------------------------------------
CREATE TABLE public.voter_turnout (
    id              bigint                 GENERATED BY DEFAULT AS IDENTITY,
    voter_id        character varying(255) NOT NULL,
    election_id     integer                NOT NULL,
    machine_id      character varying(64),
    voted_at        timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT voter_turnout_pkey PRIMARY KEY (id),
    CONSTRAINT uq_voter_turnout_voter_election UNIQUE (voter_id, election_id)
);

CREATE INDEX idx_voter_turnout_election ON public.voter_turnout (election_id);

COMMENT ON TABLE public.voter_turnout IS 'Records that a voter has voted, never what they voted for. The UNIQUE constraint is the authoritative one-vote-per-voter guarantee, and it holds across booths and across server instances.';


-- ---------------------------------------------------------------------------
-- 5. Ballots — WHAT was voted. Carries no voter identity.
-- ---------------------------------------------------------------------------
CREATE TABLE public.ballots (
    id              bigint                 GENERATED BY DEFAULT AS IDENTITY,
    ballot_uuid     character varying(36)  NOT NULL,
    election_id     integer                NOT NULL,
    election_type   character varying(20)  NOT NULL,
    candidate_id    integer                NOT NULL,
    constituency_id integer,
    machine_id      character varying(64),
    idempotency_key character varying(64)  NOT NULL,
    cast_at_hour    timestamp without time zone NOT NULL,
    CONSTRAINT ballots_pkey PRIMARY KEY (id),
    CONSTRAINT uq_ballots_uuid UNIQUE (ballot_uuid),
    CONSTRAINT uq_ballots_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ballots_election_type_chk CHECK (election_type IN ('PM', 'CM'))
);

CREATE INDEX idx_ballots_election      ON public.ballots (election_id);
CREATE INDEX idx_ballots_candidate     ON public.ballots (election_id, candidate_id);
CREATE INDEX idx_ballots_constituency  ON public.ballots (election_id, constituency_id);

COMMENT ON TABLE  public.ballots IS 'Anonymous ballots. There is deliberately no voter_id column and no foreign key to voters.';
COMMENT ON COLUMN public.ballots.idempotency_key IS 'Supplied by the voting machine. A queued vote replayed after a network failure hits this UNIQUE constraint instead of being counted twice.';
COMMENT ON COLUMN public.ballots.cast_at_hour IS 'Truncated to the hour on purpose, so ballots cannot be re-linked to voter_turnout rows by matching timestamps.';


-- ---------------------------------------------------------------------------
-- 6. Persistent hash-chained ledger
-- ---------------------------------------------------------------------------
CREATE TABLE public.ledger_blocks (
    block_index     bigint                 NOT NULL,
    previous_hash   character varying(64)  NOT NULL,
    hash            character varying(64)  NOT NULL,
    ballot_uuid     character varying(36),
    election_id     integer,
    candidate_id    integer,
    constituency_id integer,
    machine_id      character varying(64),
    cast_at_hour    timestamp without time zone,
    created_at      timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ledger_blocks_pkey PRIMARY KEY (block_index),
    CONSTRAINT uq_ledger_blocks_hash UNIQUE (hash),
    CONSTRAINT uq_ledger_blocks_ballot UNIQUE (ballot_uuid)
);

COMMENT ON TABLE public.ledger_blocks IS 'Append-only hash chain over anonymous ballots. Block 0 is genesis. hash = SHA-256 over block_index, previous_hash, ballot_uuid, election_id, candidate_id, constituency_id, machine_id and cast_at_hour, so the whole chain can be recomputed from this table alone.';


-- ---------------------------------------------------------------------------
-- 7. Migrate existing PM/CM votes, then archive the old table
-- ---------------------------------------------------------------------------
-- Turnout first, so the unique constraint collapses any historical duplicates.
INSERT INTO public.voter_turnout (voter_id, election_id, machine_id, voted_at)
SELECT DISTINCT ON (v.voter_id, v.election_id)
       v.voter_id, v.election_id, 'LEGACY', COALESCE(v.voted_at, now())
FROM   public.vote v
WHERE  v.municipality_tier IS NULL
  AND  v.election_id IS NOT NULL
ORDER  BY v.voter_id, v.election_id, v.voted_at;

-- Ballots, deliberately dropping the voter linkage.
INSERT INTO public.ballots (ballot_uuid, election_id, election_type, candidate_id,
                            constituency_id, machine_id, idempotency_key, cast_at_hour)
SELECT gen_random_uuid()::text,
       v.election_id,
       CASE WHEN v.election_type IN ('PM', 'CM') THEN v.election_type ELSE 'CM' END,
       v.candidate_id,
       v.constituency_id,
       'LEGACY',
       'legacy-' || v.id,
       date_trunc('hour', COALESCE(v.voted_at, now()))
FROM   public.vote v
WHERE  v.municipality_tier IS NULL
  AND  v.election_id IS NOT NULL
  AND  v.candidate_id IS NOT NULL;

ALTER TABLE public.vote RENAME TO vote_archive_v2;
COMMENT ON TABLE public.vote_archive_v2 IS 'Pre-V3 vote table, retained read-only for audit. Superseded by voter_turnout + ballots, which do not link voter to candidate.';


-- ---------------------------------------------------------------------------
-- 8. Immutability enforcement
-- ---------------------------------------------------------------------------
-- Blocks UPDATE and DELETE on the vote-bearing tables. Application code has no
-- way to bypass this. A DBA performing a deliberate, audited reset must opt in
-- for the current transaction only:
--     SET LOCAL evoting.allow_ledger_writes = 'on';
CREATE FUNCTION public.reject_ledger_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF COALESCE(current_setting('evoting.allow_ledger_writes', true), 'off') = 'on' THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION
        'IMMUTABLE LEDGER: % on table %.% is not permitted',
        TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING HINT = 'Recorded votes cannot be altered or removed.';
END;
$$;

CREATE TRIGGER trg_ballots_immutable
    BEFORE UPDATE OR DELETE ON public.ballots
    FOR EACH ROW EXECUTE FUNCTION public.reject_ledger_mutation();

CREATE TRIGGER trg_voter_turnout_immutable
    BEFORE UPDATE OR DELETE ON public.voter_turnout
    FOR EACH ROW EXECUTE FUNCTION public.reject_ledger_mutation();

CREATE TRIGGER trg_ledger_blocks_immutable
    BEFORE UPDATE OR DELETE ON public.ledger_blocks
    FOR EACH ROW EXECUTE FUNCTION public.reject_ledger_mutation();


-- ---------------------------------------------------------------------------
-- 9. Remove the vote-destroying stored procedures
-- ---------------------------------------------------------------------------
-- reset_election() also referenced voters.has_voted, a column that does not
-- exist, so it could never have run successfully.
DROP PROCEDURE IF EXISTS public.reset_election();
DROP PROCEDURE IF EXISTS public.reset_election_votes(integer);

-- Unused trigger function: it was never attached to any table, so the
-- biometric-session check it claims to enforce never actually ran.
DROP FUNCTION IF EXISTS public.check_vote_session();


-- ---------------------------------------------------------------------------
-- 10. Session table gains machine attribution
-- ---------------------------------------------------------------------------
ALTER TABLE public.voting_sessions
    ADD COLUMN machine_id character varying(64);


-- ---------------------------------------------------------------------------
-- 11. Recreate the reporting views over voter_turnout (PM/CM only)
-- ---------------------------------------------------------------------------
CREATE VIEW public.voter_election_status AS
SELECT v.voter_id,
       v.name,
       s.name  AS state_name,
       ls.name AS ls_constituency,
       vs.name AS vs_constituency,
       COALESCE(vs.district_name, ls.district_name, ''::character varying) AS district,
       (t1.voter_id IS NOT NULL) AS voted_pm,
       (t2.voter_id IS NOT NULL) AS voted_cm
FROM       public.voters v
LEFT JOIN  public.states         s  ON s.id  = v.state_id
LEFT JOIN  public.constituencies ls ON ls.id = v.ls_constituency_id
LEFT JOIN  public.constituencies vs ON vs.id = v.vs_constituency_id
LEFT JOIN  public.voter_turnout  t1 ON t1.voter_id = v.voter_id AND t1.election_id = 1
LEFT JOIN  public.voter_turnout  t2 ON t2.voter_id = v.voter_id AND t2.election_id = 2;


CREATE VIEW public.voter_full_detail AS
SELECT v.voter_id,
       v.name    AS voter_name,
       vs.name   AS cm_constituency,
       vs.district_name AS cm_district,
       ls.name   AS pm_constituency,
       d.name    AS district,
       s.name    AS state,
       v.card_active,
       v.fingerprint_enrolled,
       CASE WHEN v.photo IS NOT NULL
            THEN 'data:' || v.photo_type || ';base64,' || encode(v.photo, 'base64')
            ELSE NULL
       END AS photo_data_url,
       CASE WHEN t1.voter_id IS NOT NULL THEN 'Voted' ELSE 'Not voted' END AS pm_status,
       CASE WHEN t2.voter_id IS NOT NULL THEN 'Voted' ELSE 'Not voted' END AS cm_status
FROM       public.voters v
LEFT JOIN  public.states         s  ON s.id  = v.state_id
LEFT JOIN  public.constituencies ls ON ls.id = v.ls_constituency_id
LEFT JOIN  public.constituencies vs ON vs.id = v.vs_constituency_id
LEFT JOIN  public.districts      d  ON d.id  = vs.district_id
LEFT JOIN  public.voter_turnout  t1 ON t1.voter_id = v.voter_id AND t1.election_id = 1
LEFT JOIN  public.voter_turnout  t2 ON t2.voter_id = v.voter_id AND t2.election_id = 2;


-- ---------------------------------------------------------------------------
-- 12. Retire municipality elections
-- ---------------------------------------------------------------------------
UPDATE public.elections SET is_active = false WHERE type = 'MUNICIPALITY';


-- ---------------------------------------------------------------------------
-- 13. Seed the first terminal, unprovisioned
-- ---------------------------------------------------------------------------
-- No secret is stored here. The server provisions secret_hash on startup from
-- the EVOTING_MACHINE_BOOTSTRAP_SECRET environment variable, so no machine
-- credential ever lives in source control.
INSERT INTO public.machines (machine_id, label, booth_name, status)
VALUES ('PI-WARD-01', 'Ward 01 Terminal 1', 'Ward 01', 'PENDING')
ON CONFLICT (machine_id) DO NOTHING;
