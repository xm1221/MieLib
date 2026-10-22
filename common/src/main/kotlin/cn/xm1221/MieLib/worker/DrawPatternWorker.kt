package cn.xm1221.MieLib.worker

import org.apache.commons.lang3.function.ToBooleanBiFunction

abstract  class DrawPatternWorker(val priority : Int)  {

    abstract fun ifEffect(result: DrawResult):Boolean
   abstract fun effect( drawResult: DrawResult): DrawResult

    class DrawResult(val drawlast:Boolean, val tail: Int, val head: Int, val flowIrregular: Float)

}