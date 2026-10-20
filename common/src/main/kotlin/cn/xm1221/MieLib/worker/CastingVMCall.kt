package cn.xm1221.MieLib.worker

import at.petrak.hexcasting.api.casting.eval.CastResult

/**
 * 单次 `CastingVM.executeInner` 调用中在 [CastingVMWorker] 之间传递的可变状态。
 *
 * 约定：
 * - Before 阶段：要跳过原方法，**必须**调用 [cancel] 并带上结果。
 * - After 阶段开始时 [cancelled] 会被 [enterAfterPhase] 清空（保留 [result]），
 *   因此 Before 的取消不会截断 After 链。
 * - After 阶段：只想改写返回值并让后续 After 继续 → [setResult]；
 *   想截断后续 After → [cancel]。
 */
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

    /** 仅替换结果，不切换 [cancelled]（供 After 改写返回值且继续后续 After）。 */
    fun setResult(result: CastResult) {
        this.result = result
    }

    /**
     * 进入 After 阶段：清空 [cancelled]，保留 [result]。
     *
     * 仅由 `CastingVMHook` 在 Before 阶段与原方法调用之后、After 循环之前调用，
     * 使 Before 的取消不会跳过后续 After；After 中新的 [cancel] 仍会截断后续 After。
     */
    internal fun enterAfterPhase() {
        cancelled = false
    }
}
