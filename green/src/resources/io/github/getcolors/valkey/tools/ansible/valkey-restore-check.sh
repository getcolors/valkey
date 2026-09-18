#!/usr/bin/env bash
# Restore a completed backup set into a SCRATCH instance of the pinned image
# and read the smoke key back from it. A checksum proves the set is intact;
# the boot proves it is a recovery. The live service is never touched.
#
# The scratch instance runs with the append-only file OFF. The Redis
# reference warns that AOF can take precedence over the restored RDB;
# this configuration explicitly selects the RDB restoration path. It publishes no port and carries no password -- it is reached
# only through docker exec and removed on exit, whatever happened.
#
# Usage: valkey-restore-check [<stamp>]   (default: the newest completed set)
set -euo pipefail
exec 9>/run/lock/valkey-restore.lock
flock -w 120 9 || { echo "valkey-restore-check: another restore is running" >&2; exit 1; }
cd /opt/valkey
. /opt/colors/r2-env.sh
PREFIX="backup:$BACKUP_BUCKET/$SET_PREFIX"
SET="${1:-$(newest_completed_set "$PREFIX")}"
[[ "$SET" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || { echo "valkey-restore-check: invalid or absent backup stamp" >&2; exit 1; }
[ -n "$SET" ] || { echo "valkey-restore-check: no completed backup set under $SET_PREFIX/" >&2; exit 1; }
[ -n "$(set_complete "$PREFIX" "$SET")" ] || { echo "valkey-restore-check: set $SET is not complete" >&2; exit 1; }
NAME=valkey-restore-check
WORK=$(mktemp -d /var/tmp/valkey-restore.XXXXXX)
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

rclone copyto "$PREFIX/$SET/dump.rdb" "$WORK/dump.rdb"
rclone copyto "$PREFIX/$SET/manifest.txt" "$WORK/manifest.txt"
# Treat object-store content as data, never executable shell. Validate the
# deployment, immutable image, stamp, byte count and checksum before booting.
python3 - "$WORK/manifest.txt" "$PROFILE" "$VALKEY_IMAGE" "$SET" "$WORK/dump.rdb" <<'PYMANIFEST'
import hashlib, pathlib, re, sys
manifest, profile, image, stamp, dump = sys.argv[1:]
rows = pathlib.Path(manifest).read_text().splitlines()
values = {}
for row in rows:
    key, separator, value = row.partition('=')
    if not separator or key in values:
        raise SystemExit('valkey-restore-check: malformed manifest')
    values[key] = value
expected = {'stamp', 'profile', 'image', 'valkey_version', 'dbsize', 'dump_sha256', 'dump_bytes'}
if set(values) != expected or any(values[k] != v for k, v in
        [('profile', profile), ('image', image), ('stamp', stamp)]):
    raise SystemExit('valkey-restore-check: manifest identity mismatch')
if not re.fullmatch(r'[0-9]+', values['dbsize']) or not re.fullmatch(r'[0-9]+', values['dump_bytes']):
    raise SystemExit('valkey-restore-check: malformed manifest counts')
payload = pathlib.Path(dump).read_bytes()
if len(payload) != int(values['dump_bytes']) or hashlib.sha256(payload).hexdigest() != values['dump_sha256']:
    raise SystemExit('valkey-restore-check: dump size or checksum mismatch')
PYMANIFEST
image="$VALKEY_IMAGE"
dbsize=$(sed -n 's/^dbsize=//p' "$WORK/manifest.txt")
dump_sha256=$(sed -n 's/^dump_sha256=//p' "$WORK/manifest.txt")
[ "$(sha256sum "$WORK/dump.rdb" | cut -d' ' -f1)" = "$dump_sha256" ] \
  || { echo "valkey-restore-check: dump checksum mismatch for set $SET" >&2; exit 1; }
# Readable by the image's valkey user (uid 999); the entrypoint chowns what it
# finds in /data, but the directory itself must be traversable first.
chmod 0755 "$WORK"; chmod 0644 "$WORK/dump.rdb"

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --network none --name "$NAME" -v "$WORK":/data "$image" \
  valkey-server --appendonly no --save "" --dir /data --dbfilename dump.rdb >/dev/null
s() { docker exec "$NAME" valkey-cli --no-auth-warning "$@" 2>/dev/null | tr -d '\r'; }
for _ in $(seq 1 30); do
  [ "$(s PING)" = "PONG" ] && [ "$(s INFO persistence | sed -n 's/^loading://p')" = "0" ] && break
  sleep 1
done
[ "$(s PING)" = "PONG" ] || { echo "valkey-restore-check: the scratch instance never answered PING" >&2; docker logs "$NAME" 2>&1 | tail -5 >&2; exit 1; }
[ "$(s INFO persistence | sed -n 's/^loading://p')" = "0" ] || { echo "valkey-restore-check: scratch is still loading" >&2; exit 1; }
keys=$(s DBSIZE)
[ "${keys:-0}" -ge 1 ] || { echo "valkey-restore-check: the restored set holds no keys" >&2; exit 1; }
smoke=$(s GET colors:smoke)
[ -n "$smoke" ] || { echo "valkey-restore-check: colors:smoke is absent from the restored data; this is not this deployment's data" >&2; exit 1; }
[ "$keys" = "$dbsize" ] || echo "valkey-restore-check: WARN restored $keys keys, manifest recorded $dbsize (writes between DBSIZE and the snapshot)"
echo "valkey-restore-check: restored $SET into a scratch $image ($keys keys, colors:smoke=$smoke)"
echo "set=$SET"
