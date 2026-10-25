package cn.xm1221.MieLib.vm

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM

/**
 * Mixin bridge applied to [CastingVM]. Addons should call [replaceEnv].
 */
interface CastingVMEnvAccess {
    fun `mielib$replaceEnv`(next: CastingEnvironment)
}

/**
 * Replaces `env` on this existing [CastingVM] and runs
 * [CastingEnvironment.triggerCreateEvent] with the current image user data.
 *
 * Call this from a Before [cn.xm1221.MieLib.worker.CastingVMWorker]. Do not
 * cancel the cast only to swap the environment: later `executeInner`,
 * `postExecution`, and `postCast` keep this same instance and must see the
 * new environment.
 *
 * A null [next] is rejected.
 */
@Suppress("CAST_NEVER_SUCCEEDS")
fun CastingVM.replaceEnv(next: CastingEnvironment) {
    requireNotNull(next) { "CastingVM.replaceEnv: next environment must not be null" }
    (this as CastingVMEnvAccess).`mielib$replaceEnv`(next)
}
