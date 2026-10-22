package cn.xm1221.MieLib.registry

import cn.xm1221.MieLib.worker.IotaDisplayWorker
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * [IotaDisplayWorker] 的注册表。
 *
 * 注册键为 [ResourceLocation] id。同 id **允许覆盖**：后注册的 worker 替换先前的，
 * 并打一条 `Mielib.LOGGER` 警告，便于排查意外覆盖。
 *
 * 执行顺序由 [IotaDisplayWorker.priority] 升序决定，同优先级按 id 字符串稳定排序。
 */
object IotaDisplayHook : HookWorkerRegistry<IotaDisplayWorker>("IotaDisplayHook") {

    /**
     * 注册一个 worker。同 [id] 已存在时覆盖旧注册，并打警告日志。
     */
    @JvmStatic
    fun register(id: ResourceLocation, worker: IotaDisplayWorker) = put(id, worker)

    /**
     * 按 [ordered] 的顺序依次应用 worker；后命中的 worker 覆盖先前结果
     * （包括返回 `null`，即 last-hit-wins）。
     */
    @JvmStatic
    fun effect(
        tag: Tag
    ): Component? =
        fold<Component?>(null) { worker, previous ->
            if (worker.ifEffect(tag)) worker.effect(tag) else previous
        }
}
