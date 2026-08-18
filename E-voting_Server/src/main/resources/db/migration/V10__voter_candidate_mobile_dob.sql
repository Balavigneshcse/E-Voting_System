-- ============================================================================
-- V10 — Mobile number and date of birth for voters and candidates
-- ============================================================================
-- Both registration forms only ever captured a name (plus geography and
-- photos), so there was nothing to phone a voter/candidate on and nothing to
-- check their age against. Age is now load-bearing: DataAdminController
-- rejects registration of a voter under 18 or a candidate under 25 (the
-- Indian constitutional minimums for a Lok Sabha/Vidhan Sabha voter and for
-- a Lok Sabha/Vidhan Sabha candidate respectively — Article 326 and Articles
-- 84(b)/173(b)), computed from date_of_birth, so it has to be a real,
-- queryable column rather than free text.
--
-- date_of_birth is nullable at the database level only so that the ~existing~
-- rows from before this migration don't fail it outright; the application
-- layer requires it (and validates the age) on every new registration from
-- here on.
-- ============================================================================

ALTER TABLE public.voters
    ADD COLUMN mobile_number character varying(15),
    ADD COLUMN date_of_birth date;

ALTER TABLE public.candidate
    ADD COLUMN mobile_number character varying(15),
    ADD COLUMN date_of_birth date;

COMMENT ON COLUMN public.voters.mobile_number IS
    '10-digit Indian mobile number, validated in DataAdminController before insert.';
COMMENT ON COLUMN public.voters.date_of_birth IS
    'Used to enforce the 18-year minimum voting age at registration time.';
COMMENT ON COLUMN public.candidate.mobile_number IS
    '10-digit Indian mobile number, validated in DataAdminController before insert.';
COMMENT ON COLUMN public.candidate.date_of_birth IS
    'Used to enforce the 25-year minimum candidacy age (Lok Sabha and Vidhan Sabha alike) at registration time.';
