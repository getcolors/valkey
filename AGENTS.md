# Valkey package instructions

Read `../workspace/CLAUDE.md` and its compute-provider, ssh-keypair, ssh-config, and compute-name standards. This package implements green only under `green/`. Resource templates live under `green/src/resources/io/github/getcolors/valkey/`. The skill payload owns the launcher; `green/green` is its symlink. Deployment launchers are copies.

Delegate compute and state ownership to the immutable pinned colors-compute library. Do not add provider templates or a local provider registry. Application backups use an existing bucket. This package does not create or destroy application buckets.

Never read or display `.envrc.private`, put credentials in rendered output, export `COLORS_PAR_PROFILE`, or edit `.colors/`. Real lifecycle actions need authorization. Preserve prevent-destroy guards and profile-specific state and backup paths.

Use `VALKEYCLI_AUTH`, `valkey-cli`, `valkey-server`, and `valkey-check-rdb`. Identify the server using `server_name` and `valkey_version`; the Redis version field is compatibility metadata. Gate authentication on reply text, not exit status alone. Do not replace scratch restore verification with PONG alone.

Run the commands in README.md. Inspect fresh golden output before accepting it. Record live failures and their fixes without secrets. Only verified observations belong in the companion Context Skill. Do not describe a graceful restart as a power-loss test or scratch restoration as full host recovery.
