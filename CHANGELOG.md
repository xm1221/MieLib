# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- **BREAKING**: `IotaDisplayWorker` is now tag-only: constructor is `IotaDisplayWorker(type: IotaType<*>, priority: Int)`, default hook gate is `open fun ifEffect(data: Tag): Boolean = true`, and effect entry is `abstract fun effect(data: Tag, prev: IotaDisplayCall): IotaDisplayCall`.
- Migration: replace `effect(iota: Iota?)` with `effect(data, prev)`, read values from the `KEY_DATA` tag, write needed display info in your iota's `serialize()`, and keep fields of `prev` you don't change.
- `IotaDisplayHook` now executes as `effect(full, initial)`, and workers only run for tags whose `type === worker.type`.
- The `IotaType` mixin is now client-only and wraps static `IotaType.getDisplay` / `getDisplayWithMaxWidth` / `getColor` instead of abstract `display(Tag)`.
- `HookWorkerRegistry` now caches sorted worker order.

### Added

- `MieIotas.tryDeserialize(full, level)` / `MieIotas.tryDeserialize(type, data, level)` (server cached via Architectury `LifecycleEvent.SERVER_STARTED` / `SERVER_STOPPED`, with `GarbageIota` fallback).
- `IotaDisplayHook.hasWorkersFor` / `displayOf` / `colorOf`.
- Iota display color override support.
- `HookWorkerRegistry.isEmpty()`.

### Removed

- `Tag.toIota(type)` (it deserialized with a null `ServerLevel`).

### Fixed

- Infinite recursion between wrapped `getColor`/`getDisplay` and `IotaDisplayCall.init`.
- Mixin incorrectly targeting abstract `IotaType.display(Tag)`.
- NPE from `!!` when a worker returned null and dead `if (res != null)` branch.
- Iotas being deserialized on the client with a null world (e.g. entity/list iotas could NPE).
- Worker list being re-sorted on every render call.

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
