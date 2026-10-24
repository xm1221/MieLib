package cn.xm1221.MieLib.registry

import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes
import cn.xm1221.MieLib.Mielib
import cn.xm1221.MieLib.worker.IotaDisplayWorker
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * [IotaDisplayWorker] 的注册表。
 *
 * 注册键为 [ResourceLocation] id。同 id **允许覆盖**：后注册的 worker 替换先前的，
 * 并打一条 [Mielib.LOGGER] 警告，便于排查意外覆盖。
 *
 * 执行顺序由 [IotaDisplayWorker.priority] 升序决定，同优先级按 id 字符串稳定排序。
 *
 * ### 客户端 / 反序列化契约
 *
 * 这里只处理序列化后的 tag（[CompoundTag]）：`KEY_TYPE` 给出 [IotaType]，
 * `KEY_DATA` 作为 [IotaDisplayWorker.effect] 的 `data` 参数交给 worker。
 * **绝不反序列化 iota**（客户端没有 `ServerLevel`，反序列化会失败）。
 * Worker 需要展示的信息应在 iota 的 `serialize()` 中写入 tag。
 */
object IotaDisplayHook : HookWorkerRegistry<IotaDisplayWorker>("IotaDisplayHook") {

    /**
     * 注册一个 worker。同 [id] 已存在时覆盖旧注册，并打警告日志。
     */
    @JvmStatic
    fun register(id: ResourceLocation, worker: IotaDisplayWorker) = put(id, worker)

    /**
     * 是否有 worker 可能处理 [full]（按 `KEY_TYPE` 匹配 [IotaDisplayWorker.type]）。
     *
     * 供 mixin 做廉价短路，避免在没有 worker 时构造初始 [IotaDisplayCall]。
     */
    @JvmStatic
    fun hasWorkersFor(full: CompoundTag): Boolean {
        val type = IotaType.getTypeFromTag(full) ?: return false
        return ordered().any { it.type === type }
    }

    /**
     * 依次应用匹配 [full] 的 worker。
     *
     * - `KEY_TYPE` 无法解析或缺少 `KEY_DATA` 时直接返回 [initial]。
     * - 只有 `worker.type === type` 且 [IotaDisplayWorker.ifEffect] 为真的 worker 参与。
     * - 每个 worker 收到上一步的结果；若其抛异常，记录警告并保留上一步（附加组件不应弄崩渲染）。
     *
     * [initial] 由调用方按需构造：显示路径只填 `display`，颜色路径只填 `color`；
     * worker 必须原样保留自己不改的字段（`prev.display` / `prev.color` 在未被请求时为 `null`）。
     */
    @JvmStatic
    fun effect(full: CompoundTag, initial: IotaDisplayCall): IotaDisplayCall {
        val type = IotaType.getTypeFromTag(full) ?: return initial
        val data = full.get(HexIotaTypes.KEY_DATA) ?: return initial

        var call = initial
        for (worker in ordered()) {
            if (worker.type !== type) continue
            call = try {
                if (worker.ifEffect(data)) worker.effect(data, call) else call
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

    /**
     * 显示路径的便捷入口：以 [originalDisplay] 为初始值跑 hook，返回最终显示文本。
     *
     * 没有 worker 或 worker 未改写时返回 [originalDisplay]。不会调用被 mixin 包装的
     * `IotaType.getDisplay` / `getDisplayWithMaxWidth`，因此不会递归。
     */
    @JvmStatic
    fun displayOf(full: CompoundTag, originalDisplay: Component?): Component? {
        val type = IotaType.getTypeFromTag(full) ?: return originalDisplay
        if (!hasWorkersFor(full)) return originalDisplay
        val result = effect(full, IotaDisplayCall(originalDisplay, null, type))
        return result.display ?: originalDisplay
    }

    /**
     * 颜色路径的便捷入口：以 [originalColor] 为初始值跑 hook，返回最终颜色。
     *
     * 没有 worker 或 worker 未改写时返回 [originalColor]。
     */
    @JvmStatic
    fun colorOf(full: CompoundTag, originalColor: Int): Int {
        val type = IotaType.getTypeFromTag(full) ?: return originalColor
        if (!hasWorkersFor(full)) return originalColor
        val result = effect(full, IotaDisplayCall(null, originalColor, type))
        return result.color ?: originalColor
    }

    /**
     * 单次显示调用中在 worker 之间传递的可变状态。
     *
     * [display] / [color] 在**未被请求**时为 `null`（显示路径不请求颜色，颜色路径不请求显示）。
     * worker 必须保留自己不改写的字段。
     */
    class IotaDisplayCall(
        var display: Component?,
        var color: Int?,
        val type: IotaType<*>?,
    )
}
