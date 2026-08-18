# E-Voting Server

Spring Boot 3.3 / Java 21 / PostgreSQL / Flyway. Runs the election: voter and candidate
records, polling control, live counting, the vote ledger, and the terminal registry.

Only Lok Sabha (`PM`) and Vidhan Sabha (`CM`) elections are supported.

## What guarantees this server provides

**A voter can vote at any booth.** No voter is bound to a terminal, and no terminal decides
which ballot to show. The server derives the ballot from the voter's own registration.
One-vote-per-voter is a `UNIQUE (voter_id, election_id)` constraint on `voter_turnout`, so it
holds across booths and across server instances rather than depending on application code.

**Recorded votes cannot be changed.** `ballots`, `voter_turnout` and `ledger_blocks` reject
`UPDATE` and `DELETE` through a database trigger. There is no reset endpoint. Re-running a
demonstration means restoring a database snapshot.

**A ballot cannot be traced to a voter.** Two tables, deliberately never joined:
`voter_turnout` records *who* voted with no candidate; `ballots` records *what* was voted for
with no voter. `ballots.cast_at_hour` is truncated to the hour so the two cannot be
re-associated by matching timestamps.

**Tampering is detectable.** Every ballot is committed to a hash chain in `ledger_blocks`,
persisted so it survives a restart. Verification recomputes the whole chain from the table, so
altering a stored ballot breaks its own hash and every subsequent back-link.

**Results are live.** Tallies are computed from `ballots` on each request — no cache, no
scheduled aggregation. A vote committed a second ago appears in the next response.

**Terminals are individually identified and revocable.** Each has its own provisioning secret
and its own HMAC signing key, so one can be cut off without disturbing the others.

## Prerequisites

- JDK 21
- PostgreSQL 15 or later
- Maven 3.9+

## Quick local run (no TLS setup, IntelliJ)

For clicking around locally — not for anything resembling a real election. Skips the
keystore and every secret entirely.

1. Create an empty `votingdb` database (see step 1 below).
2. Open this project in IntelliJ, then run the shared **"Server (dev)"** configuration
   (visible in the run-configuration dropdown once the project loads — it's checked
   into `.idea/runConfigurations`, so it should already be there).
3. If your local Postgres password isn't `postgres`, don't edit
   `application-dev.properties` to fix it — that file is tracked in git. Either set a
   `DB_PASSWORD` environment variable on the run configuration, or create an untracked
   `src/main/resources/application-local.properties` with
   `spring.datasource.password=your-password` and run with both profiles active
   (`-Dspring-boot.run.profiles=dev,local`). See the comment at the top of
   `application-dev.properties` for the full reasoning.
4. That's it. The dev profile disables TLS, fills in a fixed admin login
   (`admin` / `admin123`) and a fixed terminal bootstrap secret, and Flyway seeds one
   test terminal (`PI-WARD-01`) that self-provisions on startup — no dashboard step
   needed. Run the machine module's shared **"Machine"** configuration the same way;
   its packaged `config.properties` already points at this dev server.

Everything below is the real path: real TLS, real secrets, nothing defaulted.

## First run

### 1. Create an empty database

```sql
CREATE DATABASE votingdb;
```

Leave it empty. Flyway builds the schema and seed data from `V1`, then applies `V2` and `V3`
in order. Do not enable baselining.

### 2. Generate a TLS keystore

The server refuses plain HTTP by default, because a ballot must not travel unencrypted.

```bash
keytool -genkeypair -alias evoting -keyalg RSA -keysize 2048 -validity 730 \
        -storetype PKCS12 -keystore evoting-dev.p12 \
        -dname "CN=evoting-server,O=Your Organisation,C=IN" \
        -ext "SAN=dns:localhost,ip:127.0.0.1"
```

For real use, add the server's LAN hostname and IP to the `SAN` list so terminals can verify
the certificate. Keystores are gitignored; generate one per deployment.

### 3. Supply the secrets

Nothing secret lives in `application.properties`. Every value below must come from the
environment.

| Variable | Purpose |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | database connection |
| `EVOTING_MASTER_KEY` | base64 32 bytes — encrypts terminal signing keys at rest |
| `EVOTING_JWT_SECRET` | base64 32 bytes — signs terminal tokens |
| `EVOTING_FINGERPRINT_PEPPER` | mixed into fingerprint templates |
| `EVOTING_MACHINE_BOOTSTRAP_SECRET` | provisions terminals still in `PENDING` on startup |
| `EVOTING_ADMIN_API_KEY` | election officer key for privileged terminal actions |
| `EVOTING_ADMIN_USERNAME`, `EVOTING_ADMIN_PASSWORD` | dashboard sign-in |
| `SERVER_SSL_KEYSTORE_PASSWORD` | keystore password |

Generate the two 32-byte keys:

```bash
# Linux / macOS
openssl rand -base64 32
```

```powershell
# Windows PowerShell
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
```

**Changing `EVOTING_FINGERPRINT_PEPPER` invalidates every existing fingerprint enrollment.**
**Losing `EVOTING_MASTER_KEY` means re-provisioning every terminal.** Back both up.

Spring Boot does not read `.env` files. Export the variables in your shell, or use your
service manager's environment configuration.

For quick local iteration only — never for a real election — you can skip
`EVOTING_MASTER_KEY` and `EVOTING_JWT_SECRET` by running with an explicit `dev` profile
(`mvn spring-boot:run -Dspring-boot.run.profiles=dev`). The server then derives ephemeral
keys and logs a warning; every machine credential is invalidated on the next restart.
With no profile flag at all — the default, and what a real deployment should use — both
variables are required and the server refuses to start without them.

### 4. Start

```bash
mvn spring-boot:run
```

Then open `https://localhost:8443/`. A self-signed certificate will prompt a browser warning
on first visit.

## Administration dashboard

Sign in at `/Login.html`. The dashboard has six sections:

- **Live results** — turnout, party totals, per-constituency leaders, votes per terminal.
  Auto-refreshes every 5 seconds.
- **Polling control** — open and close polling. Exactly one election is open at a time.
- **Terminals** — add a terminal, issue its provisioning secret, revoke it.
- **Ledger audit** — verify chain integrity and browse blocks.
- **Voters** and **Candidates** — registration.

## Bringing a terminal online

1. **Terminals → Add a terminal.** Give it an ID such as `PI-WARD-02`, a label and a booth
   name.
2. **Issue secret.** The provisioning secret is shown once and cannot be retrieved again.
3. Put the ID and secret in that terminal's `config.properties` as `MACHINE_ID` and
   `PROVISIONING_SECRET`.
4. Export the server certificate into a truststore for the terminal — see the machine README.

If a terminal is lost, **Revoke** it. Its tokens stop working immediately. Re-issuing a secret
also rotates its signing key.

## Hardware simulation

`EVOTING_SIMULATION_ENABLED=true` (the default) stands in for the RC522 reader and the MFS100
scanner. A voter with no enrolled template is auto-enrolled with a deterministic simulated one
so the real verification path still runs: the terminal submits a sample, the server hashes it
with the pepper and compares against the stored template in constant time. The terminal cannot
assert that a fingerprint matched.

Be clear about the limit: this simulates the *plumbing* of biometric verification, not its
security. Because the simulated card supplies a sample that will match, it cannot demonstrate
that the right person is present. Only the real scanner can. Set this to `false` once the
MFS100 is connected.

## Schema changes

Add a new `V4__*.sql` and let Flyway apply it. Never edit an applied migration — Flyway
validates checksums and will refuse to start.

Note that the immutability triggers block `UPDATE` and `DELETE` on the vote-bearing tables. A
migration that genuinely must touch them has to opt in for that transaction:

```sql
SET LOCAL evoting.allow_ledger_writes = 'on';
```

## Machine API

Sixteen endpoints under `/api/**`, in five controllers:

| Controller | Endpoints |
| --- | --- |
| `ElectionController` | `POST /api/machine/register`, `GET /api/election/status`, `GET /api/candidates` |
| `VoterController` | `POST /api/voter/verify-card`, `POST /api/voter/verify-fingerprint`, `GET /api/voter/{id}/details`, `GET /api/voter/{id}/status` |
| `VotingController` | `POST /api/session/start`, `POST /api/vote/cast`, `POST /api/session/cancel`, `POST /api/session/timeout` |
| `ResultsController` | `GET /api/results`, `GET /api/audit/log`, `GET /api/results/turnout` |
| `AdminController` | `GET /api/admin/elections`, `POST /api/admin/election/open`, `POST /api/admin/election/close` |

Every one except `register` requires four things, checked by
`MachineAuthenticationFilter` before any controller runs:

1. HTTPS.
2. A machine JWT whose `jti` is present, unexpired and unrevoked in `machine_tokens`.
3. An HMAC-SHA256 signature over `METHOD\npath\ntimestamp\nnonce\nsha256(body)`, keyed with
   that terminal's own signing key.
4. A timestamp inside the freshness window and a nonce not seen before.

`register` is exempt because a terminal has no signing key until registration returns one. It
authenticates with its one-time provisioning secret, and the reply carries a signing key — so
it must never be served over plain HTTP.

The `ResultsController` and `AdminController` endpoints additionally require the
`X-Admin-Key` header, so an unattended terminal cannot open polling or read results by itself.
