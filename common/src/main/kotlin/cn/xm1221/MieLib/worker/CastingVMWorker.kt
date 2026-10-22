package cn.xm1221.MieLib.worker

import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.Iota
import net.minecraft.server.level.ServerLevel

/**
 * CastingVM hook 的最小执行单元。
 *
 * 只有一个入口 [execute]；Before / After 阶段由构造参数 [isAfter] 区分，
 * 执行顺序由 [priority] 决定（升序，数字越小越先）。
 *
 * Worker 内不要自己调用原方法：是否执行原 `executeInner` 由 `CastingVMHook` 决定。
 * 默认应返回传入的同一个 [CastingVMCall]（可变状态在 call 上）；若返回新实例，
 * Hook 会以返回值为准，但不鼓励无谓拷贝。
 */
abstract class CastingVMWorker(
    val isAfter: Boolean = false,
    override val priority: Int = 0,
) : PriorityWorker {
    abstract fun execute(
        iota: Iota,
        world: ServerLevel,
        cont: SpellContinuation,
        call: CastingVMCall,
        vm: CastingVM,
    ): CastingVMCall
}
