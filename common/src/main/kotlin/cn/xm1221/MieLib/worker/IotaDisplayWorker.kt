package cn.xm1221.MieLib.worker

import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import cn.xm1221.MieLib.toIota
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component

abstract class IotaDisplayWorker(val type: IotaType<Iota>, val priority:Int) {
    abstract  fun ifEffect(iota:Iota?): Boolean
    open fun ifEffect(tag: Tag): Boolean {
        return ifEffect(tag.toIota(type))
    }

    abstract  fun effect(iota:Iota?): Component?
    open fun effect(tag: Tag): Component? {
        return effect(tag.toIota(type))
    }
}