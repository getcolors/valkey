# Valkey

A green Package Skill for one Valkey server on one machine. The deployment uses Docker Compose, host-generated authentication, an append-only file, and verified snapshot backups in an existing Cloudflare R2 bucket. The host publishes Valkey on loopback only; clients connect through an SSH tunnel.

The live-test target is Vultr with existing Cloudflare R2 buckets. Compute operations come from the pinned `colors-compute` library. Other providers have no live verification from this build. There are no red or blue implementations.

Valkey 9.1.2 passed two live converges, workstation acceptance, and a scratch recovery rehearsal on Vultr on 2026-09-18. The [Valkey Context Skill](https://github.com/getcolors/skills/tree/main/valkey-single-node) records the observed traps and verification limits. Detailed [deployment evidence](https://github.com/getcolors/valkey-vultr/blob/main/verification.md) requires access to the private deployment repository.

## Install and use

```sh
npx skills add getcolors/valkey --skill package-valkey-green
cp .agents/skills/package-valkey-green/green ./green
chmod +x green
./green build
./green create --dry-run
./green create
./green describe
./green rehearse
```

Copy the launcher again after every skill update. Deployment configuration belongs in `colors.yml`; credentials belong in a Git-ignored `.envrc.private`. Never export `COLORS_PAR_PROFILE`. Read [configuration](skills/package-valkey-green/references/configuration.md) before deploying.

Every converge checks server identity and version, authentication, a write/read round-trip, loopback listeners, and persistence across a graceful restart. It creates a backup set and runs the monitor. Workstation acceptance checks the SSH tunnel and confirms that the public database port does not answer. A failed gate fails the converge.

`rehearse` takes a fresh backup and restores a completed set into a network-isolated scratch container of the pinned image. It requires the deployment's smoke key before writing a recovery marker. This does not test restoration onto a replacement production host. A graceful restart does not establish a crash-loss bound.

## Destruction

`compute-prevent-destroy: true` protects the machine. An explicitly authorized cleanup uses `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false ./green delete` for that run only. Do not change the committed flag. Existing state and backup buckets remain operator-owned; deleting compute does not delete them or the backup sets.

## Development

```sh
direnv allow
cd green
bb test
bb golden
bb syntax
./green build
./green create --dry-run
cd ..
./scripts/launcher.sh
```

`VALKEY_LIB_ROOT` selects this package's working tree while developing. The payload resolves immutable pushed commits in normal use. `bb pin` stamps the launcher after a clean pushed package commit. The package has Vultr keygen and opt-out fixtures; provider behavior and its full matrix belong to `colors-compute`.
