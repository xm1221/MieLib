# AGENTS.md — MieLib

> Full canonical guide: [`AGENT.md`](AGENT.md) (keep `AGENT.md` intact; when conventions change, update both).
> Also see [`HEXCLI.md`](HEXCLI.md), [`docs/genhexbook.md`](docs/genhexbook.md), and [`CHANGELOG.md`](CHANGELOG.md).

## Project Scope & Stack

- **MieLib** (`cn.xm1221.MieLib`, mod id `mielib`) is a shared library mod for Hex Casting addons (not a player-facing content mod).
- Target stack (`gradle/libs.versions.toml` / `gradle.properties`): **Minecraft 1.20.1**, Java **17**, Kotlin **2.2.21**, Architectury **9.2.14**, Hex Casting **0.11.4** (Fabric + Forge). Do **not** upgrade to 1.21 / NeoForge or remove either loader unless explicitly instructed.
- Modules (`settings.gradle.kts`): `common/`, `fabric/` (incl. `fabric/src/example`), `forge/`, `hexcli-client/`, `hexcli-installer/`, plus `doc/` (`doc/genhexbook/`).

## Non-Negotiable Conventions

1. **Git Identity & Branch**: Commit/push as `xm1221 <3850632202@qq.com>` (never agent/bot author or committer). Default workflow pushes directly to `main` unless instructed otherwise.
2. **Scope Restraint**: Only touch files relevant to the task. Do not hardcode addon-specific spells into MieLib, do not add fake CurseForge/Modrinth IDs, and never commit secrets, tokens, or build outputs.
3. **Changelog & Deps**: Record user-visible or addon-observable API/behavior changes in `CHANGELOG.md` (Keep a Changelog). Put new dependencies in `gradle/libs.versions.toml`.
4. **HexCLI Skill**: Use the `hexcasting-hexcli` skill (`.agents/skills/hexcasting-hexcli/SKILL.md`, mirrored from `skills/hexcasting/SKILL.md`) when writing, running, or debugging Hex spells via HexCLI (`-Dmielib.hexcli=true`, soft-depends on `hexParse`).

## Core Hook Contracts

- **CastingVM Hooks** (`mixin/CastingVMMixin.java`, `registry/CastingVMHook.kt`, `worker/CastingVMWorker.kt`, `worker/CastingVMCall.kt`):
  - Only **one** `@WrapMethod(executeInner)` (`CastingVMMixin`) may exist; all extensions use `CastingVMHook.register`.
  - Workers run sorted by ascending `priority` (id tie-break): all Before (`!isAfter`, stops on `cancel(result)`), then `original.call` **at most once** (forbidden if cancelled), then all After (`isAfter`, runs even if Before cancelled; `cancel(result)` adopts result and stops remaining After). Final `result` must be non-null.
  - Duplicate `id` registration overwrites and must log `LOGGER.warn`. Always pass the `CastingVM` instance to workers.
- **Iota Display Hooks** (`registry/IotaDisplayHook.kt`, `mixin/IotaTypeMixin.java`):
  - Client-only display layer and **tag-only** (`KEY_DATA` `Tag`): never deserialize iotas inside display hooks and never recursively call wrapped `IotaType.getDisplay` / `getDisplayWithMaxWidth` / `getColor`.
  - Server-side deserialization must use `MieIotas.tryDeserialize(...)`.

## Build & Verification

```bash
./gradlew build
# or fast compile check across all modules:
./gradlew :common:classes :fabric:classes :forge:classes :hexcli-client:classes :hexcli-installer:classes
```
