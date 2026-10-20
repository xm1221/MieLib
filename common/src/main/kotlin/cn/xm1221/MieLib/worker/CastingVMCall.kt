package cn.xm1221.MieLib.worker

import at.petrak.hexcasting.api.casting.eval.CastResult

class CastingVMCall {
    var cancelled: Boolean = false
        private set

    var result: CastResult? = null

    fun  cancel() {
        cancelled = true
    }
}