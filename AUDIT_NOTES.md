# Audit notes — full codebase pass

This documents one review pass over the whole project: every Java file in both
modules, every Flyway migration, every static admin page. Kept here rather than
only in chat so the reasoning survives alongside the code.

## Fixed

**Missing voter-lookup indexes (the main one).** `voters.voter_id` and
`voters.nfc_card_id` had no database index in any migration, despite the `Voter`
entity claiming `unique = true` on both. Every card tap was a sequential scan.
Fixed in `V5__voter_lookup_indexes.sql`, which also indexes
`candidate(election_id, constituency_id)` for the per-voter ballot lookup on
every session start. This is the same principle behind any large platform's
"is this username taken" check: an indexed exact-match lookup, not a scan.

**A production secret could silently fall back to an ephemeral key.**
`MasterKeyProvider` was supposed to refuse to start without
`EVOTING_MASTER_KEY`/`EVOTING_JWT_SECRET` outside development, but it treated
Spring's unset-profile `default` as development too — which is what any
deployment gets unless it explicitly passes a profile flag. The one deployment
mode nobody remembers to configure was also the one that got weak,
restart-losing keys instead of a startup failure. Now only an explicit `dev`
profile enables the fallback; documented in the server README.

**Dead entity/repository pairs removed.** `State`, `Constituency`,
`ElectionConfig` and their repositories were never referenced anywhere outside
their own files — all real reads of states/constituencies/election config go
through `JdbcTemplate` in `DataAdminController`, by design (see that class's
own Javadoc). `Constituency` also only mapped 3 of the table's 10 columns.
Removed rather than completed, since nothing needed them.

**Stale `.env.example`.** Referenced `EVOTING_MACHINE_SECRET`, which nothing
reads, and was missing four variables the app requires and the README
documents (`EVOTING_MASTER_KEY`, `EVOTING_JWT_SECRET`,
`EVOTING_FINGERPRINT_PEPPER`, `EVOTING_MACHINE_BOOTSTRAP_SECRET`,
`SERVER_SSL_KEYSTORE_PASSWORD`). Rewritten to match `application.properties`
exactly.

**Machine module targeted Java 17.** Aligned to 21, matching the server and
the stated stack.

**Style consistency.** `Voter`, `Candidate`, `Election`, `DataInitializer`,
`AuthController` and three repository interfaces were in an older, denser
style (inline annotations, field injection, `System.out.println` instead of
SLF4J, abbreviated names) inconsistent with the rest of the codebase.
Rewritten to match. Two confirmed-unused alias methods on `Voter`
(`getConstituencyId`/`setConstituencyId`, never called anywhere) removed
rather than kept as a second name for `vsConstituencyId`.

**`Login.html` linked back to `index.html`**, which immediately redirects to
`Login.html` — a silent loop, since this server has no public voting page by
design. Link removed.

## Checked and left alone (verified intentional, not bugs)

- `evoting.security.simulation-enabled=true` default — the machine module has
  no real hardware implementation yet (`SimulatedCardReader`/
  `SimulatedFingerprintScanner` are the only `CardReader`/`FingerprintScanner`
  implementations that exist), so this isn't a security default that got left
  on by accident; it's the only mode that currently works, and it's already
  honestly documented in the server README's "Hardware simulation" section.
- The ~24,000 lines of seed data across `V1`/`V4` (real Lok Sabha / Vidhan
  Sabha constituencies, sourced from the Local Government Directory per the
  migration's own header comment) — legitimate data, not bloat.

## Not done in this pass

- No build verification via `mvn clean package`: this sandbox's network is
  allow-listed to a handful of package registries and does not include Maven
  Central, so dependencies can't be resolved here. Run it locally before
  deploying.
- No changes to `KioskFrame`/`VotingMachineApp`, `ledger/*`, `security/*`
  (beyond `MasterKeyProvider`), or the `Admin.html` dashboard — read in full
  and found consistent with the rest of the codebase already.
