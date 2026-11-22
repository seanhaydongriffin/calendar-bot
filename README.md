# TeamsReader (Java port for the Raspberry Pi)

A Java rewrite of `TeamsReader.au3`, for the Raspberry Pi Zero 2 W (Java 17).
Same one-shot flow:

```
refresh Teams tokens  ->  collect Teams messages + screenshots + IMAP emails
   ->  ONE multimodal Gemini call  ->  parse consolidated events (dedupe_key)
   ->  upsert into Google Calendar (idempotent)  ->  update ledger + IMAP state
```

All HTTP that needs TLS/JA3 impersonation (the **Teams** calls) still shells out
to **curl-impersonate** — no Java HTTP client is used for those. Gemini and
Google Calendar use plain curl (no impersonation, same as the AutoIt version).
IMAP email is read with **JavaMail** (Sean's choice), not curl `imaps://`.

## What changed from the AutoIt version
- **Dependencies:** `org.json` (tiny JSON parser) + `javax.mail` (JavaMail). Two jars.
- **IMAP:** JavaMail instead of curl `imaps://`. Folder opened READ_ONLY = the old
  `BODY.PEEK` (never sets \Seen). UIDVALIDITY + high-water `lastuid` logic unchanged.
- **Time:** `java.time` with a fixed zone (`Australia/Brisbane`) instead of
  hand-rolled civil-date math — deterministic regardless of the Pi's system TZ.
- **Teams is best-effort:** a stale refresh token no longer aborts the run; it
  continues **email-only** (the confirmed-working path).
- Base64/JWT/quoted-printable helpers collapse into standard library calls.

State/ledger/calendar-id formats are **unchanged**, so your existing
`teams_state.ini`, `teams_events_ledger.txt`, `*.dat` files keep working, and
calendar events stay deduped across the AutoIt and Java versions.

## Running on Windows in VS Code (dev / testing)
Develop and test on Windows, then ship the same jar to the Pi — **no recompile**,
the one jar runs both places.

1. Install the **Extension Pack for Java** (Microsoft) in VS Code.
2. Install a **JDK 17** — use **Eclipse Temurin 17** to match the Pi. *(This PC
   currently has no JDK.)* `winget install EclipseAdoptium.Temurin.17.JDK`, or grab
   it from <https://adoptium.net>.
3. Open the `teamsreader-java` folder in VS Code.
4. Press **F5** → runs the `TeamsReader (Windows)` config in `.vscode/launch.json`.
   That config sets `cwd` and the env vars to
   `C:\dwn\curl-impersonate-2.3.2` (where the `.dat` config files and
   `curl-impersonate.exe` live) and uses the `chrome136` profile, so there's no
   manual env fiddling.

**To build a shippable jar without Maven:** Command Palette →
**"Java: Export Jar..."** → main class **`teamsreader.Main`**, include the
dependencies (json, javax.mail, **and activation** — JavaMail needs it on Java 17).
That writes `teamsreader-java.jar`. Test it with `run.bat`, then ship it to the Pi.
Alternatively `mvn package` → `target\teamsreader.jar` (the Maven fat jar); the run
scripts accept either name.

**Deploy to the Pi:** copy the single `teamsreader-java.jar` + `run.sh` + the
`.dat`/`.ini`/ledger files across (SFTP) into one folder (e.g. `~/teamsreader/`),
then `chmod +x run.sh && ./run.sh`. Same jar, no rebuild. **No `lib/`, no source.**

## Build (on the Pi, no Maven needed)
```
./build.sh        # downloads 2 jars into lib/, compiles, makes teamsreader.jar
```
Or, if you have Maven: `mvn -q package` → `target/teamsreader.jar` (fat jar).

## Run
Put `teamsreader-java.jar`, `run.sh`, `curl-imp-shim.sh`, and all the state/secret
files in one directory (e.g. `~/teamsreader`), then:
```
chmod +x run.sh curl-imp-shim.sh
./run.sh
```
No `lib/` folder — the Export-Jar / shade fat jar bundles the dependencies.

### Environment variables (set in run.sh)
| var | meaning | default |
|-----|---------|---------|
| `TEAMSREADER_HOME` | folder holding the .dat/.ini/ledger files | the script's folder |
| `TEAMSREADER_CURL` | path to the curl binary / shim | `curl-imp-shim.sh` if present, else `~/curl-impersonate-v0.6.0.arm-linux-gnueabihf/curl-impersonate-chrome` |
| `TEAMSREADER_IMPERSONATE` | TLS/JA3 profile (ignored when the shim is used) | `chrome116` |

### ⚠ curl-impersonate: the Pi needs the shim
The Windows build (`curl-impersonate-2.3.2`, lexiforest fork) takes
`--impersonate chrome136`. The Pi's **v0.6.0** build (original project) has **no
`--impersonate` flag at all** — `curl-impersonate-chrome --impersonate chrome116`
fails with *"option --impersonate: is unknown"*. v0.6.0 instead ships **wrapper
scripts** (`curl_chrome116`, …) that bake the fingerprint in.

`curl-imp-shim.sh` bridges this: it strips `--impersonate <profile>` and routes
the call through `curl_chrome116`. **`run.sh` uses the shim automatically** when it
sits in the same folder. Verified end-to-end on the Pi 18 Nov 2026 — Microsoft
accepted the chrome116 fingerprint (token refresh + skypetoken + Teams messages all
succeeded). chrome116 is the newest profile this v0.6.0 build ships; if Microsoft
ever rejects it, install a newer armhf curl-impersonate (or a newer wrapper) and
point the shim's `WRAP=` at it.

## Secret / state files (copy these from Windows via SFTP)
| file | purpose | notes |
|------|---------|-------|
| `teams_refresh_token.dat` | Teams refresh token | **ROTATES every run**; saved immediately, previous kept as `.bak`. Working as of 18 Nov 2026. **Run on ONE machine only** — a refresh on the Pi invalidates the Windows copy and vice-versa. If it ever expires (`AADSTS70000`), re-seed from teams.live.com's MSAL localStorage. |
| `teams_gemini_key.dat` | Gemini API key | required |
| `teams_internode_imap.dat` | IMAP app password | host `imap.themessagingco.com.au:993` |
| `google_client.dat` | `client_id\|client_secret` | Google OAuth desktop client |
| `google_refresh_token.dat` | Google refresh token | see below |
| `teams_state.ini` | IMAP uidvalidity + lastuid high-water | created/updated automatically |
| `teams_events_ledger.txt` | dedupe memory fed back to Gemini | created/updated automatically |

### Google consent on a headless Pi
This port does **not** run the interactive loopback OAuth flow (no browser on the
Pi). `google_refresh_token.dat` already exists from the Windows setup — just copy
it across. If Google ever revokes it, re-run the **AutoIt** version once on
Windows (or any desktop) to re-consent, then copy the new
`google_refresh_token.dat` to the Pi.

## Optional: run on a schedule
Sean chose plain `java -jar` (via `run.sh`). To run it periodically, a cron entry
is simplest — e.g. every 2 hours (matches the costed Gemini usage):
```
0 */2 * * * /home/seangriffin/teamsreader/run.sh >> /home/seangriffin/teamsreader/cron.log 2>&1
```
Or every 20 minutes: `*/20 * * * * /home/seangriffin/teamsreader/run.sh >> …`.
Edit with `crontab -e`. Cron runs with a minimal environment, but `run.sh` sets
everything it needs (HOME-relative paths), so no extra env setup is required.

## Status
**Working end-to-end on the Raspberry Pi (18 Nov 2026).** Built on Windows via VS
Code "Java: Export Jar..." → `teamsreader-java.jar`, shipped to the Pi, and a full
cycle ran clean there: Teams token refresh + skypetoken (via `curl-imp-shim.sh` →
chrome116), 60 Teams messages + 6 screenshots collected, Gemini extracted 4 events,
Google Calendar upserted (0 created / 4 updated — idempotent). JavaMail IMAP, the
curl-impersonate wrapper, Gemini, and Calendar all verified on the Pi.

**Remaining:** add a cron entry (above); stop running the Windows/AutoIt version so
the rotating Teams token isn't invalidated.
