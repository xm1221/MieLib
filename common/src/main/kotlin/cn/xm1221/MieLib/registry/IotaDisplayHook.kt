package cn.xm1221.MieLib.registry

import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import cn.xm1221.MieLib.Mielib
import cn.xm1221.MieLib.worker.IotaDisplayWorker
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * [IotaDisplayWorker] 的注册表。
 *
 * Hex 0.12 不再提供 tag 形态的 `IotaType.getDisplay`。Worker 按 [Iota.getType] 匹配，
 * 输入是 [Iota] 实例。自动调用点是 [Iota.displayWithMaxWidth]（见 `IotaDisplayMixin`）。
 */
object IotaDisplayHook : HookWorkerRegistry<IotaDisplayWorker>("IotaDisplayHook") {

    @JvmStatic
    fun register(id: ResourceLocation, worker: IotaDisplayWorker) = put(id, worker)

    @JvmStatic
    fun hasWorkersFor(iota: Iota): Boolean {
        val type = iota.type
        return ordered().any { it.type === type }
    }

    @JvmStatic
    fun effect(iota: Iota, initial: IotaDisplayCall): IotaDisplayCall {
        val type = iota.type
        var call = initial
        for (worker in ordered()) {
            if (worker.type !== type) continue
            call = try {
                if (worker.ifEffect(iota)) worker.effect(iota, call) else call
            } catch (e: Exception) {
                Mielib.LOGGER.warn(
                    "IotaDisplayHook: worker {} failed for type {}; keeping previous result",
                    worker::class.java.name,
                    type,
                    e,
                )
                call
            }
        }
        return call
    }

    @JvmStatic
    fun displayOf(iota: Iota, originalDisplay: Component?): Component? {
        if (!hasWorkersFor(iota)) return originalDisplay
        val result = effect(iota, IotaDisplayCall(originalDisplay, null, iota.type))
        return result.display ?: originalDisplay
    }

    class IotaDisplayCall(
        var display: Component?,
        var color: Int?,
        val type: IotaType<*>?,
    )
}
