package cn.xm1221.MieLib

import at.petrak.hexcasting.api.casting.SpellList
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.iota.ListIota
import net.minecraft.nbt.Tag

fun List<Iota>.createVM(env: CastingEnvironment): CastingVM{
    val vm = CastingVM.empty(env = env)
    vm.image = vm.image.copy(stack = this)
    return vm
}

fun Iota.toSpell(): SpellList {
    if (this is ListIota){
        return this.list
    }
    else return ListIota(listOf<Iota>(this)).list
}


fun Tag.toIota(type: IotaType<Iota>): Iota? {
   return type.deserialize(this,null)
}