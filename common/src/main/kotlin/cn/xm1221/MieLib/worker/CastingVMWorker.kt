package cn.xm1221.MieLib.worker

import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.Iota
import net.minecraft.server.level.ServerLevel

abstract class CastingVMWorker ( val isAfter: Boolean = false, val priority: Int = 0) {
   
    abstract fun execute(iota: Iota, world: ServerLevel, cont: SpellContinuation, call: CastingVMCall,vm: CastingVM):CastingVMCall

}


