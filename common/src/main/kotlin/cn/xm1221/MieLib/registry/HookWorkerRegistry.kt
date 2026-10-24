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
 * 同优先级按 id 字符串稳定排序。排序结果在注册变化前会被缓存（[ordered]），
 * 因此渲染热路径上不会重复排序。
 */
abstract class HookWorkerRegistry<W : PriorityWorker>(private val hookName: String) {
    private val workers = ConcurrentHashMap<ResourceLocation, W>()

    @Volatile
    private var cachedOrder: List<W>? = null

    /**
     * 注册一个 worker。同 [id] 已存在时覆盖旧注册，并打警告日志。
     * 注册会使排序缓存失效。
     */
    protected fun put(id: ResourceLocation, worker: W) {
        val previous = workers.put(id, worker)
        cachedOrder = null
        if (previous != null) {
            Mielib.LOGGER.warn(
                "$hookName: overwriting worker id {} (was {}, now {})",
                id,
                previous::class.java.name,
                worker::class.java.name,
            )
        }
    }

    /** 快照所有 worker，按 [PriorityWorker.priority] 升序排序，同优先级按 id 字符串稳定排序（带缓存）。 */
    fun ordered(): List<W> {
        cachedOrder?.let { return it }
        val sorted = workers.entries
            .sortedWith(compareBy({ it.value.priority }, { it.key.toString() }))
            .map { it.value }
        cachedOrder = sorted
        return sorted
    }

    /** 当前是否没有注册任何 worker。 */
    fun isEmpty(): Boolean = workers.isEmpty()

    /** 按 [ordered] 的顺序依次折叠所有 worker。 */
    fun <T> fold(initial: T, step: (W, T) -> T): T {
        var acc = initial
        for (worker in ordered()) {
            acc = step(worker, acc)
        }
        return acc
    }
}
