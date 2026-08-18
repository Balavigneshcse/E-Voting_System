-- ============================================================================
-- V5 — Indexed voter lookup
-- ============================================================================
-- Every NFC tap resolves to a voter through VoterRepository.findByVoterId or
-- findByNfcCardId, and every session start resolves a ballot through
-- CandidateRepository.findByElectionIdAndConstituencyId. Both are on the hot
-- path of the terminal's voter journey and both ran, until this migration,
-- as sequential scans: neither `voters.voter_id`, `voters.nfc_card_id` nor
-- `candidate(election_id, constituency_id)` had a supporting index anywhere in
-- V1, V3 or V4, despite the `Voter` entity already claiming `unique = true`
-- on the first two.
--
-- With ten seeded voters that is invisible. With an electorate in the
-- millions it is the difference between an indexed lookup — the same
-- structure a "is this username taken" check uses — and a full table scan on
-- every single card tap, repeated for as many machines as are attached to
-- this server at once.
--
-- A UNIQUE index does two jobs at once here: it is what makes the lookup an
-- index seek instead of a scan, and it is what turns "two voters somehow
-- share an ID" from a silently-possible data bug into a rejected write. The
-- registration race in DataAdminController#addVoter — check-then-insert with
-- no database backing — was only ever safe by accident until this.
-- ============================================================================

CREATE UNIQUE INDEX uq_voters_voter_id  ON public.voters (voter_id);
CREATE UNIQUE INDEX uq_voters_nfc_card  ON public.voters (nfc_card_id) WHERE nfc_card_id IS NOT NULL;

COMMENT ON INDEX public.uq_voters_voter_id IS 'Backs every card-tap lookup by voter ID and is the database-level one-voter-one-record guarantee. Without it, VoterController#verifyCard sequential-scans the entire electorate on every tap.';
COMMENT ON INDEX public.uq_voters_nfc_card IS 'Backs the NFC-UID lookup path. Partial: a voter not yet issued a card has no nfc_card_id, and Postgres would already treat multiple NULLs as non-conflicting, but the partial form keeps the index itself smaller as enrollment rolls out gradually.';

CREATE INDEX idx_candidate_election_constituency ON public.candidate (election_id, constituency_id);

COMMENT ON INDEX public.idx_candidate_election_constituency IS 'Backs VotingService#ballotFor, called on every session start to build the voter''s ballot. Composite and in this order because every query filters by both columns together.';
