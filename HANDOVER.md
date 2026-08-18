# Session Handover — E-Voting System

Written at the end of a long working session covering a full audit, a visual redesign, and
several rounds of feature work and bug fixes. This is for whoever picks this project up
next — another Claude session, a human developer, or the same person after a break —
so the reasoning behind non-obvious decisions survives, not just the code.

`AUDIT_NOTES.md` in this same folder covers the *first* pass (the initial letter-by-letter
audit). This document picks up from there and covers everything since: getting it running,
the UI redesign, and several rounds of feature requests and real bugs found along the way.

---

## 1. What this is

An electronic voting system modelled on Indian elections (PM/Lok Sabha and CM/Vidhan Sabha),
two modules:

- **`E-voting_Server`** — Spring Boot 3.3.2, Java 21, PostgreSQL, Flyway. Package root
  `Backend.*` (`controller`, `service`, `repository`, `model`, `dto`, `security`, `ledger`,
  `config`). Admin dashboard is plain HTML/CSS/JS under `src/main/resources/static/`
  (`Admin.html`, `Login.html`, `index.html`) — no build step, no framework.
- **`E-voting_Machine`** — the polling-booth kiosk client. Java 21, Swing, **one**
  dependency (Jackson) by design — this is meant to run on a Raspberry Pi. Package root
  `machine.*` (`ui`, `api`, `hardware`, `queue`, `crypto`, `config`).

Vote integrity is backed by a real hash-chained ledger (`Backend.ledger`), not just a
`votes` table. Terminals authenticate to the server via per-machine JWT + HMAC request
signing (`Backend.security.MachineAuthenticationFilter`), separate entirely from the
admin dashboard's session-cookie auth.

## 2. Running it right now (dev)

The project has a `dev` Spring profile (`application-dev.properties`) purpose-built for
one-click local iteration — see that file's own header comment for the full rationale.
Short version:

1. Empty `votingdb` Postgres database.
2. Run the server with `-Dspring.profiles.active=dev`. Shared IntelliJ run configs exist
   at `.idea/runConfigurations/` (`Server (dev)`, `Machine`) — if IntelliJ doesn't pick
   them up automatically, recreate them the same way (see `E-voting_Server/README.md`,
   "Quick local run" section near the top).
3. This gives you: no TLS, a fixed admin login (`admin` / `admin123`), a fixed terminal
   bootstrap secret, fixed (not ephemeral) master/JWT keys, and Flyway checksum
   validation turned off (see §6, "Flyway checksums" — this last one matters if you edit
   an already-applied migration during iteration).
4. A `PI-WARD-01` terminal self-provisions on server startup (seeded PENDING by `V6`,
   picked up by the dev bootstrap secret) — no dashboard step needed to get the machine
   client talking to the server.

**None of this weakens `application.properties`.** Outside the `dev` profile, every one
of these has to be supplied explicitly or the server refuses to start — that was a real
bug (§6) before it was a deliberate design.

## 3. What changed this session, by theme

### Initial audit (see `AUDIT_NOTES.md`)
Missing DB indexes on the hot voter-lookup path, dead entity/repository pairs, a real
security bug in `MasterKeyProvider` (see §6), style consistency pass, stale `.env.example`.

### Getting it running
- `application-dev.properties`, `V6__dev_test_terminal.sql`, IntelliJ run configs — the
  whole "one-click local run" path described in §2.
- Fixed a CSRF bug that made every admin POST fail with 403 while every GET worked fine
  (see §6 — this is a genuinely easy trap to fall back into if the CSRF config is ever
  touched again).

### Visual redesign ("modern, impressive, Indian flag theme")
- **Machine (`Theme.java`, `KioskFrame.java`)**: full rewrite. Rounded cards with real
  drop shadows, gradient pill buttons, a hand-painted 24-spoke Ashoka Chakra (drawn via
  `Graphics2D`, not an image asset), and — the signature element — a stylised indelible-ink
  fingertip glyph on the vote-cast screen, since that is the one physically distinctive
  thing that happens after voting in India. Verified by actually rendering it: installed
  Xvfb + a full (non-headless) JDK in this sandbox and screenshotted all nine screens,
  which caught several real bugs before they shipped (invalid Java syntax, a button fill
  that would have hidden white text, a missing `AccessibleContext` on a custom component).
- **Server (`Admin.html`, `Login.html`, `index.html`)**: matching CSS design system —
  same palette, same Chakra emblem as an inline SVG (no external assets; the CSP is
  `default-src 'self'` so nothing external would load anyway). Computed WCAG contrast
  ratios for every colour pairing used and fixed one real failure (`#CC6600` → `#9C4600`
  for small text — the original didn't meet AA at small sizes).

### District cascading, candidate photos, more CSRF
- `/districts` and `/vs-by-district` endpoints already existed server-side but were never
  called from the dashboard — wired State → District → LS/VS constituency cascading into
  both the voter and candidate forms. Extended `/admin/data/constituencies` to accept
  `districtId`, which made `/vs-by-district` redundant (removed).
- The candidate form had **no photo/symbol upload field in the HTML at all**, despite the
  backend already accepting `photoBase64`/`symbolBase64`. Added both fields.
- Found the CSRF bug mentioned above and fixed it (§6).

### Feature batch: NOTA, photo rules, candidate images, officer panel, stats
- Party colour picker removed from the candidate form entirely (superseded by showing
  the actual uploaded party symbol instead — see below).
- Photo/symbol uploads: JPG/PNG only, 5MB cap, enforced both client-side (clear rejection
  message) and server-side (`DataAdminController#decodeBase64` — never trust the browser
  alone).
- Biometric enrollment was already happening automatically at voter registration — it was
  just invisible. Gave it its own labelled section and a distinct callout for the
  resulting simulated fingerprint code.
- **NOTA**: auto-added the first time a real candidate is registered for a constituency
  (`DataAdminController#ensureNota`), backfilled for existing seed data (`V7`). Always
  sorted last regardless of insertion order — `CandidateRepository` now has an explicit
  `ORDER BY CASE WHEN name='NOTA' THEN 1 ELSE 0 END, id` instead of relying on unspecified
  default ordering (which was itself a latent bug — button-to-candidate mapping on the
  terminal was never actually guaranteed stable before this).
- **Candidate images on the terminal**: new `CandidateMediaController`
  (`/api/candidate/{id}/photo`, `/symbol`) rather than embedding images in the ballot
  payload — a ballot can carry up to 8 candidates and an image can be several MB, so
  inlining would balloon an already-signed, already-sealed request. `CandidateOption` now
  carries `hasPhoto`/`hasSymbol` booleans instead of the old `partyColor`/`symbolUrl`
  fields (both of which are now fully removed from the DTOs on both sides). The terminal
  fetches and caches each symbol once per run (`VotingMachineApp.symbolCache`), on the
  worker thread, never the EDT.
- Ballot and identity screens now show real constituency **names** — both were showing
  bare numeric IDs before (`"Constituency 42"`).
- Officer panel: added a running "votes recorded at this terminal today" counter (separate
  from server-wide turnout) and a "Sync now" button (`VoteSyncWorker#nudge()` already
  existed and did exactly this — it just wasn't wired to a button).
- Results page: NOTA count/%, candidates contesting, constituencies reporting, leading
  party, closest race — all new stat cards.

### Bug hunting: photo "missing", NOTA still blocking some voters
- Investigated a reported "voter photo is gone" issue by actually testing, not just
  re-reading code: installed real Postgres and real Jackson jars via `apt` (Maven Central
  isn't reachable from this sandbox) and verified the full pipeline — binary round-trip
  through Postgres, Jackson record deserialization by field name (ruling out a
  positional-binding theory), and the exact upload-validation logic extracted and run
  standalone against real image bytes. Could not reproduce a bug. Added a `photoSaved`
  flag to the registration response so this is never ambiguous again — if a photo didn't
  attach, the officer sees that immediately instead of finding out later at the terminal.
- The *actual* NOTA bug: the earlier fix only added NOTA once a real candidate existed for
  a constituency. A constituency with **zero** candidates ever registered still blocked
  every voter with "no candidates registered." Fixed in `VotingService#ensureNota`, called
  inline inside `startSession()` before the ballot is built — not as its own
  `@Transactional` method (see §6, self-invocation).
- Auto-generated voter IDs (`V001`, `V002`, ... — `DataAdminController#nextVoterId`), NFC
  card ID set equal to the voter ID. Manual entry removed from the form.
- Found and fixed a real Postgres portability bug: `V1`'s `pg_dump` preamble included
  `SET transaction_timeout = 0;`, a PostgreSQL 17-only setting, which broke the migration
  on PG15/16 — directly contradicting the README's stated minimum. Removed.
- Results page restructured into two dedicated pages (**PM election results** / **CM
  election results**) instead of one page with a dropdown — `getPmStateResults` /
  `getCmStateResults` already existed server-side (same "built but never wired up"
  pattern as the district cascading) and are now surfaced as a state-wise /
  district-wise browser on each page.

### Most recent batch: next election, per-state CM, Excel export
- **Two real, severe bugs found and fixed via a new test harness** (see §5) — both were
  leftover/mistyped element-ID references in `Admin.html` that threw on page load and
  **silently halted the entire script**, which is why "no dropdowns are visible" was
  reported: nothing after the throw ever ran, including all the dropdown-population code.
- "Start next PM/CM election" — `ElectionAdminService#nextElection`, counts up each
  type's cycle independently.
- Per-state CM polling — deliberately built as an *additional* gate on top of the
  existing "exactly one active election" model rather than a restructuring of it (see
  §6). New `election_open_states` table (`V8`); a fresh CM election starts with every
  state closed.
- Excel export (`ResultsExportService`, Apache POI) — summary, party totals, constituency
  leaders, and full state/district/constituency detail as separate sheets. **Not
  compile-verified** — see §7.

### This session: Tamil Nadu district-link bugs, voter/candidate mobile + DOB + eligibility
- **Erode/Tiruppur Lok Sabha seats were swapped**, and Viluppuram and nine other Tamil
  Nadu assembly seats had a NULL district — both reported by the person running the
  system. Root cause: `V4` assigns an LS seat's `district_id` to "whichever district
  holds the majority of its real assembly segments" (see `V4`'s own header). Erode's six
  segments split 3 Erode / 2 Tiruppur / 1 Namakkal, but one of the three Erode segments
  (Modakurichi) was itself one of the ten NULL-district VS rows, so it wasn't counted —
  turning a real 3-2 Erode majority into a stored 2-2 tie that landed on Tiruppur.
  Tiruppur's own LS row, despite the name, genuinely does have an Erode-district majority
  (4 of 6 segments) and needed no change.
- **Fixed in `V9`**, as plain `UPDATE`s (not a rewrite of `V4`, which is immutable —
  see §6). Every value was checked two independent ways: against the seat's real district
  per Wikipedia/ECI, and against the district of its immediate assembly-number neighbours
  in this table (Tamil Nadu's AC numbers run in contiguous per-district blocks in this
  seed data, which turned out to be a strong independent check on its own).
- **This only covers Tamil Nadu.** The same NULL-district gap exists in ~300 assembly
  seats across most other states — see §7 for the breakdown. Not fixed; would need the
  same per-seat verification against a real source, state by state.
- **Voter and candidate registration now collect mobile number and date of birth**
  (`V10`, `Voter`/`Candidate` entities, `DataAdminController`). Both are required and
  validated server-side: mobile must be a 10-digit number starting 6-9 (TRAI's allocated
  range); date of birth must parse and not be in the future. Age is computed from it and
  **enforced as a hard eligibility gate** — registration is rejected outright, not
  flagged, for a voter under 18 (Article 326) or a candidate under 25 (Articles 84(b) /
  173(b), same minimum for Lok Sabha and Vidhan Sabha). There's no separate "eligible"
  flag anywhere to fall out of sync: an ineligible row simply can't be created. The
  voters/candidates list endpoints also now return a computed `age` column
  (`DATE_PART('year', AGE(date_of_birth))`) so an officer can audit an existing
  registration without doing the arithmetic by hand.
- **Verification in this session**: all ten migrations (`V1`-`V10`) applied end to end
  against real Postgres 16, including a spot-check that the twelve corrected Tamil Nadu
  rows now hold the right `district_id`/`district_name`, and that `DATE_PART('year',
  AGE(...))` returns the expected age for known test dates. `Admin.html`'s script block
  was extracted and syntax-checked with `node --check`. The Java side (new fields on
  `Voter`/`Candidate`, new validation helpers and branches in `DataAdminController`) was
  reviewed carefully and brace/paren-balance-checked but **not compiled** — same Maven
  Central limitation as always (§5). First thing to check after pulling this: `mvn
  compile`.

## 4. Architecture facts a successor needs before touching related code

- **Election activation is still "exactly one active per type, globally"** — PM and CM
  are independent of each other, but within one type, opening a new election closes the
  previous one of that same type. This is deliberate: it's what lets a terminal work out
  which ballot to show without being configured per booth, and it's why per-state CM
  was built as a gate *on top of* an active election rather than N separate always-active
  election rows per state.
- **NOTA is not seeded for every constituency in the database up front.** It's added the
  first time a real candidate is registered there, or the first time a voter from that
  constituency starts a session (`VotingService#ensureNota` is the backstop for
  constituencies that never get a real candidate at all). Seeding it for all ~4,600
  constituencies in the real electoral geography data up front was deliberately avoided —
  most will never be contested in this deployment.
- **Candidate/voter images are fetched on demand, never embedded in a live payload.**
  Voter photo is the one exception (embedded base64 in the voter-details response) —
  that's a single image per session, not up to 8 at once, so the size tradeoff is
  different.
- **The `dev` profile is real, not a stub.** Every dev-only default lives in
  `application-dev.properties` and is documented inline with *why* it's safe there and
  not in `application.properties`.

## 5. Testing tools now available in this sandbox

Worth knowing about for whoever continues this, since they meaningfully changed what
"verified" means in this session — several real, shipping-blocking bugs were only found
this way, not by reading code:

- **Real PostgreSQL** (`apt-get install postgresql`, already done, service may need
  `service postgresql start`). Used to actually apply every migration in order end to end,
  not just review the SQL.
- **Real Jackson jars** (`apt-get install libjackson2-databind-java
  libjackson2-annotations-java libjackson2-core-java`, jars land in `/usr/share/java/`).
  Used to settle a real question (does Jackson bind Java records by name or position)
  with a real test instead of documentation-recall.
- **Node.js + jsdom** (`npm install jsdom` in `/tmp`) — runs `Admin.html`'s actual
  `<script>` block against a simulated DOM with mocked `fetch` responses, and reports any
  runtime error with a line number. This is what caught both "leftover `getElementById`
  reference" bugs — static grep for element *definitions* isn't enough; something has to
  actually execute the script to catch a dangling *reference*. Worth running this after
  any nontrivial `Admin.html` change from now on. (See the test files referenced in this
  session's transcript for the mock-response pattern — they aren't persisted anywhere in
  the repo, just written fresh to `/tmp` each time.)
- **Xvfb + a full (non-headless) JDK** (`apt-get install xvfb openjdk-21-jdk`) — actually
  renders the Swing kiosk off-screen and rasterizes it to PNG, catching real Graphics2D
  bugs that reading the painting code cannot.
- **What's still not testable here**: full `mvn` builds against Maven Central (this
  sandbox's network is allow-listed to a handful of package registries, not Maven
  Central) — so no Spring context actually boots in this sandbox. Every server-side
  change this session was verified by careful reading, real Postgres for the SQL layer,
  and real Jackson for serialization — but never a genuine `ApplicationContext` startup.

## 6. Hard-won gotchas

Each of these cost real debugging time this session — recorded so they don't get
rediscovered the same way:

- **Spring Security 6's default CSRF handler doesn't match a JS client that reads the
  raw cookie.** `XorCsrfTokenRequestAttributeHandler` (the default since Spring Security
  6.0) BREACH-masks the token wherever it's rendered and expects the *masked* form back.
  A client that reads `XSRF-TOKEN` straight from `document.cookie` and sends that back
  verbatim — which is what this dashboard's JS does, and is a completely standard
  pattern — gets "unmasked" into garbage server-side, and every POST fails CSRF
  validation with a 403 that looks exactly like an expired session. Fixed with
  `.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())` in `SecurityConfig`.
  If CSRF config is ever touched again, keep this.
- **`@Transactional` is proxy-based; self-invocation bypasses it silently.** Calling
  `this.someOtherMethod()` from within the same Spring-managed bean does not go through
  the bean's proxy, so `@Transactional` (including `Propagation.REQUIRES_NEW`) on that
  other method is quietly a no-op. This surfaced while trying to build a retry-on-collision
  loop for voter ID assignment — a `REQUIRES_NEW` retry loop inside one method looks like
  it should get a fresh transaction per attempt and does not. Ended up simplifying to a
  single attempt with a clear "please try again" error rather than fighting this.
- **A `pg_dump` preamble can silently make a migration version-specific.** `V1` shipped
  with `SET transaction_timeout = 0;` — a PostgreSQL 17+-only setting — because it was
  dumped from a PG18 server, breaking the migration on the documented PG15+ minimum.
  Worth grep-ing any future `pg_dump`-sourced migration for `SET` statements before
  trusting it's portable.
- **Flyway checksums are immutable once applied.** Editing an already-applied migration
  file (as happened when fixing the `transaction_timeout` line above) makes Flyway refuse
  to start on any database that already recorded the old checksum. `dev` profile now sets
  `spring.flyway.validate-on-migrate=false` for exactly this reason — real deployments
  still get the real check.
- **A single uncaught error in top-level `<script>` code halts everything after it,
  silently.** Both severe `Admin.html` bugs this session were exactly this shape: a
  `document.getElementById('somethingRemoved').addEventListener(...)` at the top level
  (not inside a function), where the element no longer existed. `getElementById` returns
  `null`, calling `.addEventListener` on `null` throws, and because this is synchronous
  top-level code, every line after it — including the entire startup chain that populates
  every dropdown on the page — never executes. Static grep for the *old* ID being defined
  isn't enough to catch this; grep for the ID being *referenced* too, or better, run it
  (§5).

## 7. Known issues / flagged, not fixed

- **Apache POI (`pom.xml`) is not compile-verified.** Added for Excel export
  (`ResultsExportService`). Written carefully against POI's stable, mature core API
  (`XSSFWorkbook`/`Sheet`/`Row`/`Cell`), but this sandbox can't reach Maven Central to
  actually build it. First thing to check after pulling this: does `mvn compile` succeed.
- **`getPmStateResults` and `getCmStateResults` use inconsistent column aliases** for the
  district column (`district` vs `district_name`). Harmless — the JS that consumes both
  already checks `item.district_name || item.district || '—'` — but worth normalizing if
  that service is touched again.
- **`Candidate.getPartyColor()`/`setPartyColor()` are dead code** — the DB column and
  entity mapping still exist (some old seed candidates still have a value), but nothing
  in the application calls either accessor anymore since the color picker was removed
  from the UI. Low priority; safe to remove entirety whenever convenient.
- **`V6__dev_test_terminal.sql` is redundant with `V3`**, which already seeds an
  identical `PI-WARD-01` PENDING row. Harmless (`ON CONFLICT DO NOTHING`), and Flyway
  migrations are immutable once shipped, so it stays — just don't be confused by the
  duplication if you go looking for where that terminal gets seeded.
- **The voter-photo-missing report was never reproduced.** Investigated thoroughly (§3)
  without finding a bug. Most likely explanation is the photo genuinely wasn't attached
  at registration for that particular voter — the new `photoSaved` response flag should
  make this unambiguous going forward. Worth a second look only if it recurs with
  `photoSaved: true` in the response and the photo still doesn't show.
- **~300 assembly (VS) seats outside Tamil Nadu still have a NULL `district_id`**, the
  same bug `V9` fixed for Tamil Nadu's ten. Counted directly from the seed data; largest
  first: Madhya Pradesh 32, Gujarat 31, West Bengal 28, Jharkhand 24, Karnataka 24, Uttar
  Pradesh 24, Arunachal Pradesh 18, Assam 17, Bihar 17, Kerala 17, Chhattisgarh 13, Andhra
  Pradesh 11, Punjab 8, Telangana 6, Maharashtra 5, then single digits down to Haryana,
  Mizoram, Nagaland, Tripura and Uttarakhand at 1 each. No duplicate constituency numbers
  and no `district_id`/`district_name` internal inconsistencies were found anywhere else
  in the seed data — this NULL gap is the only remaining structural issue found. Each row
  needs the same treatment `V9` gave Tamil Nadu: a real source per seat, not a guess.
- **New JPA columns (`Voter.mobileNumber`/`dateOfBirth`, same on `Candidate`) are nullable
  at the database level even though the application now requires both on every new
  registration.** Deliberate — see `V10`'s own comment — so that rows created before this
  migration don't fail a retroactive `NOT NULL`. Worth tightening to `NOT NULL` in a
  future migration once/if a backfill for pre-existing rows is done.

## 8. Suggested next steps

Roughly in the order I'd tackle them:

1. Confirm `mvn compile`/`mvn package` succeeds — with the new POI dependency and with
   this session's `Voter`/`Candidate`/`DataAdminController` changes, neither compiled in
   this sandbox (§5).
1a. Pick the next state to verify and fix for the NULL-district bug (§7) — Madhya
   Pradesh and Gujarat are the biggest, at 32 and 31 seats respectively.
2. Run the `Admin.html` jsdom check (§5) after any further dashboard changes — it's cheap
   and has already caught two real, severe bugs this session.
3. Consider whether PM elections might eventually want the same kind of granularity CM
   just got (currently PM has no sub-national gating at all, which is correct for a
   single national election — but worth a deliberate decision if that assumption ever
   changes).
4. The officer panel (`KioskFrame`/`VotingMachineApp`) could still use a manual
   voter-lookup or vote-history view — flagged earlier as a real possibility but not
   built, to avoid guessing at a feature decision that deserved a real answer from the
   person running the election.
