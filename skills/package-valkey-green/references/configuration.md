# Configuration

`colors.yml` holds non-secret desired state. The profile identifies remote state, the SSH keypair and alias, and the backup prefix. Never override it through the environment.

| Key | Meaning |
|---|---|
| `profile` | Unique deployment identity, for example `valkey-vultr`. |
| `workdir` | Generated output directory, normally `.colors`. |
| `provider-compute` | Compute provider selected by the pinned library. The live target is `vultr`. |
| `provider-backend` | Remote compute state backend; the live target is `r2`. |
| `compute-prevent-destroy` | Keep `true`; authorized deletion uses a one-run environment override. |
| `valkey-image` | Immutable `tag@sha256:...` image. |
| `valkey-version` | Exact expected version reported by `INFO server`, for example `9.1.2`. |
| `valkey-port` | Host loopback port; the container uses 6379. |
| `valkey-backup-r2-bucket` | Existing backup bucket. Sets live under `<profile>/valkey/<stamp>/`. |
| `valkey-backup-r2-endpoint` | Cloudflare R2 HTTPS endpoint. |
| `valkey-backup-r2-region` | `auto` for R2. |
| `valkey-backup-oncalendar` | Backup systemd schedule, for example `*-*-* 00/6:00:00`. |
| `valkey-backup-retention-days` | Prune old completed sets only while a newer completed set exists. |
| `valkey-backup-max-age-hours` | Monitor fails when the newest completed set is older than this limit. |
| `r2-bucket`, `r2-endpoint` | Existing remote compute state bucket and endpoint. |

The Vultr example uses `vultr-region: ams`, `vultr-plan: vc2-1c-2gb`, `vultr-os-id: 2284`, and `vultr-ssh-sources` restricted to the operator's CIDR. Omitting `vultr-ssh-keys` selects the managed profile keypair. Supplying a valid existing account key reference selects opt-out mode. Refer to the [pinned library](https://github.com/getcolors/colors-compute/tree/ae28ea74962bb1897fa6365c143c1d43ac1fe095) for its provider settings and credentials; this package does not duplicate its provider registry.

## Credentials

Set these locally in `.envrc.private` for the Vultr/R2 deployment:

| Variable | Purpose |
|---|---|
| `COLORS_PAR_VULTR_API_KEY` | Compute API access. |
| `COLORS_PAR_R2_ACCESS_KEY_ID`, `COLORS_PAR_R2_SECRET_ACCESS_KEY` | Remote compute state. |
| `COLORS_PAR_VALKEY_BACKUP_R2_ACCESS_KEY_ID`, `COLORS_PAR_VALKEY_BACKUP_R2_SECRET_ACCESS_KEY` | Object read/write for the backup bucket. |

The Valkey password is generated once on the host. It is never an operator-supplied desired-state value. The service uses `noeviction`, `appendonly yes`, and `appendfsync everysec`. Application backups and compute state use distinct profile-owned object paths even when sharing buckets with other deployments.

## Backup evidence

A completed set contains `dump.rdb`, a manifest, and a nonempty `.complete` marker written after uploaded content is read back and verified. Rehearsal checks the manifest and snapshot, restores with the pinned image into an isolated scratch container, and verifies the smoke key before publishing the recovery marker. Shared buckets remain external throughout create and delete. Incomplete sets remain for manual inspection. Pruning stops if listing or marker reads fail, and reads every candidate marker before deleting any old completed set.
