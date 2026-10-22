package cn.xm1221.MieLib.worker

abstract class DrawPatternWorker(override val priority: Int) : PriorityWorker {

    abstract fun ifEffect(result: DrawResult):Boolean
   abstract fun effect( drawResult: DrawResult): DrawResult

    class DrawResult(val drawLast:Boolean, val tail: Int, val head: Int, val flowIrregular: Float)

}
