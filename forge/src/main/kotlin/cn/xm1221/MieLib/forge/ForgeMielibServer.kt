package cn.xm1221.MieLib.forge

import cn.xm1221.MieLib.Mielib
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent

object ForgeMielibServer {
    @Suppress("UNUSED_PARAMETER")
    fun init(event: FMLDedicatedServerSetupEvent) {
        Mielib.initServer()
    }
}
