package cn.xm1221.MieLib.worker

import at.petrak.hexcasting.api.casting.eval.ResolvedPattern
import at.petrak.hexcasting.client.gui.GuiSpellcasting

abstract class DrawPatternWorker(override val priority: Int) : PriorityWorker {

    open fun ifEffect(result: DrawResult): Boolean = true

    open fun effect(drawResult: DrawResult): DrawResult = drawResult

    /**
     * `seed` 是 GuiSpellcasting.render 调用 RenderLib.drawPatternFromPoints 时的最后一个参数。
     * 对已绘制的图案，它是 GuiSpellcasting.patterns 中的索引；对正在绘制（WIP）的图案，它会超出该范围。
     * 需要用到该值时请重写此 seed 重载或 [DrawPatternContext] 重载。仅客户端使用。
     */
    open fun ifEffect(result: DrawResult, seed: Double): Boolean = ifEffect(result)

    /**
     * `seed` 是 GuiSpellcasting.render 调用 RenderLib.drawPatternFromPoints 时的最后一个参数。
     * 对已绘制的图案，它是 GuiSpellcasting.patterns 中的索引；对正在绘制（WIP）的图案，它会超出该范围。
     * 需要用到该值时请重写此 seed 重载或 [DrawPatternContext] 重载。仅客户端使用。
     */
    open fun effect(drawResult: DrawResult, seed: Double): DrawResult = effect(drawResult)

    /**
     * 带完整渲染上下文的判断重载；默认转发到 [ifEffect(result, ctx.seed)] 以保持向后兼容。
     * 需要访问 [DrawPatternContext.gui] / [DrawPatternContext.pattern] / [DrawPatternContext.index] 时重写此方法。
     */
    open fun ifEffect(result: DrawResult, ctx: DrawPatternContext): Boolean = ifEffect(result, ctx.seed)

    /**
     * 带完整渲染上下文的效果重载；默认转发到 [effect(drawResult, ctx.seed)] 以保持向后兼容。
     * 需要访问 [DrawPatternContext.gui] / [DrawPatternContext.pattern] / [DrawPatternContext.index] 时重写此方法。
     */
    open fun effect(drawResult: DrawResult, ctx: DrawPatternContext): DrawResult = effect(drawResult, ctx.seed)

    /**
     * 单次图案描边的渲染上下文（仅客户端使用）。
     *
     * @property gui 正在渲染的 [GuiSpellcasting] 实例（直接调用旧版 `DrawPatternHook.effect` 时可能为 `null`）。
     * @property index 已绘制图案在 `gui.patterns` 中的下标；正在绘制（WIP）或未知时为 `-1`。
     * @property pattern 对应的 [ResolvedPattern] 实例；WIP 或未知时为 `null`。
     * @property isWip 是否为正在绘制中的临时图案（或无法匹配到已绘制图案）。
     * @property seed 传给 `RenderLib.drawPatternFromPoints` 的 `seed` 参数。
     */
    class DrawPatternContext(
        val gui: GuiSpellcasting?,
        val index: Int,
        val pattern: ResolvedPattern?,
        val isWip: Boolean,
        val seed: Double,
    )

    class DrawResult(val drawLast: Boolean, val tail: Int, val head: Int, val flowIrregular: Float) {
        /** 保留 `drawLast` 与 `flowIrregular`，仅替换尾/头渐变颜色。 */
        fun withColor(tail: Int, head: Int): DrawResult = DrawResult(drawLast, tail, head, flowIrregular)

        /** 保留 `drawLast` 与 `flowIrregular`，将尾与头设为同一纯色。 */
        fun withColor(color: Int): DrawResult = DrawResult(drawLast, color, color, flowIrregular)

        /** 按需复制并修改部分字段。 */
        @JvmOverloads
        fun copy(
            drawLast: Boolean = this.drawLast,
            tail: Int = this.tail,
            head: Int = this.head,
            flowIrregular: Float = this.flowIrregular,
        ): DrawResult = DrawResult(drawLast, tail, head, flowIrregular)
    }
}
