-- ============================================================================
-- V7 — NOTA backfill
-- ============================================================================
-- DataAdminController#ensureNota now adds NOTA automatically the first time a
-- real candidate is registered for a constituency, going forward. This
-- backfills the same guarantee for constituencies the seed data already put
-- candidates in, so an existing demo ballot gets NOTA without needing someone
-- to register one more candidate there first.
--
-- One NOTA row per (election_id, constituency_id) pair that has at least one
-- candidate and does not already have one. CandidateRepository always sorts
-- NOTA last regardless of its row id, so where it lands in this insert does
-- not affect ballot order.
-- ============================================================================

INSERT INTO public.candidate (name, party, election_id, constituency_id, state_id)
SELECT 'NOTA', 'None of the Above', c.election_id, c.constituency_id, MIN(c.state_id)
FROM   public.candidate c
WHERE  c.constituency_id IS NOT NULL
GROUP  BY c.election_id, c.constituency_id
HAVING COUNT(*) FILTER (WHERE c.name = 'NOTA') = 0;
