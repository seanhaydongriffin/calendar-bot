#!/bin/bash
# Adapter for the Pi's curl-impersonate v0.6.0 (original project, armhf).
#
# The app invokes `cfg.curl --impersonate <profile> ...` (lexiforest-fork style,
# as on Windows). But v0.6.0's `curl-impersonate-chrome` has NO --impersonate flag
# - it exits with "option --impersonate: is unknown". v0.6.0 instead ships wrapper
# scripts (curl_chrome116, ...) that bake the fingerprint in via many -H/--ciphers
# /--curves flags.
#
# This shim bridges the two: it strips `--impersonate <profile>` and routes the
# call through the chrome116 wrapper. Teams calls get the chrome116 JA3 (accepted
# by login.microsoftonline.com / teams.live.com as of 18 Nov 2026); Gemini and
# Calendar calls carry no --impersonate and simply pass through.
#
# Point run.sh at this file:  export TEAMSREADER_CURL=.../curl-imp-shim.sh
# (run.sh picks it up automatically when it sits beside run.sh).
WRAP="$HOME/curl-impersonate-v0.6.0.arm-linux-gnueabihf/curl_chrome116"

args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --impersonate) shift 2 ;;        # drop the flag AND its profile value
    *) args+=("$1"); shift ;;
  esac
done

exec "$WRAP" "${args[@]}"
