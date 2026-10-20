package cn.xm1221.MieLib.fabric

import cn.xm1221.MieLib.Mielib
import net.fabricmc.api.DedicatedServerModInitializer

object FabricMielibServer : DedicatedServerModInitializer {
    override fun onInitializeServer() {
        Mielib.initServer()
    }
}
