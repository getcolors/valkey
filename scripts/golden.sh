#!/usr/bin/env bash
set -euo pipefail

# Two package integration fixtures; provider coverage belongs to colors-compute.
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

accept=0
[[ ${1:-} == --accept ]] && accept=1

status=0
for variant in colors optout; do
  fixture="$tmp/$variant.yml"
  sed "s#WORKDIR#$tmp/work#" "$root/test/fixtures/$variant.yml" > "$fixture"
  (cd "$root/green" && VALKEY_LIB_ROOT="$root" ./green build -f "$fixture" >/dev/null)

  profile=$(sed -n 's/^profile: //p' "$fixture")
  provider=$(sed -n 's/^provider-compute: //p' "$fixture")
  backend=$(sed -n 's/^provider-backend: //p' "$fixture")
  actual="$tmp/work/$profile"
  golden="$root/test/resources/golden/$backend/$profile"

  # No rendered artefact may carry a real secret into a committed golden.
  if grep -rEq 'BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY|github_pat_|ghp_|gho_|ghu_|ghs_|ghr_|AKIA[0-9A-Z]{16}' "$actual"; then
    echo "golden: a credential-shaped value was rendered in $profile" >&2; exit 1
  fi
  # Credentials resolve only at Ansible execution time.
  for par in VALKEY_BACKUP_R2_ACCESS_KEY_ID VALKEY_BACKUP_R2_SECRET_ACCESS_KEY; do
    grep -q "lookup('env','COLORS_PAR_$par')" "$actual/valkey-ansible/main.yml" \
      || { echo "golden: $profile no longer renders COLORS_PAR_$par as a lookup" >&2; exit 1; }
  done
  # Loopback is the only host binding; a second one is a second address to
  # reason about, and the VPC one is gone by design.
  if [[ $(grep -c ':<{ valkey-port }>:6379\|:6379:6379' "$actual/valkey-ansible/compose.yml") != 1 ]] \
     || ! grep -q '"127.0.0.1:6379:6379"' "$actual/valkey-ansible/compose.yml"; then
    echo "golden: $profile compose.yml must publish the port on 127.0.0.1 and nowhere else" >&2; exit 1
  fi
  # The password never enters a rendered file: it is generated on the host.
  if grep -rEq 'requirepass [0-9a-f]{16}' "$actual"; then
    echo "golden: $profile rendered a Valkey password" >&2; exit 1
  fi
  # Every rendered script must at least parse.
  for sh in "$actual"/valkey-ansible/*.sh; do
    bash -n "$sh" || { echo "golden: $sh does not parse" >&2; exit 1; }
  done
  # R2 uses Cloudflare and skips unsupported bucket/head probes.
  if ! grep -q 'RCLONE_CONFIG_BACKUP_PROVIDER=Cloudflare' "$actual/valkey-ansible/r2-env.sh" \
     || ! grep -q 'RCLONE_CONFIG_BACKUP_NO_CHECK_BUCKET=true RCLONE_CONFIG_BACKUP_NO_HEAD=true' "$actual/valkey-ansible/r2-env.sh"; then
    echo "golden: $profile r2-env.sh lost the Cloudflare provider or the rclone skips" >&2; exit 1
  fi
  [[ ! -e "$actual/valkey-storage" ]] || { echo "unexpected managed storage" >&2; exit 1; }

  # A build that reached the real ~/.ssh would leak the operator's home into
  # committed bytes and make the goldens workstation-specific.
  if grep -rq "$HOME/.ssh" "$actual"; then
    echo "golden: $profile rendered a real home directory; build must use the placeholder" >&2; exit 1
  fi
  # SSH Config Standard §6: the local stage takes the address, the user and the
  # alias as Ansible extra-vars, never through Selmer, so its rendered playbook
  # carries no address at all.
  if grep -rEq '([0-9]{1,3}\.){3}[0-9]{1,3}' "$actual/valkey-ansible-local"; then
    echo "golden: $profile rendered an address into the local ssh_config stage" >&2; exit 1
  fi

  if [[ $accept == 1 ]]; then
    rm -rf "$golden"; mkdir -p "$(dirname "$golden")"; cp -a "$actual" "$golden"; continue
  fi
  [[ -d "$golden" ]] || { echo "golden missing for $profile; inspect build then run bb golden:accept" >&2; exit 1; }
  diff -ru "$golden" "$actual" || status=1
done

[[ $status == 0 ]] && echo 'all Valkey goldens and safety assertions pass'
exit "$status"
