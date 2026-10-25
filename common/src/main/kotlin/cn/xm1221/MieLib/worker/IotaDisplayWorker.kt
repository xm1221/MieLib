package cn.xm1221.MieLib.worker

import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import cn.xm1221.MieLib.registry.IotaDisplayHook

/**
 * 单个 iota 的显示改写单元。
 *
 * Hex 0.12 删掉了 `IotaType.getDisplay(CompoundTag)` / `getColor(CompoundTag)` 和 `KEY_DATA`。
 * 展示入口现在是实例方法 [Iota.display]；本 worker 直接拿到那个 [Iota]，不再读 NBT。
 *
 * 自动注入只包住具体方法 [Iota.displayWithMaxWidth]（`GuiSpellcasting` 走这里）。
 * [Iota.display] 和 [IotaType.color] 是各子类自己的实现，没有公共静态入口，mixin 包不住全部覆写。
 * 颜色字段仍可在 [effect] 里改，但目前没有自动套用点。
 */
abstract class IotaDisplayWorker(val type: IotaType<*>, override val priority: Int) : PriorityWorker {

    /** 返回 `false` 表示本次调用跳过该 worker；默认全部参与。 */
    open fun ifEffect(iota: Iota): Boolean = true

    /**
     * 基于 [iota] 改写显示信息。
     *
     * [prev] 是前一个 worker 的结果。不需要改写的字段必须原样保留。
     */
    abstract fun effect(iota: Iota, prev: IotaDisplayHook.IotaDisplayCall): IotaDisplayHook.IotaDisplayCall
}
