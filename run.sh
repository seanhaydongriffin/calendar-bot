#!/usr/bin/env bash
# Run one extract->push cycle. Put the jar (teamsreader-java.jar, the fat jar from
# VS Code "Export Jar..." - deps bundled, no lib/ needed) and all the
# .dat / .ini / ledger state files together in one directory (e.g. ~/teamsreader)
# and run this from there.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

# Only ever run one cycle at a time. The web "Run Bot" button and cron can both
# launch this script; two overlapping runs would clobber the rotating Teams
# refresh token (AADSTS70000). Take a non-blocking lock on FD 9; if another run
# already holds it, exit cleanly (0) instead of waiting.
exec 9>"$HERE/.teamsreader.lock"
if ! flock -n 9; then
  echo "Another TeamsReader run is already in progress - skipping this one."
  exit 0
fi

# Where the secret/state files live (defaults to this folder).
export TEAMSREADER_HOME="${TEAMSREADER_HOME:-$HERE}"

# curl binary. The v0.6.0 armhf curl-impersonate has NO --impersonate flag (it
# uses wrapper scripts), but the app passes `--impersonate <profile>` for Teams.
# So prefer curl-imp-shim.sh (ships beside this script): it strips that flag and
# routes Teams through the curl_chrome116 wrapper, while Gemini/Calendar pass
# straight through. If the shim isn't present, fall back to the bare chrome binary
# (email + Gemini + Calendar still work; Teams won't).
if [ -z "${TEAMSREADER_CURL:-}" ]; then
  if [ -x "$HERE/curl-imp-shim.sh" ]; then
    TEAMSREADER_CURL="$HERE/curl-imp-shim.sh"
  else
    TEAMSREADER_CURL="$HOME/curl-impersonate-v0.6.0.arm-linux-gnueabihf/curl-impersonate-chrome"
  fi
fi
export TEAMSREADER_CURL

# TLS/JA3 profile. The Pi's v0.6.0 build does NOT have chrome136 - use a profile
# it actually ships (chrome116 / chrome120). Only matters once the Teams source
# is restored; the email+Gemini+Calendar path uses no impersonation.
export TEAMSREADER_IMPERSONATE="${TEAMSREADER_IMPERSONATE:-chrome116}"

# Prefer the VS Code "Export Jar..." output; fall back to the Maven fat jar name.
JAR="$HERE/teamsreader-java.jar"
[ -f "$JAR" ] || JAR="$HERE/teamsreader.jar"

# Keep memory modest on the 512MB Pi Zero 2 W.
# Pass through any CLI args (e.g. --list-chats, the conversation-discovery mode).
# Mirror this run's output to lastrun.log (overwrite) so the Pi dashboard's
# Calendar page shows the latest run - manual, cron and button runs alike.
java -Xmx192m -jar "$JAR" "$@" 2>&1 | tee "$HERE/lastrun.log"