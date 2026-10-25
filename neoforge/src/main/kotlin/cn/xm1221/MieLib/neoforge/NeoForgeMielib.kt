package cn.xm1221.MieLib.neoforge

import cn.xm1221.MieLib.Mielib
import cn.xm1221.MieLib.neoforge.datagen.NeoForgeMielibDatagen
import net.neoforged.fml.common.Mod
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@Mod(Mielib.MODID)
class NeoForgeMielib {
    init {
        // Architectury 13 on NeoForge reads the bus from ModContainer; there is no
        // dev.architectury.platform.forge.EventBuses on this loader.
        MOD_BUS.apply {
            addListener(NeoForgeMielibClient::init)
            addListener(NeoForgeMielibDatagen::init)
            addListener(NeoForgeMielibServer::init)
        }
        Mielib.init()
    }
}
