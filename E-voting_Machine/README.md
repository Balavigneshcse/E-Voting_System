# E-Voting Terminal

The polling-booth client. Java 21, Swing, one dependency (Jackson) — the hardware quotation
specifies the Raspberry Pi 4 as a thin client, so the terminal stays a small single jar and
uses only JDK cryptography.

Build:

```bash
mvn package
```

Produces `target/evoting-machine.jar`. Run it with `java -jar evoting-machine.jar`, with
`config.properties` in the working directory.

## Voter flow

1. **Card** — the voter's card is read. The server resolves it to a voter.
2. **Identity** — name and photograph appear so the voter confirms it is them.
3. **Fingerprint** — a sample is captured and sent. The server compares it against the
   enrolled template and, on a match, issues a single-use token.
4. **Ballot** — candidates for the voter's own constituency, one per numbered button.
5. **Confirm** — the choice is shown once more before it becomes final.
6. **Receipt** — the ballot reference and its ledger block number.

An idle session is abandoned after `SESSION_TIMEOUT_SECONDS`.

## Ten buttons, no touchscreen

The quotation specifies a non-touch 7" display with ten illuminated buttons: eight candidate
slots plus Confirm and Cancel. Every interaction maps onto exactly those ten inputs.

| Physical button | Key on a laptop |
| --- | --- |
| Candidate 1–8 | `1`–`8` |
| Confirm | `Enter` |
| Cancel | `Esc` |
| — | `Ctrl+Shift+O` for the officer panel |

On-screen controls are clickable so a laptop demo works, but nothing depends on a pointer.
Wiring the enclosure's buttons to GPIO means feeding those ten events into the same handlers.

Because there are eight candidate buttons, a ballot cannot hold more than eight candidates.
The admin dashboard enforces this when candidates are registered.

## A confirmed vote is never lost

When the voter presses Confirm, the vote is written to an encrypted local queue **before**
any network call and before the screen says anything. Only then is delivery attempted.

- Server reachable: the vote is delivered and the receipt shows the ledger block.
- Server busy or unreachable: the screen still confirms the vote, because it genuinely is
  recorded — on the terminal, awaiting delivery. A background worker retries every
  `QUEUE_RETRY_SECONDS`. The status bar shows how many are held.

Retrying is safe. Each queued vote carries an idempotency key generated once, so the server
counts the first delivery and answers duplicates with the original receipt. A vote cannot be
counted twice however many times the network fails mid-request.

The queue survives a restart and a power cut: writes go to a temporary file that is then moved
into place, so an interrupted write leaves either the old queue or the new one.

Eligibility is still checked online. The terminal never decides whether a voter may vote — the
server does, from the database. That is what keeps one-vote-per-voter exact while a vote is
being held.

## Configuration

Edit `config.properties`. A copy next to the jar overrides the packaged one, so a deployed
terminal can be reconfigured without rebuilding.

| Key | Notes |
| --- | --- |
| `SERVER_URL` | Must be `https://`. The terminal refuses plain HTTP. |
| `MACHINE_ID` | Must match a terminal registered in the admin dashboard. |
| `PROVISIONING_SECRET` | Shown once by the dashboard when the terminal was provisioned. |
| `ADMIN_KEY` | Enables the officer panel. Leave blank on public terminals. |
| `TRUSTSTORE_PATH`, `TRUSTSTORE_PASSWORD` | Truststore holding the server certificate. |
| `SESSION_TIMEOUT_SECONDS` | Idle timeout. Default 120. |
| `QUEUE_RETRY_SECONDS` | Retry interval for held votes. Default 15. |
| `DATA_DIR` | Where held votes are kept. Must survive a reboot. |
| `KIOSK_FULLSCREEN` | Full-screen undecorated window for the enclosure. |

### Two settings that weaken security

Both default to `false`. Neither belongs in a polling booth.

- `TRUST_ANY_CERTIFICATE=true` — accepts any server certificate, so the terminal cannot tell
  the real server from an impostor.
- `ALLOW_INSECURE_TRANSPORT=true` — permits a plain-HTTP `SERVER_URL`, so ballots travel
  unencrypted.

The terminal prints a warning on startup when either is on.

## Setting up a terminal

1. In the dashboard: **Terminals → Add a terminal**, then **Issue secret**.
2. Put the ID and secret into `MACHINE_ID` and `PROVISIONING_SECRET`.
3. Export the server certificate into a truststore:

```bash
keytool -exportcert -alias evoting -keystore evoting-dev.p12 \
        -storetype PKCS12 -file evoting.crt

keytool -importcert -alias evoting -file evoting.crt \
        -keystore evoting-truststore.p12 -storetype PKCS12
```

4. Point `TRUSTSTORE_PATH` at `evoting-truststore.p12` and set its password.
5. Start the terminal. It registers, receives a token and its own signing key, and waits for
   polling to open.

## How requests are protected

Four layers, mirroring the server's filter:

1. **TLS** to the server, verified against the truststore.
2. **A machine JWT**, revocable server-side.
3. **An HMAC-SHA256 signature** over the method, path, timestamp, nonce and a hash of the body,
   keyed with a secret unique to this terminal. A stolen token alone cannot cast a vote.
4. **A timestamp and single-use nonce**, so a captured request cannot be replayed.

On top of that, the ballot choice is sealed with AES-256-GCM before it enters the request body,
using a key derived separately from the signing key. The candidate a voter picked is therefore
not visible to anything between the TLS termination point and the vote handler — not to a
reverse proxy, not to request logging.

## Simulated hardware

`SimulatedCardReader` and `SimulatedFingerprintScanner` implement the same interfaces the real
RC522 and MFS100 will. The rest of the terminal does not know which is in use.

- **Card**: the operator types the card ID or voter ID. The server still has to recognise it
  against the voter roll, so an invented ID is refused exactly as an unregistered card is.
- **Fingerprint**: the server returns a sample code during the card read, which the terminal
  treats as data carried on the card. The voter just presses the pad. The server-side check is
  real — it hashes the submitted sample and compares against the enrolled template.

Swapping in real hardware means implementing `readCardIdentifier()` and `captureSample()`.
Nothing else changes.

A caveat worth stating plainly: because the simulated card supplies a sample that will match,
this exercises the *plumbing* of biometric verification, not its security. It cannot show that
the right person is present. Only the real scanner can.

## Officer panel

`Ctrl+Shift+O`, available when `ADMIN_KEY` is set and no voter is mid-session. Opens and closes
polling, shows live turnout, and reports how many votes this terminal is holding.
