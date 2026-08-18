-- ============================================================================
-- V8 — Per-state CM polling
-- ============================================================================
-- A Lok Sabha (PM) election is one national event: "exactly one election active
-- at a time", enforced by ElectionAdminService#open, is the right model for it
-- and stays untouched here.
--
-- A Vidhan Sabha (CM) election is not really one event — each state runs its
-- own assembly election, and they do not all open on the same day in reality.
-- Rather than modelling that as N separate Election rows (which would mean
-- reworking how a terminal discovers "the" active election, a core invariant
-- everything else depends on), this adds a second gate on top of the existing
-- one: a CM election can be active in the existing sense while still being
-- closed to a given state's voters until that state is explicitly opened here.
-- A PM election ignores this table entirely.
--
-- Presence of a row means open. A freshly-activated CM election starts with
-- no rows — every state closed — because that matches "enable for each state
-- separately" as an opt-in action, not an opt-out one.
-- ============================================================================

CREATE TABLE public.election_open_states (
    election_id INTEGER     NOT NULL REFERENCES public.elections(id) ON DELETE CASCADE,
    state_id    INTEGER     NOT NULL,
    opened_at   TIMESTAMP   NOT NULL DEFAULT now(),
    PRIMARY KEY (election_id, state_id)
);

COMMENT ON TABLE public.election_open_states IS
    'Which states a CM election is open to voters in. Ignored for PM elections, which are always national.';
