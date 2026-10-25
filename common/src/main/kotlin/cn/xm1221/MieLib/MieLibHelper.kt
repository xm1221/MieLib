package cn.xm1221.MieLib

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.utils.TreeList

fun List<Iota>.createVM(env: CastingEnvironment): CastingVM {
    val vm = CastingVM.empty(env = env)
    vm.image = vm.image.copy(stack = TreeList.from(this))
    return vm
}

/** Hex 0.12 removed `SpellList`; list iotas store a [TreeList]. */
fun Iota.toSpell(): TreeList<Iota> {
    if (this is ListIota) {
        return this.list
    }
    return TreeList.from(listOf(this))
}
