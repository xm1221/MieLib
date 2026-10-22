package cn.xm1221.MieLib.registry

import cn.xm1221.MieLib.Mielib
import cn.xm1221.MieLib.worker.PriorityWorker
import net.minecraft.resources.ResourceLocation
import java.util.concurrent.ConcurrentHashMap

/**
 * [PriorityWorker] 的通用注册表。
 *
 * 注册键为 [ResourceLocation] id。同 id **允许覆盖**：后注册的 worker 替换先前的，
 * 并打一条 [Mielib.LOGGER] 警告，便于排查意外覆盖。
 *
 * 执行顺序由 [PriorityWorker.priority] 决定（升序，数字越小越先），
 * 同优先级按 id 字符串稳定排序。
 */
abstract class HookWorkerRegistry<W : PriorityWorker>(private val hookName: String) {
    private val workers = ConcurrentHashMap<ResourceLocation, W>()

    /**
     * 注册一个 worker。同 [id] 已存在时覆盖旧注册，并打警告日志。
     */
    protected fun put(id: ResourceLocation, worker: W) {
        val previous = workers.put(id, worker)
        if (previous != null) {
            Mielib.LOGGER.warn(
                "$hookName: overwriting worker id {} (was {}, now {})",
                id,
                previous::class.java.name,
                worker::class.java.name,
            )
        }
    }

    /** 快照所有 worker，按 [PriorityWorker.priority] 升序排序，同优先级按 id 字符串稳定排序。 */
    fun ordered(): List<W> =
        workers.entries
            .sortedWith(compareBy({ it.value.priority }, { it.key.toString() }))
            .map { it.value }

    /** 按 [ordered] 的顺序依次折叠所有 worker。 */
    fun <T> fold(initial: T, step: (W, T) -> T): T {
        var acc = initial
        for (worker in ordered()) {
            acc = step(worker, acc)
        }
        return acc
    }
}
