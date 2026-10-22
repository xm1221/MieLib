package cn.xm1221.MieLib.registry

import cn.xm1221.MieLib.worker.DrawPatternWorker
import net.minecraft.resources.ResourceLocation

object DrawPatternHook {
    private val workers = mutableMapOf<ResourceLocation, DrawPatternWorker>()

    @JvmStatic
    fun register(id: ResourceLocation, worker: DrawPatternWorker) {
        workers[id]=worker
    }

    @JvmStatic
    fun effect(
        drawLast: Boolean, tail: Int, head: Int, flowIrregular: Float
    ): DrawPatternWorker.DrawResult
    {
        val chain = workers.values.sortedBy { it.priority }
        var result = DrawPatternWorker.DrawResult(drawLast,tail,head,flowIrregular)
        for (worker in chain) {
            if (worker.ifEffect(result)) {
                result = worker.effect(result)
            }
        }
        return result
    }

}