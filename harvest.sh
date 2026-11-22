#!/usr/bin/env bash
# =============================================================================
# TeamsReader token harvester
# -----------------------------------------------------------------------------
# Uses the LOGGED-IN luakit session on the Pi (the Pi is now a trusted device)
# to silently mint a fresh Teams refresh_token, and writes it to
# teams_refresh_token.dat for the bot to use.
#
# How it works:
#   1. Generate a PKCE verifier/challenge (we control both).
#   2. Launch luakit (headless, via xvfb) pointed STRAIGHT at the authorize URL
#      with prompt=none + our code_challenge. The logged-in session makes the
#      silent auth succeed and redirect to teams.live.com/v2/authv2#code=...
#   3. luakit logs that redirect ("Requested link: ...authv2#code=...") - we read
#      the code from its output and kill luakit before the heavy SPA loads.
#   4. Exchange the code (+ our verifier) for tokens and save the refresh_token.
#
# Run it on its own schedule (e.g. every 12h via cron). The bot (every 2h) then
# refreshes that token as usual. Re-login in luakit is only needed if the MSA
# session itself expires (months), and uses the authenticator code (no CAPTCHA).
#
# IMPORTANT: close any interactive luakit window first - luakit is single-
# instance, so a second one would just open a tab in the existing window.
# =============================================================================
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

TENANT="9188040d-6c67-4c5b-b112-36a304b66dad"
CLIENT="4b3e8f46-56d3-427f-b1e2-d239b2ea6bca"
REDIRECT="https://teams.live.com/v2/authv2"
REDIRECT_ENC="https%3A%2F%2Fteams.live.com%2Fv2%2Fauthv2"
SCOPE="https://auth.fl.teams.microsoft.com/teams.auth.readwrite openid profile offline_access"
SCOPE_ENC="https%3A%2F%2Fauth.fl.teams.microsoft.com%2Fteams.auth.readwrite%20openid%20profile%20offline_access"
AUTHZ="https://login.microsoftonline.com/${TENANT}/oauth2/v2.0/authorize"
TOKEN="https://login.microsoftonline.com/${TENANT}/oauth2/v2.0/token"

# curl-impersonate (via the shim if present, same as the bot)
CURL="$HERE/curl-imp-shim.sh"
[ -x "$CURL" ] || CURL="curl"

# account email -> login_hint (helps prompt=none resolve the account silently)
HINT=""
[ -f "$HERE/teams_login_hint.dat" ] && HINT="$(tr -d '\r\n' < "$HERE/teams_login_hint.dat")"
HINT_ENC="$(printf '%s' "$HINT" | sed 's/@/%40/g')"

urldecode() { local s="$1"; printf '%b' "${s//%/\\x}"; }

# ---- PKCE ----
VERIFIER="$(openssl rand -base64 48 | tr '+/' '-_' | tr -d '=\n' | cut -c1-64)"
CHALLENGE="$(printf '%s' "$VERIFIER" | openssl dgst -binary -sha256 | openssl base64 | tr '+/' '-_' | tr -d '=\n')"
NONCE="$(openssl rand -hex 16)"
STATE="harvest-$(date +%s)"

AUTH_URL="${AUTHZ}?client_id=${CLIENT}&scope=${SCOPE_ENC}&redirect_uri=${REDIRECT_ENC}&response_type=code&response_mode=fragment&prompt=none&client_info=1&code_challenge=${CHALLENGE}&code_challenge_method=S256&nonce=${NONCE}&state=${STATE}&x-client-SKU=msal.js.browser&x-client-VER=5.6.3"
[ -n "$HINT_ENC" ] && AUTH_URL="${AUTH_URL}&login_hint=${HINT_ENC}"

echo "[harvest] launching luakit (headless) to mint a token..."
OUT="$(mktemp)"
xvfb-run -a luakit "$AUTH_URL" >"$OUT" 2>&1 &
LKPID=$!

CODE=""; ERR=""
for i in $(seq 1 45); do
  LINE="$(grep -ao 'authv2#[^ ]*' "$OUT" 2>/dev/null | head -1)"
  if [ -n "$LINE" ]; then
    CODE="$(printf '%s' "$LINE" | grep -oP 'code=\K[^&]+' | head -1)"
    ERR="$(printf '%s'  "$LINE" | grep -oP 'error=\K[^&]+' | head -1)"
    break
  fi
  # bail early if luakit died
  kill -0 "$LKPID" 2>/dev/null || break
  sleep 1
done

# stop this luakit + its Xvfb (safe: the harvester runs alone)
kill "$LKPID" 2>/dev/null
pkill -f "luakit"  2>/dev/null
pkill -f "Xvfb"    2>/dev/null
CAP="$(cat "$OUT" 2>/dev/null)"
rm -f "$OUT"

if [ -n "$ERR" ]; then
  echo "[harvest] silent auth returned error: $ERR"
  echo "[harvest] the luakit MSA session has likely expired - open 'luakit https://login.live.com' and sign in again (authenticator code)."
  exit 2
fi
if [ -z "$CODE" ]; then
  echo "[harvest] no auth code captured. Check: is luakit logged in? any other luakit running? Last luakit output:"
  printf '%s\n' "$CAP" | tail -5
  exit 3
fi

CODE="$(urldecode "$CODE")"
echo "[harvest] got auth code; exchanging for tokens..."

RESP="$("$CURL" -s --compressed -X POST --impersonate chrome116 \
  -H "Origin: https://teams.live.com" \
  --data-urlencode "client_id=${CLIENT}" \
  --data-urlencode "scope=${SCOPE}" \
  --data-urlencode "grant_type=authorization_code" \
  --data-urlencode "code=${CODE}" \
  --data-urlencode "redirect_uri=${REDIRECT}" \
  --data-urlencode "code_verifier=${VERIFIER}" \
  --data-urlencode "client_info=1" \
  "$TOKEN")"

RT="$(printf '%s' "$RESP" | grep -oP '"refresh_token"\s*:\s*"\K[^"]+' | head -1)"
if [ -z "$RT" ]; then
  echo "[harvest] token exchange FAILED. Response (first 600 chars):"
  printf '%s\n' "$RESP" | head -c 600; echo
  exit 4
fi

cp -f "$HERE/teams_refresh_token.dat" "$HERE/teams_refresh_token.dat.bak" 2>/dev/null
printf '%s' "$RT" > "$HERE/teams_refresh_token.dat"
echo "[harvest] SUCCESS - fresh Teams refresh_token saved to teams_refresh_token.dat"
