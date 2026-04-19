# E-Voting System — Complete Setup Guide
## Two Laptops Over Internet (Your laptop = Server, Friend's laptop = Machine)

---

## WHAT YOU'RE BUILDING

```
YOUR LAPTOP (Server)                    FRIEND'S LAPTOP (Machine)
─────────────────────                   ──────────────────────────
Spring Boot Server                      Java Swing Voting Machine App
PostgreSQL Database          ←────→     Simulates the Raspberry Pi
Port 8080                   ngrok       Calls 16 REST APIs
                            tunnel      Shows voter + candidates
```

---

## STEP 1 — SET UP SERVER (Your Laptop)

### 1.1 Add new files to your existing E-voting project

Copy these 3 files INTO your existing project:

```
E-voting/src/main/java/Backend/controller/MachineApiController.java  ← NEW
E-voting/src/main/java/Backend/config/SecurityConfig.java             ← REPLACE existing
E-voting/src/main/resources/application.properties                    ← REPLACE existing
```

### 1.2 Verify application.properties

Open `application.properties` and make sure your DB password is correct:
```
spring.datasource.password=Bala@sql    ← change if yours is different
```

The new keys added:
```
evoting.machine-secret=machine@evoting2025
evoting.admin-key=admin@evoting123
```

### 1.3 Start the server

In VS Code terminal (inside E-voting folder):
```bash
./mvnw spring-boot:run
```

OR if you have Maven installed:
```bash
mvn spring-boot:run
```

Wait for: `Started EvotingBackendApplication in X seconds`

### 1.4 Test the server is running

Open browser → http://localhost:8080/api/election/status

You should see JSON like:
```json
{"isActive": false, "message": "No election is currently active."}
```

---

## STEP 2 — ENABLE AN ELECTION IN DATABASE

The machine needs an active election. Open pgAdmin or DBeaver:

```sql
-- See what elections you have
SELECT id, name, type, is_active FROM elections;

-- Enable election with ID 1 (or whatever ID your election has)
UPDATE elections SET is_active = true WHERE id = 1;
-- Disable all others
UPDATE elections SET is_active = false WHERE id != 1;
```

Now test again: http://localhost:8080/api/election/status
Should show: `{"isActive": true, "electionName": "...", ...}`

---

## STEP 3 — SET UP NGROK (Internet Tunnel)

ngrok creates a public URL that forwards to your laptop's port 8080.
Your friend's laptop connects to this URL over the internet.

### 3.1 Install ngrok

Download from: https://ngrok.com/download
- Windows: download ngrok.exe, put it somewhere convenient
- OR: `choco install ngrok` (if you have Chocolatey)
- Free account required — sign up at ngrok.com

### 3.2 Authenticate ngrok (one time)

```bash
ngrok config add-authtoken YOUR_TOKEN_FROM_NGROK_DASHBOARD
```

(Your token is shown at: https://dashboard.ngrok.com/get-started/your-authtoken)

### 3.3 Start the tunnel

IMPORTANT: Your server must be running FIRST (Step 1.3), THEN run this:

```bash
ngrok http 8080
```

You'll see something like:
```
Forwarding   https://abc123.ngrok-free.app -> http://localhost:8080
```

COPY that URL (e.g. https://abc123.ngrok-free.app)

### 3.4 Send the URL to your friend

WhatsApp/message your friend the ngrok URL.
They need it for the next step.

IMPORTANT: ngrok URL changes every time you restart it (free tier).
Keep ngrok running throughout the entire testing session.

---

## STEP 4 — SET UP MACHINE CLIENT (Friend's Laptop)

### 4.1 Friend needs Java 17+

Check with: `java -version`
If not installed: download from https://adoptium.net (Eclipse Temurin 17)

### 4.2 Edit config.properties

Friend opens `evoting-machine-client/src/main/resources/config.properties`

Change this line:
```
SERVER_URL=http://localhost:8080
```
To the ngrok URL you sent them:
```
SERVER_URL=https://abc123.ngrok-free.app
```

### 4.3 Build the machine client

In VS Code terminal (inside evoting-machine-client folder):

```bash
mvn package
```

This creates: `target/evoting-machine.jar`

### 4.4 Run the machine client

```bash
java -jar target/evoting-machine.jar
```

The Swing GUI opens. It will show "Connecting to server..."
After a few seconds: "Machine registered."

---

## STEP 5 — TEST THE COMPLETE FLOW

### From your laptop (server side) — open election

Option A: Use the admin panel in the voting machine app:
- Click "Admin" button on idle screen
- Login: username = `Logic Makers`, password = `logic`
- Click "Open Election"
- Enter election ID: `1`

Option B: Use Postman:
```
POST http://localhost:8080/api/admin/login
Body: {"username": "Logic Makers", "password": "logic"}
→ Copy adminToken from response

POST http://localhost:8080/api/admin/election/open
Header: X-Admin-Key: admin@evoting123
Body: {"electionId": 1}
```

### From friend's laptop (machine side) — vote

1. Screen shows "SCAN VOTER CARD"
2. Type a voter ID from your database (e.g., check with `SELECT voter_id FROM voters LIMIT 5;`)
3. Click SCAN
4. Screen shows "FINGERPRINT VERIFICATION"
5. Click the fingerprint button 👆
6. Screen shows candidates with VOTE buttons
7. Click VOTE next to any candidate
8. Confirm → Vote recorded!

---

## STEP 6 — VERIFY VOTES IN DATABASE

On your laptop:
```sql
SELECT * FROM vote ORDER BY voted_at DESC LIMIT 10;
```

Also test the audit log API:
```
GET http://localhost:8080/api/audit/log
Header: X-Admin-Key: admin@evoting123
```

---

## ALL 16 APIs — QUICK REFERENCE

### Group 1: Machine Startup
| # | Method | URL | Description |
|---|--------|-----|-------------|
| 1 | GET | /api/election/status | Check if election is active |
| 2 | GET | /api/candidates | Get candidate list |
| 3 | POST | /api/machine/register | Register machine, get token |

### Group 2: Voter Auth
| # | Method | URL | Description |
|---|--------|-----|-------------|
| 4 | POST | /api/voter/verify-card | Verify RFID card |
| 5 | POST | /api/voter/verify-fingerprint | Verify fingerprint |
| 6 | GET | /api/voter/{id}/details | Get voter name + photo |
| 7 | GET | /api/voter/{id}/status | Check if already voted |

### Group 3: Voting Session
| # | Method | URL | Description |
|---|--------|-----|-------------|
| 8 | POST | /api/session/start | Start 2-min voting session |
| 9 | POST | /api/vote/cast | Cast vote (blockchain) |
| 10 | POST | /api/session/cancel | Cancel session |
| 11 | POST | /api/session/timeout | Timeout session |

### Group 4: Results & Audit (admin only)
| # | Method | URL | Description |
|---|--------|-----|-------------|
| 12 | GET | /api/results | Vote counts per candidate |
| 13 | GET | /api/audit/log | Blockchain audit trail |
| 14 | GET | /api/results/turnout | Voter turnout stats |

### Group 5: Admin Panel (admin only)
| # | Method | URL | Description |
|---|--------|-----|-------------|
| 15 | POST | /api/admin/login | Admin login |
| 16 | POST | /api/admin/election/open | Open election |
| - | POST | /api/admin/election/close | Close election |

### Headers
| Header | Used in | Value |
|--------|---------|-------|
| X-Machine-Token | Groups 2, 3 | token from /api/machine/register |
| X-Admin-Key | Groups 4, 5 | admin@evoting123 |

---

## TROUBLESHOOTING

### "Connection refused" on friend's laptop
→ Check ngrok is running on your laptop
→ Check firewall — Windows Firewall may block port 8080
→ Try: Windows Defender Firewall → Allow app → Add Java

### "No candidates found"
→ Check election is active: `SELECT * FROM elections WHERE is_active = true;`
→ Check candidates exist: `SELECT * FROM candidates WHERE election_id = 1;`

### "Voter not found"
→ Find a real voter ID: `SELECT voter_id, name FROM voters LIMIT 10;`

### Server crashes with "validate failed"
→ Your DB schema doesn't match. Change in application.properties:
```
spring.jpa.hibernate.ddl-auto=update
```

### ngrok "ERR_NGROK_3200"
→ Free tier limit reached. Wait a bit or use a paid plan.
→ Alternative: both laptops on same WiFi network — use local IP instead of ngrok.

---

## SAME WIFI ALTERNATIVE (No ngrok needed)

If both laptops are on the same WiFi:

1. On server laptop, find local IP:
   - Windows: `ipconfig` → look for IPv4 Address (e.g., 192.168.1.15)

2. Friend changes config.properties:
   ```
   SERVER_URL=http://192.168.1.15:8080
   ```

3. On server laptop, allow Windows Firewall for port 8080:
   ```
   netsh advfirewall firewall add rule name="E-Voting Server" protocol=TCP dir=in localport=8080 action=allow
   ```

This is more reliable than ngrok for demos!

---

## ADMIN CREDENTIALS

| Username | Password | Role |
|----------|----------|------|
| Logic Makers | logic | Super Admin (all access) |
| Bala | 922524104016 | PM Admin |
| Arun | 922524104011 | CM Admin |
| Deepak | 922524104026 | Municipality Admin |
| Dinesh | 922524104040 | Data Admin |

Machine secret: `machine@evoting2025`
Admin API key: `admin@evoting123`
