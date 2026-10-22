# AGENT.md — MieLib

给在本仓库上写代码的人（含 Claude Code / 其它 coding agent）用的总领说明。
动手前先读完本文；有冲突时以本文为准，其次是 `CHANGELOG.md` 与现有代码。

---

## 1. 项目是什么

**MieLib**（`mielib`）是给 [Hex Casting](https://github.com/FallingColors/HexMod) 附属模组用的 **公共库模组**，不是面向玩家的内容模组。

当前重心：

- 为附属提供稳定的 **CastingVM `executeInner` 钩子**（Before / After），避免每个 addon 各自 mixin 互踩。
- 共用脚手架：Architectury 多加载器、配置、网络、Registrar、hexdoc 等。

仓库：https://github.com/xm1221/MieLib  
默认分支：`main`  
坐标 / 包名：`cn.xm1221.MieLib`，模组 id：`mielib`

---

## 2. 仓库结构（别乱搬家）

| 路径 | 职责 |
|------|------|
| `common/` | 跨加载器逻辑与资源（钩子、Registrar、网络抽象等） |
| `fabric/` / `forge/` | 加载器入口与平台实现（`MielibAbstractionsImpl` 等） |
| `buildSrc/` | Gradle 约定插件 |
| `doc/` | hexdoc 文档工程 |
| `gradle/libs.versions.toml` | 版本目录；改依赖先改这里 |
| `CHANGELOG.md` | Keep a Changelog；**有用户可见行为/API 变更必须写** |
| `AGENT.md` | 本文；改协作约定时同步更新 |

当前游戏目标版本以 `gradle.properties` / `libs.versions.toml` 为准（撰写时为 **1.20.1** + Hex 0.11.x + Architectury）。升 1.21 / NeoForge 是单独里程碑，不要在无关 PR 里顺手升版本。

---

## 3. 非协商约定

1. **Git 署名**：提交与 push 必须使用作者  
   `xm1221 <3850632202@qq.com>`  
   禁止 Claude / bot / CodeHelper 作为 author 或 committer。
2. **直接推 `main`**（除非维护者另有说明）。不要默认开 feature 分支或提 PR，除非被要求。
3. **库的姿态**：优先稳定、可预期的 API；破坏性改动要在 CHANGELOG 的 `Changed` / `Removed` 写清楚，并尽量给迁移一句说明。
4. **不要引入第二个 `executeInner` WrapMethod**。全进程只允许 `CastingVMMixin` 那一处；扩展只能 `CastingVMHook.register`。
5. **本地 Claude Code 优先** 改本仓库；不要擅自改用 Cursor Cloud Agent，除非维护者明确要求。
6. **范围克制**：一次任务只改相关文件。脚手架大翻、升 MC 版本、加新内容模组功能，需单独授权。
7. **密钥与发布**：不提交 token；CurseForge / Modrinth id 空着就别瞎填或假发版。

---

## 4. CastingVM Hooks（核心契约）

实现落在：

- `mixin/CastingVMMixin.java` — 唯一 `@WrapMethod(executeInner)`
- `registry/CastingVMHook.kt` — 注册表与流水线
- `worker/CastingVMWorker.kt` — addon 实现的钩子单元
- `worker/CastingVMCall.kt` — 单次调用的可变状态

### 4.1 流水线（必须遵守）

1. 快照已注册 worker，按 **`priority` 升序**（越小越先），同优先级按 id 字符串稳定排序。
2. 跑全部 **Before**（`isAfter == false`）。某 Before `cancel(result)` 后停止后续 Before。
3. **原方法 `original.call` 全程最多一次**：未 cancel 则调用并 `setResult`；已 cancel 则 **禁止** 再调原方法，且 `result` 必须非空。
4. 跑全部 **After**（`isAfter == true`），**即使 Before 已 cancel 也要跑**（便于清理 / 日志）。After 里 `cancel(result)` = 采用该结果并停止后续 After；**禁止**再调原方法。
5. 最终返回非空 `result`，否则抛错。

禁止：末尾或 fallback 再次 `original.call`；空 `cancel()` 不带结果。

同 id **允许覆盖**（后注册替换先前），覆盖时应打警告日志，避免完全静默。

### 4.2 API 要点

- `register(id: ResourceLocation, worker)`：同 id → **覆盖**旧注册，并 `LOGGER.warn`（更灵活，便于热替换 / 调试）。
- `CastingVMCall.cancel(result)` / `setResult(result)`；`result` / `cancelled` 为受限可见。
- Worker 只有一个 `execute(..., vm: CastingVM)`；用 `isAfter` 区分阶段，不要再拆两个 abstract。
- Hook / mixin 必须把 **`CastingVM` 实例** 传给 Worker。

改钩子语义时：同步改 KDoc，并更新 `CHANGELOG.md`。

---

## 5. 代码风格

- Kotlin 为主，Mixin 用 Java 可以。
- 公开 API 写清 KDoc（尤其是 cancel / priority / 调用次数）。
- 命名：模组 id `mielib`；Kotlin 顶层对象以现有 `Mielib` 为准，不要为了「好看」批量重命名。
- 格式：顺手整理触碰文件的明显空格/换行即可，禁止全仓库无关 reformat。
- 新依赖进 `libs.versions.toml`，并说明为什么 common 需要它。

---

## 6. 构建与验证

```bash
./gradlew build
# 或至少
./gradlew :common:compileKotlin
```

依赖拉不下来时：仍然可以提交 **语义正确的源码 + CHANGELOG + AGENT 更新**，并在汇报里写明构建失败原因；不要为了「绿」而删掉必要的 Hex 依赖。

---

## 7. CHANGELOG

- 遵循 [Keep a Changelog](https://keepachangelog.com/)。
- 版本号见 `gradle.properties` 的 `modVersion`。
- 每次用户可见或 addon 可感知的 API / 行为变化，必须写入对应版本的 `Added` / `Changed` / `Fixed` / `Removed`。
- 未正式对外发布前，可在 `0.0.1` 条目下扩写；一旦打过 tag / 发过 Maven，破坏性改动应升版本。

---

## 8. Agent 工作流程（默认）

1. 读本文 + 相关源码 + 当前 `CHANGELOG.md`。
2. 用最小改动实现任务；钩子相关必须满足第 4 节。
3. 更新 CHANGELOG（若有行为/API 变化）。
4. 若改了协作约定，更新本文。
5. 本地编译（能编则编）。
6. 以 `xm1221` 身份 commit，push `origin/main`。
7. 向维护者汇报：commit、改动摘要、构建结果、已知风险。

---

## 9. 明确不要做的事

- 为单个 addon 功能（例如某一款附属的具体法术）把逻辑写死进 MieLib，除非维护者确认这是「库级」能力。
- 在 MieLib 里再加第二套 CastingVM mixin「保险」。
- 未经要求把仓库升到 1.21 / NeoForge，或删掉 Fabric/Forge 其中一侧。
- 提交构建产物、密钥、大型二进制（除现有 `libs/` 约定）。
- 用假数据填发布 id 或自动发 Curse/Modrinth。

---

## 10. 维护者

- 所有者：xm1221  
- 相关附属：MieHex / MieHex:Revolution 等（消费本库，不在本仓库内实现其内容）。

有疑问时先停下来问维护者，不要猜测着扩大范围。
