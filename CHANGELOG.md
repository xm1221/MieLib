# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.0.1] - 2026-10-21

### Added

- Initial version.
- `CastingVMHook` / `CastingVMWorker` / `CastingVMCall` API for extending `CastingVM.executeInner` from addons (MieHex:Revolution etc.).
- `AGENT.md`, the overarching agent guide for working in this repository (including the hook contract).
- Client `DrawPatternHook` / `DrawPatternWorker` registry; a single `GuiSpellcasting` `WrapOperation` on `RenderLib.drawPatternFromPoints` lets addons rewrite `drawLast`/`tail`/`head`/`flowIrregular` (workers run in ascending priority).
- Client `IotaDisplayHook` / `IotaDisplayWorker` registry for overriding iota display text.
- `PriorityWorker` interface and `HookWorkerRegistry<W>` shared registry backing `CastingVMHook`, `DrawPatternHook`, and `IotaDisplayHook` (`ordered()` + `fold`).

### Fixed

- `CastingVM.executeInner`'s original method is now called **exactly once** on the success path and **zero times** when a Before worker cancels; previously it could be called two or three times per cast.
- Before-stage cancellation no longer silently falls back to `original.call(...)`. A Before `cancel` without a result now throws instead of effectively un-cancelling the hook.
- After workers now run even when a Before worker cancelled the original call, so cleanup/logging hooks are not skipped. The After phase starts with `cancelled` cleared (the result is kept), so a Before-stage cancel no longer truncates the After chain after the first worker; only an After-stage `cancel` stops later After workers.
- After-stage `cancel` no longer re-invokes the original method; it adopts the current `result` and stops later After workers.
- A missing final `CastResult` now fails loudly with `IllegalStateException` rather than returning `null`.

### Changed

- Hook execution order is now **ascending `priority`**, with ties broken by the id string, instead of registration order.
- `CastingVMCall.cancel()` is now `cancel(result: CastResult)`; `result` has a private setter and a new `setResult(result)` is available for After workers that only want to rewrite the return value.
- `CastingVMWorker` has a single `execute(...)` entry point; the separate `beforeExecute` / `afterExecute` abstract methods were removed in favour of the `isAfter` constructor parameter.
- Workers now receive the `CastingVM` instance (`vm`), allowing hooks to read/modify the VM image and environment.
- `CastingVMHook` KDoc updated to describe the actual pipeline.
- Duplicate `CastingVMHook.register` ids **overwrite** the previous worker (with a warn log) instead of throwing, so addons can replace a hook flexibly.
- `CastingVMWorker` / `DrawPatternWorker` / `IotaDisplayWorker` implement `PriorityWorker`; constructors unchanged for source compatibility.
- `DrawPatternHook` / `IotaDisplayHook` now use the same `ConcurrentHashMap` + overwrite-warn + id-tiebreak behaviour as `CastingVMHook` (was silent overwrite / unordered ties).
- `CastingVMHook.execute` semantics unchanged; only ordering lookup moved to `HookWorkerRegistry.ordered()`.
