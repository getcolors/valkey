#!/usr/bin/env bash
# Server-side acceptance for the Valkey host, run during convergence.
#
# Exit codes are not evidence; each gate asks the system what it actually has.
# Usage: valkey-smoke <public-ip>
set -euo pipefail
public_ip="${1:?usage: valkey-smoke <public-ip>}"
port=6379
cd /opt/valkey
pw=$(cat /etc/valkey/secrets/password)
export VALKEYCLI_AUTH="$pw"
fail() { echo "valkey-smoke: $*" >&2; exit 1; }
r() { docker compose exec -T -e VALKEYCLI_AUTH valkey valkey-cli --no-auth-warning "$@" 2>&1 | tr -d '\r'; }
raw() { docker compose exec -T valkey valkey-cli --no-auth-warning "$@" 2>&1 | tr -d '\r'; }
cfg() { r CONFIG GET "$1" | tail -1; }
wait_pong() {
  local i; for i in $(seq 1 30); do [ "$(r PING)" = "PONG" ] && return 0; sleep 2; done
  fail "Valkey did not answer PING within 60s"
}

# --- S1 the round-trip -------------------------------------------------------
stamp=$(date -u +%Y%m%dT%H%M%SZ)
[ "$(r SET colors:smoke "$stamp")" = "OK" ] || fail "SET colors:smoke answered '$(r SET colors:smoke "$stamp")', expected OK"
[ "$(r GET colors:smoke)" = "$stamp" ] || fail "GET colors:smoke answered '$(r GET colors:smoke)', expected $stamp"

# --- S2 the configuration Valkey actually runs with ---------------------------
[ "$(cfg maxmemory-policy)" = "noeviction" ] || fail "maxmemory-policy is '$(cfg maxmemory-policy)', not noeviction"
[ "$(cfg appendonly)" = "yes" ] || fail "appendonly is '$(cfg appendonly)', not yes"
[ "$(cfg appendfsync)" = "everysec" ] || fail "appendfsync is '$(cfg appendfsync)', not everysec"
persistence=$(r INFO persistence)
grep -q '^aof_enabled:1' <<<"$persistence" || fail "aof_enabled is not 1"
server=$(r INFO server)
grep -qx 'server_name:valkey' <<<"$server" || fail "server_name is not valkey"
[ "$(sed -n 's/^valkey_version://p' <<<"$server")" = "9.1.2" ] || fail "valkey version differs from 9.1.2: $(sed -n 's/^valkey_version://p' <<<"$server")"

# --- S3 the negatives ----------------------------------------------------------
anon=$(raw PING)
grep -q PONG <<<"$anon" && fail "an unauthenticated PING was accepted"
grep -q NOAUTH <<<"$anon" || fail "an unauthenticated PING answered '$anon' instead of NOAUTH"
wrong=$(VALKEYCLI_AUTH=not-the-password docker compose exec -T -e VALKEYCLI_AUTH valkey valkey-cli --no-auth-warning PING 2>&1 | tr -d '\r')
grep -qE 'WRONGPASS|NOAUTH' <<<"$wrong" || fail "a wrong password answered '$wrong' instead of a refusal"
grep -q PONG <<<"$wrong" && fail "a wrong password was accepted"

# --- S4 the bind addresses -------------------------------------------------------
# Only loopback may listen on the port: never the public address, never a
# private one, never a wildcard. Docker publishes exactly what the Compose
# file says, and this is where that claim is checked against the kernel.
listeners=$(ss -ltnH "sport = :$port" | awk '{print $4}' | sort -u)
expected="127.0.0.1:$port"
[ "$listeners" = "$expected" ] || fail "port $port listeners are [$(tr '\n' ' ' <<<"$listeners")], expected [$(tr '\n' ' ' <<<"$expected")]"
if timeout 3 bash -c "exec 3<>/dev/tcp/$public_ip/$port" 2>/dev/null; then
  fail "the public address $public_ip answers on port $port"
fi

# --- S5 persistence across a restart --------------------------------------------
# The key written above must survive a graceful restart: that is the
# append-only file doing its job, proven rather than configured.
docker compose restart -t 30 valkey >/dev/null 2>&1
wait_pong
[ "$(r GET colors:smoke)" = "$stamp" ] || fail "colors:smoke did not survive a restart (got '$(r GET colors:smoke)')"
persistence=$(r INFO persistence)
grep -q '^aof_last_write_status:ok' <<<"$persistence" || fail "aof_last_write_status is not ok after the restart"

echo "valkey-smoke: round-trip, configuration, auth negatives, bind addresses and restart persistence all hold"
