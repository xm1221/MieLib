package cn.xm1221.MieLib.registry

import at.petrak.hexcasting.api.casting.eval.CastResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.Iota
import cn.xm1221.MieLib.Mielib
import cn.xm1221.MieLib.worker.CastingVMCall
import cn.xm1221.MieLib.worker.CastingVMWorker
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import java.util.concurrent.ConcurrentHashMap

/**
 * [CastingVMWorker] 的注册表。
 *
 * 全进程只有 `CastingVMMixin` 一处 `@WrapMethod` 挂在
 * `CastingVM.executeInner`；其它模组只能通过 [register] 扩展，不能再次 wrap 同一方法。
 *
 * 执行流水线：
 * 1. 快照所有 worker，按 [CastingVMWorker.priority] 升序排序，同优先级按 id 字符串稳定排序。
 * 2. 依次执行 Before worker（[CastingVMWorker.isAfter] == false），并把 `vm` 实例传给它们。
 *    某个 Before 调用 [CastingVMCall.cancel] 后停止后续 Before，此时必须已有 `result`。
 * 3. 若未取消，调用一次原 `executeInner`；若已取消，则绝不调用原方法。
 * 4. 即使 Before 取消了，After worker（isAfter == true）仍会执行，便于清理 / 日志。
 *    进入 After 阶段时会清空 `cancelled`（保留 `result`），因此 Before 的取消不会截断 After 链；
 *    After 中的 `cancel` 表示采用当前 `result` 并停止后续 After，同样不会再次调用原方法。
 * 5. 返回 `result`；缺失则抛出 [IllegalStateException]。
 *
 * 注册键为 [ResourceLocation] id。同 id **允许覆盖**：后注册的 worker 替换先前的，
 * 并打一条 [Mielib.LOGGER] 警告，便于排查意外覆盖。
 */
object CastingVMHook {
    private val workers = ConcurrentHashMap<ResourceLocation, CastingVMWorker>()

    /**
     * 注册一个 worker。同 [id] 已存在时覆盖旧注册，并打警告日志。
     */
    @JvmStatic
    fun register(id: ResourceLocation, worker: CastingVMWorker) {
        val previous = workers.put(id, worker)
        if (previous != null) {
            Mielib.LOGGER.warn(
                "CastingVMHook: overwriting worker id {} (was {}, now {})",
                id,
                previous::class.java.name,
                worker::class.java.name,
            )
        }
    }

    /** 供 mixin 调用；语义见类 KDoc。 */
    @JvmStatic
    fun execute(
        iota: Iota,
        world: ServerLevel,
        cont: SpellContinuation,
        original: Operation<CastResult>,
        vm: CastingVM,
    ): CastResult {
        val chain = workers.entries
            .sortedWith(compareBy({ it.value.priority }, { it.key.toString() }))
            .map { it.value }

        var call = CastingVMCall()

        // —— Before ——
        for (worker in chain) {
            if (worker.isAfter) continue
            call = worker.execute(iota, world, cont, call, vm)
            if (call.cancelled) break
        }

        // —— Original：整个流水线最多调用一次 ——
        if (!call.cancelled) {
            call.setResult(original.call(iota, world, cont))
        } else {
            requireNotNull(call.result) {
                "CastingVMHook: before-cancel without result; use CastingVMCall.cancel(result)"
            }
        }

        // —— After：即使 Before 取消了也要跑 ——
        // 清空 cancelled（保留 result），否则 Before 的取消会在第一个 After 后截断整条 After 链。
        call.enterAfterPhase()

        for (worker in chain) {
            if (!worker.isAfter) continue
            call = worker.execute(iota, world, cont, call, vm)
            if (call.cancelled) break
        }

        return requireNotNull(call.result) { "CastingVMHook: missing CastResult" }
    }
}
