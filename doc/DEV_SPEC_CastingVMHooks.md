# MieLib CastingVM Hooks 开发规范

面向 `CastingVMHook` / `CastingVMWorker` / `CastingVMCall` 与唯一的 `CastingVMMixin`。
本规范是 addon（如 MieHex:Revolution 刻印集）对接前的契约；实现必须遵守。

版本目标：在现有 `0.0.1` 上修正 hook 语义后记入 CHANGELOG（Keep a Changelog）。

---

## 1. 架构硬约束

1. **全进程只有一处** `@WrapMethod` 挂在 `CastingVM.executeInner`（`CastingVMMixin`）。其它模组禁止再 wrap 同一方法；扩展只能 `CastingVMHook.register`。
2. Hook 必须收到 **`CastingVM` 实例**（mixin 里 `(CastingVM)(Object)this`），以便改 image / 读 env。
3. 注册键为 **`ResourceLocation` id**；同 id 不允许静默覆盖：已存在则 `Mielib.LOGGER.warn` 并 **拒绝覆盖**（`putIfAbsent` / 显式检查后 return 或 throw `IllegalArgumentException`，二选一写进 KDoc，推荐 throw）。
4. 执行顺序：按 `priority` **升序**（数字越小越先）。同 priority 时顺序稳定即可（例如按 id 字符串）。
5. Worker 只有一个入口：`execute(...)`；用构造参数 `isAfter: Boolean` 区分 Before / After 阶段。不要再拆 `beforeExecute` / `afterExecute` 两个 abstract（避免空实现噪音）。

---

## 2. `executeInner` 流水线（必须一字不差遵守）

伪代码（实现应对齐语义，可微调局部变量名）：

```kotlin
fun execute(iota, world, cont, original, vm): CastResult {
    val chain = snapshotWorkersSorted() // values 拷贝后 sortedBy { priority }.thenBy { id }
    var call = CastingVMCall()

    // —— Before ——
    for (worker in chain.filter { !it.isAfter }) {
        call = worker.execute(iota, world, cont, call, vm)
        if (call.cancelled) break  // 停止后续 Before；此时必须已有 call.result
    }

    // —— Original：整个流水线最多调用一次 ——
    if (!call.cancelled) {
        call.result = original.call(iota, world, cont)
    } else {
        requireNotNull(call.result) {
            "CastingVMHook: before-cancel without result; use CastingVMCall.cancel(result)"
        }
    }

    // —— After：即使 Before 取消了也要跑（便于清理 / 日志）——
    // After 阶段「cancel」= 采用当前 call.result，并停止后续 After（不再次调用 original）
    for (worker in chain.filter { it.isAfter }) {
        call = worker.execute(iota, world, cont, call, vm)
        if (call.cancelled) break
    }

    return requireNotNull(call.result) { "CastingVMHook: missing CastResult" }
}
```

### 禁止

- 在函数末尾或 fallback 里 **再次** `original.call(...)`。
- Before `cancel` 后若 `result == null` 回落到 `original.call`（等于没取消）。
- After 里用 `original.call` 作为 fallback。

---

## 3. `CastingVMCall` API

```kotlin
class CastingVMCall {
    var cancelled: Boolean = false
        private set
    var result: CastResult? = null
        private set

    /** 取消后续 Before / 停止后续 After，并设定本步结果。 */
    fun cancel(result: CastResult) {
        this.result = result
        cancelled = true
    }

    /** 仅替换结果，不切换 cancelled（供 After 改写返回值且继续后续 After）。 */
    fun setResult(result: CastResult) {
        this.result = result
    }
}
```

约定：

- Before：要跳过原方法 → **必须** `cancel(result)`。
- After：只想改返回值并让后面 After 继续 → `setResult(result)`；想截断后续 After → `cancel(result)`。
- 禁止把 `result` 做成公开 setter 却让 `cancel()` 不带参数（易漏设结果）。

---

## 4. `CastingVMWorker`

```kotlin
abstract class CastingVMWorker(
    val isAfter: Boolean = false,
    val priority: Int = 0,
) {
    abstract fun execute(
        iota: Iota,
        world: ServerLevel,
        cont: SpellContinuation,
        call: CastingVMCall,
        vm: CastingVM,
    ): CastingVMCall
}
```

- 默认应 **返回同一个 `call` 实例**（可变状态在 call 上）；若返回新实例，Hook 以返回值为准，但文档不鼓励无谓拷贝。
- Worker 内不要自己调 `original`；能否跑原方法由 Hook 决定。

---

## 5. 文档与 CHANGELOG

1. `CastingVMHook` 的 KDoc 必须与第 2 节流水线一致（禁止再写「按注册顺序」或已删除的 before/after 方法名）。
2. `CHANGELOG.md` 使用 [Keep a Changelog](https://keepachangelog.com/)：
   - 在 `## 0.0.1` 下用 `### Fixed` / `### Changed` 写清 hook 语义修正（不要另开版本号，除非已发布过 0.0.1 给外部；当前未正式发布，可把 0.0.1 条目扩写，并加日期 `## [0.0.1] - YYYY-MM-DD`）。
3. 中英文均可；至少中文或英文一种写清楚「original 只调用一次」「cancel 必须带 result」「After 在 cancel 后仍执行」。

---

## 6. 工程约定

- Git author/committer：`xm1221 <3850632202@qq.com>`。
- 直接推当前默认分支 `main`（无 feature 分支，除非 push 被拒）。
- 本轮只改 hook 相关：`CastingVMHook.kt`、`CastingVMWorker.kt`、`CastingVMCall.kt`、`CastingVMMixin.java`、本规范、`CHANGELOG.md`；`MieLibHelper.kt` 可保留，仅做格式整理。
- 能编译则编；若依赖 Hex 拉取失败，至少保证上述源文件语义正确并提交。

---

## 7. 验收清单

- [ ] `original.call` 在成功路径恰好 1 次；cancel 路径 0 次。
- [ ] `cancel(result: CastResult)` + `setResult`；无「空 cancel」。
- [ ] Before cancel 后仍跑全部 After（除非某个 After 自己 cancel 截断）。
- [ ] `vm` 传入 Worker。
- [ ] priority 升序；KDoc 已更新。
- [ ] 重复 id 有明确失败行为。
- [ ] CHANGELOG 已写。
