package cn.xm1221.MieLib.worker

import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import cn.xm1221.MieLib.registry.IotaDisplayHook
import cn.xm1221.MieLib.toIota
import net.minecraft.nbt.Tag

abstract class IotaDisplayWorker(val type: IotaType<Iota>, override val priority: Int) : PriorityWorker {
    abstract  fun ifEffect(iota:Iota?): Boolean
    open fun ifEffect(tag: Tag): Boolean {
        return ifEffect(tag.toIota(type))
    }

    abstract  fun effect(iota:Iota?): IotaDisplayHook.IotaDisplayCall
    open fun effect(tag: Tag): IotaDisplayHook.IotaDisplayCall? {
        return effect(tag.toIota(type))
    }

}