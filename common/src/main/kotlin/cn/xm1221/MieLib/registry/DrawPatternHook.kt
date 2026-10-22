package cn.xm1221.MieLib.registry

import cn.xm1221.MieLib.worker.DrawPatternWorker
import net.minecraft.resources.ResourceLocation

/**
 * [DrawPatternWorker] 的注册表。
 *
 * 注册键为 [ResourceLocation] id。同 id **允许覆盖**：后注册的 worker 替换先前的，
 * 并打一条 `Mielib.LOGGER` 警告，便于排查意外覆盖。
 *
 * 执行顺序由 [DrawPatternWorker.priority] 升序决定，同优先级按 id 字符串稳定排序。
 */
object DrawPatternHook : HookWorkerRegistry<DrawPatternWorker>("DrawPatternHook") {

    /**
     * 注册一个 worker。同 [id] 已存在时覆盖旧注册，并打警告日志。
     */
    @JvmStatic
    fun register(id: ResourceLocation, worker: DrawPatternWorker) = put(id, worker)

    /** 按 [ordered] 的顺序依次应用 worker；未命中的 worker 保持原值。 */
    @JvmStatic
    fun effect(
        drawLast: Boolean, tail: Int, head: Int, flowIrregular: Float
    ): DrawPatternWorker.DrawResult =
        fold(DrawPatternWorker.DrawResult(drawLast, tail, head, flowIrregular)) { worker, result ->
            if (worker.ifEffect(result)) worker.effect(result) else result
        }
}
