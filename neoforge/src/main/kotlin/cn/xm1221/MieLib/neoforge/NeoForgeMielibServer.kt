package cn.xm1221.MieLib.neoforge

import cn.xm1221.MieLib.Mielib
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent

object NeoForgeMielibServer {
    @Suppress("UNUSED_PARAMETER")
    fun init(event: FMLDedicatedServerSetupEvent) {
        Mielib.initServer()
    }
}
