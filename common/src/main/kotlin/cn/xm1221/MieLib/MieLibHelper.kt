package cn.xm1221.MieLib

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.iota.Iota

fun List<Iota>.createVM(env: CastingEnvironment): CastingVM{
    val vm = CastingVM.empty(env = env)
    vm.image = vm.image.copy(stack = this)
    return vm
}


