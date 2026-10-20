package cn.xm1221.MieLib.registry

import at.petrak.hexcasting.api.casting.eval.CastResult
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.Iota
import cn.xm1221.MieLib.worker.CastingVMCall
import cn.xm1221.MieLib.worker.CastingVMWorker
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import net.minecraft.server.level.ServerLevel

/**
 * [CastingVMWorker] 的注册表。
 *
 * `executeInner` 的顺序是：
 * 1. 按注册顺序调用 [CastingVMWorker.isAfter] 为 false 的 [CastingVMWorker.beforeExecute]。
 *    这些回调可以通过 [CastingVMCall.cancel] 取消原方法。
 * 2. 若没有被取消，调用原来的 `executeInner`。
 * 3. 按注册顺序调用 [CastingVMWorker.isAfter] 为 true 的 [CastingVMWorker.afterExecute]。
 */
object CastingVMHook {
    private val workers = mutableListOf<CastingVMWorker>()

    @JvmStatic
    fun register(worker: CastingVMWorker) {
        synchronized(workers) {
            workers += worker
        }
    }

    @JvmStatic
    fun execute(
        iota: Iota,
        world: ServerLevel,
        cont: SpellContinuation,
        original: Operation<CastResult>,
    ): CastResult {
        val chain = synchronized(workers) { workers.toList() }
        val call = CastingVMCall()
        for (worker in chain) {
            if (!worker.isAfter) {
                worker.beforeExecute(iota, world, cont, call)
            }
        }

        var result = if (call.cancelled) {
            call.result ?: original.call(iota, world, cont)
        } else {
            original.call(iota, world, cont)
        }

        for (worker in chain) {
            if (worker.isAfter) {
                result = worker.afterExecute(iota, world, cont, result)
            }
        }
        return result
    }
}
