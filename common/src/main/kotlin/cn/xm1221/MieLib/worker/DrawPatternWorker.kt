package cn.xm1221.MieLib.worker

abstract class DrawPatternWorker(override val priority: Int) : PriorityWorker {

    open fun ifEffect(result: DrawResult): Boolean = true

    open fun effect(drawResult: DrawResult): DrawResult = drawResult

    /**
     * `seed` 是 GuiSpellcasting.render 调用 RenderLib.drawPatternFromPoints 时的最后一个参数。
     * 对已绘制的图案，它是 GuiSpellcasting.patterns 中的索引；对正在绘制（WIP）的图案，它会超出该范围。
     * 需要用到该值时请重写此 seed 重载。仅客户端使用。
     */
    open fun ifEffect(result: DrawResult, seed: Double): Boolean = ifEffect(result)

    /**
     * `seed` 是 GuiSpellcasting.render 调用 RenderLib.drawPatternFromPoints 时的最后一个参数。
     * 对已绘制的图案，它是 GuiSpellcasting.patterns 中的索引；对正在绘制（WIP）的图案，它会超出该范围。
     * 需要用到该值时请重写此 seed 重载。仅客户端使用。
     */
    open fun effect(drawResult: DrawResult, seed: Double): DrawResult = effect(drawResult)

    class DrawResult(val drawLast: Boolean, val tail: Int, val head: Int, val flowIrregular: Float)
}
