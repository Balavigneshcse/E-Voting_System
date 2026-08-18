# E-Voting System

An electronic voting system covering voter and candidate registration, polling-booth
terminals, live result reporting, and election cycle progression for Lok Sabha (PM) and
Vidhan Sabha (CM) elections in India.

The system is two separate applications that talk to each other over HTTPS:

| Module | What it is | Directory |
| --- | --- | --- |
| Server | Spring Boot backend: the database, the admin dashboard, and the API every terminal talks to | `E-voting_Server/` |
| Machine | The polling-booth kiosk client: a Java Swing application | `E-voting_Machine/` |

Each has its own detailed README (`E-voting_Server/README.md`,
`E-voting_Machine/README.md`) covering its API, configuration, and security model in
full. This file is the starting point: what to install, and how to get both pieces
running together on a machine that has never seen this project before.

## Requirements

Install these before doing anything else. Version numbers are minimums, not
suggestions — the project uses language and driver features from exactly these
versions.

| Requirement | Version | Check it's installed | Notes |
| --- | --- | --- | --- |
| Java Development Kit (JDK) | 21 | `java -version` | Both modules target Java 21. `keytool`, used later for TLS, is part of the JDK — nothing extra to install for it. |
| Apache Maven | 3.9 or later | `mvn -version` | Not required if you only ever build and run through IntelliJ, which bundles its own Maven — but needed for any command-line build. |
| PostgreSQL | 15 or later | `psql --version` | The server's database. Needs to be installed and running locally, or reachable over the network. |
| Git | any recent version | `git --version` | To clone the repository. |
| IntelliJ IDEA | any recent version, Community or Ultimate | — | Optional but recommended — the project is set up for a one-click run from IntelliJ (see below). Not required if you're comfortable driving Maven and `java -jar` by hand instead. |

Nothing else needs to be installed separately. The machine client has exactly one
external dependency (Jackson), and the server's dependencies (Spring Boot, Flyway,
Apache POI, the PostgreSQL driver) are all pulled in by Maven automatically the first
time you build.

### Installing the requirements

**Windows**

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
winget install Apache.Maven
winget install PostgreSQL.PostgreSQL.16
winget install Git.Git
```

**macOS** (Homebrew)

```bash
brew install openjdk@21 maven postgresql@16 git
```

**Linux** (Debian/Ubuntu)

```bash
sudo apt update
sudo apt install openjdk-21-jdk maven postgresql git
```

Whichever platform, confirm each tool afterward with the "check it's installed"
commands in the table above before continuing.

## Getting the code

```bash
git clone <this repository's URL>
cd E-Voting_System
```

## Running it on a new machine — the fast path

This is the shortest route from a clean checkout to a working system on your own
computer: no TLS setup, no secrets to generate, everything defaulted for local
iteration. It is not how a real election would be run — see each module's own README
for that — but it is the right way to first bring the project up and confirm
everything works.

### 1. Create the database

Make sure PostgreSQL is running, then create one empty database:

```bash
psql -U postgres -c "CREATE DATABASE votingdb;"
```

Leave it empty. The server builds the entire schema and seed data itself on first
start, through Flyway.

### 2. Start the server

**With IntelliJ (recommended):**

1. Open the repository root in IntelliJ. Let it finish indexing and importing both
   Maven modules.
2. In the run-configuration dropdown at the top of the window, look for a shared
   **"Server (dev)"** configuration. If your checkout already has one (checked into
   `.idea/runConfigurations`), select it and press Run.
3. If there is no such configuration yet, create one: **Run > Edit Configurations > +
   > Spring Boot**, main class `Backend.EvotingBackendApplication`, and under **Active
   profiles** enter `dev`. Save it, then press Run. Consider ticking "Store as project
   file" / sharing it through version control so the next person who clones the repo
   gets it for free.
4. If your local PostgreSQL password isn't `postgres`, set a `DB_PASSWORD` environment
   variable on the run configuration rather than editing any file — see
   `E-voting_Server/src/main/resources/application-dev.properties` for why, and for a
   file-based alternative if you'd rather not use an environment variable.

**From the command line, without IntelliJ:**

```bash
cd E-voting_Server
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Either way, the `dev` profile disables TLS and fills in fixed development secrets, so
there is nothing else to configure. The server starts on `http://localhost:8443` and
seeds one test terminal (`PI-WARD-01`) that self-provisions automatically.

Confirm it's up by opening `http://localhost:8443/Login.html` in a browser.

### 3. Start the machine client

**With IntelliJ:**

Run the shared **"Machine"** configuration the same way as the server, or create one:
**Run > Edit Configurations > + > Application**, main class `machine.VotingMachineApp`,
working directory set to `E-voting_Machine/src/main/resources` (so it can find the
packaged `config.properties`).

**From the command line:**

```bash
cd E-voting_Machine
mvn package
cd target
java -jar evoting-machine.jar
```

The packaged `config.properties` already points at the local dev server and the seeded
`PI-WARD-01` terminal, so nothing needs editing for this first run.

### 4. Register a voter and a candidate, then try voting

1. In the admin dashboard (`Login.html`, default `admin` / `admin123` in dev), open
   **Voters** and register one, then open **Candidates** and register at least one for
   the same constituency.
2. Open the **Polling control** section and start an election.
3. Switch to the machine client window and step through the voter flow — see
   `E-voting_Machine/README.md` for exactly what each screen expects.

## Running it for real

Everything above skips TLS and every real secret, which is fine for trying the
project out but not for anything resembling an actual election. `E-voting_Server/README.md`
has the complete real setup: generating a TLS keystore, generating and storing every
required secret, and what each one protects. `E-voting_Machine/README.md` covers
provisioning a real terminal against that server, including exporting the server's
certificate into a terminal truststore.

## Keeping secrets out of the repository

Nothing secret should ever be typed directly into a tracked file. In particular:

- Database, keystore, and application secrets are read from environment variables
  everywhere in this project (see `application.properties`) — set them in your shell
  or your service manager, never in a file that gets committed.
- If you'd rather keep a local password in a file than in an environment variable, use
  an untracked `application-local.properties` (see the comment in
  `application-dev.properties`) — `.gitignore` specifically refuses to track that
  filename.
- TLS keystores (`*.p12`, `*.jks`, `*.pfx`) and certificate/key files (`*.key`, `*.crt`,
  `*.pem`) are also excluded by `.gitignore`. Generate one per deployment; never share
  or commit one.
- Before your first commit on a new checkout, run `git status` and check nothing with a
  real password, key, or secret in it is staged. `.gitignore` catches the common cases,
  not every possible one.

## Repository layout

```
E-Voting_System/
├── E-voting_Server/        Spring Boot backend — see its own README
├── E-voting_Machine/       Swing kiosk client — see its own README
├── AUDIT_NOTES.md          Findings from a full-codebase review pass
├── .data/                  Source data and scripts used to generate the electoral
│                           geography seed migration (states, districts, constituencies)
└── .gitignore
```

## Further reading

- `E-voting_Server/README.md` — server guarantees, full secret list, the admin
  dashboard, the machine API, and how to add a schema migration.
- `E-voting_Machine/README.md` — the voter flow, the queued-vote delivery guarantee,
  terminal configuration, and how requests are authenticated end to end.
- `AUDIT_NOTES.md` — a record of a prior full review of the codebase: what was found
  and fixed, and why.
