package cn.xm1221.MieLib.forge

import dev.architectury.platform.forge.EventBuses
import cn.xm1221.MieLib.Mielib
import cn.xm1221.MieLib.forge.datagen.ForgeMielibDatagen
import net.minecraftforge.fml.common.Mod
import thedarkcolour.kotlinforforge.forge.MOD_BUS

@Mod(Mielib.MODID)
class ForgeMielib {
    init {
        MOD_BUS.apply {
            EventBuses.registerModEventBus(Mielib.MODID, this)
            addListener(ForgeMielibClient::init)
            addListener(ForgeMielibDatagen::init)
            addListener(ForgeMielibServer::init)
        }
        Mielib.init()
    }
}
