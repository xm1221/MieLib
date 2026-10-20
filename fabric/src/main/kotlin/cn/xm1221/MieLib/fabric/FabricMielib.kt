package cn.xm1221.MieLib.fabric

import cn.xm1221.MieLib.Mielib
import net.fabricmc.api.ModInitializer

object FabricMielib : ModInitializer {
    override fun onInitialize() {
        Mielib.init()
    }
}
