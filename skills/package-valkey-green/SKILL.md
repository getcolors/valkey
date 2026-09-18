---
name: package-valkey-green
description: Deploy and manage a single-node Valkey service from colors.yml using the green launcher, with SSH tunnel access, authentication, AOF persistence, verified snapshot backups in an existing Cloudflare R2 bucket, and a scratch restore rehearsal. Use for Valkey build, converge, inspect, backup, recovery rehearsal, or authorized teardown. This is not a clustered or highly available deployment.
---

# Valkey package skill

Read `references/configuration.md` for desired state and credential names. Use the bundled `green` launcher with Babashka and the deployment's devenv toolchain. Install the payload into the deployment and copy `green` to its root. Copy it again after an update; the installed payload and launcher must match.

```sh
./green build
./green create --dry-run
./green create
./green describe
./green rehearse
```

Build and dry-run require no credentials and must not touch SSH files or remote state. Real create provisions one machine, writes the profile's SSH alias, installs Valkey, and runs application and workstation acceptance gates. It restarts the service to verify persistence, so expect a brief interruption on every converge.

Keep secrets in the Git-ignored `.envrc.private`. Never export `COLORS_PAR_PROFILE`, edit generated `.colors/`, or put credentials in desired state or tracked files. Preserve `compute-prevent-destroy: true`; only an explicitly authorized delete may use the one-run `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` override. Operator-owned buckets and their backup sets survive compute deletion.

The service publishes only on host loopback. Connect through the generated SSH alias. Gates check Valkey identity and version, authentication replies, persistence, listener addresses, tunnel write/read, and public-port refusal. Failures must be resolved before claiming verification.

`rehearse` restores a completed, checksum-verified snapshot into a scratch container of the same pinned image with AOF disabled. It verifies the deployment's smoke key and writes `<profile>/valkey/.colors-recovery-verified` in the backup bucket. It does not perform production-host recovery. Do not infer crash durability from the graceful-restart gate.

Compute settings and provider behavior belong to the [pinned colors-compute library](https://github.com/getcolors/colors-compute/tree/ae28ea74962bb1897fa6365c143c1d43ac1fe095). The package requests one public machine with SSH-only ingress and no private network. Vultr is the live-test target. Provider switching requires deleting under the recorded provider first; unreadable state must never count as absence.
