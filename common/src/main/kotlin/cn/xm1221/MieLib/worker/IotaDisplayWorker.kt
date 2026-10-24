package cn.xm1221.MieLib.worker

import at.petrak.hexcasting.api.casting.iota.IotaType
import cn.xm1221.MieLib.registry.IotaDisplayHook
import net.minecraft.nbt.Tag

/**
 * 单个 iota 的显示 / 颜色改写单元。
 *
 * 生命周期与线程约束：
 * - 只在**客户端**渲染时被调用（`IotaType.getDisplay` / `getDisplayWithMaxWidth` / `getColor`）。
 * - [data] 是完整 tag 里的 [at.petrak.hexcasting.common.lib.hex.HexIotaTypes.KEY_DATA] 子 tag，
 *   即该 iota 序列化后的数据；hook **不会**反序列化 iota，worker 也不要自行反序列化
 *   （客户端没有 `ServerLevel`，反序列化会失败或拿到错误结果）。
 * - 需要的展示信息请在 iota 的 `serialize()` 里写进 [data]，或直接从 [data] 读取原始 NBT。
 *
 * 执行顺序由 [priority] 升序决定（数字越小越先），同优先级按注册 id 字符串稳定排序。
 */
abstract class IotaDisplayWorker(val type: IotaType<*>, override val priority: Int) : PriorityWorker {

    /** 返回 `false` 表示本次调用跳过该 worker；默认全部参与。 */
    open fun ifEffect(data: Tag): Boolean = true

    /**
     * 基于 [data] 改写显示信息。
     *
     * [prev] 是前一个 worker 的结果；[IotaDisplayHook.IotaDisplayCall.display] /
     * [IotaDisplayHook.IotaDisplayCall.color] 在**没有被请求**时为 `null`
     * （显示路径不请求颜色，颜色路径不请求显示），**不需要改写的字段必须原样保留**，
     * 不要用 `null` 覆盖它们。
     */
    abstract fun effect(data: Tag, prev: IotaDisplayHook.IotaDisplayCall): IotaDisplayHook.IotaDisplayCall
}
