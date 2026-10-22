package cn.xm1221.MieLib.registry

import cn.xm1221.MieLib.worker.DrawPatternWorker
import cn.xm1221.MieLib.worker.IotaDisplayWorker
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

object IotaDisplayHook {
    private val workers = mutableMapOf<ResourceLocation, IotaDisplayWorker>()

    @JvmStatic
    fun register(id: ResourceLocation, worker: IotaDisplayWorker) {
        workers[id]=worker
    }

    @JvmStatic
    fun effect(
        tag: Tag
    ): Component?
    {
        val chain = workers.values.sortedBy { it.priority }
        var res: Component? = null
       for ( worker in chain) {
           if (worker.ifEffect(tag)) {
               res = worker.effect(tag)
           }
       }
        return res
    }
}