-- ============================================================================
-- V6 — Dev test terminal
-- ============================================================================
-- Seeds one machine row, PENDING, so a freshly built database has something
-- for the bootstrap secret (EVOTING_MACHINE_BOOTSTRAP_SECRET, wired up by
-- default in application-dev.properties) to provision on the server's next
-- startup, with no trip through the admin dashboard needed first.
--
-- Harmless outside of local development: without the dev profile active,
-- evoting.security.machine-bootstrap-secret is empty by default, so
-- MachineService#provisionPendingTerminals leaves this row exactly as
-- PENDING and it must be provisioned from the dashboard like any other
-- terminal, the same as a real deployment would.
-- ============================================================================

INSERT INTO public.machines (machine_id, label, booth_name)
VALUES ('PI-WARD-01', 'Test Terminal 1', 'Local Development')
ON CONFLICT (machine_id) DO NOTHING;
