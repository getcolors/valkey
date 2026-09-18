#!/usr/bin/env bash
# Root-only status helper: the last monitor result, the backup sets, the
# recovery marker, the container, and how to connect. Prints where the
# generated password lives, never the password itself.
set -uo pipefail
# Everything below reads root-only files and the Docker socket. The alias
# logs in as root on Vultr; use passwordless sudo if invoked by another
# authorized local account.
[ "$(id -u)" -eq 0 ] || exec sudo -n "$0" "$@"
. /opt/colors/r2-env.sh
PREFIX="backup:$BACKUP_BUCKET/$SET_PREFIX"
echo "== monitor =="; cat /var/lib/colors/valkey-monitor.json 2>/dev/null || echo "(no monitor result yet)"; echo
echo "== backup sets in $BACKUP_BUCKET/$SET_PREFIX (completed, newest last) =="
completed_sets "$PREFIX" | tail -5
echo
echo "== recovery marker =="
v=$(r2_cat "backup:$BACKUP_BUCKET/$SET_PREFIX/.colors-recovery-verified" || true)
printf '%-28s %s\n' ".colors-recovery-verified" "${v:-absent}"
echo
echo "== container =="
docker compose -f /opt/valkey/compose.yml ps --format '{{.Name}} {{.State}} {{.Status}}'
echo
echo "== connect =="
echo "from your workstation:"
echo "  ssh -L 6379:127.0.0.1:6379 valkey-optout-fixture"
echo "  VALKEYCLI_AUTH=\$(ssh valkey-optout-fixture sudo -n cat /etc/valkey/secrets/password) valkey-cli -p 6379"
echo "the generated password lives on this host only:"
echo "  /etc/valkey/secrets/password"
