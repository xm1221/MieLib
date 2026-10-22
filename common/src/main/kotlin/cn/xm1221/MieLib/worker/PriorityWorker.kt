package cn.xm1221.MieLib.worker

/** Shared contract for hook workers: lower priority runs earlier. */
interface PriorityWorker {
    val priority: Int
}
