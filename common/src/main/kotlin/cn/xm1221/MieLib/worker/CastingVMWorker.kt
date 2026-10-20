package cn.xm1221.MieLib.worker

import at.petrak.hexcasting.api.casting.eval.CastResult
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.Iota
import net.minecraft.server.level.ServerLevel

abstract class CastingVMWorker ( val isAfter: Boolean = false, val priority: Int = 0) {
   
    open fun beforeExecute(iota: Iota, world: ServerLevel, cont: SpellContinuation, call: CastingVMCall)

    open fun afterExecute(iota: Iota, world: ServerLevel, cont: SpellContinuation, result: CastResult): CastResult = result


class CastingVMCall {
    var cancelled: Boolean = false
        private set

    var result: CastResult? = null
        private set

    fun cancel(result: CastResult) {
        cancelled = true
        this.result = result
    }
}

}
