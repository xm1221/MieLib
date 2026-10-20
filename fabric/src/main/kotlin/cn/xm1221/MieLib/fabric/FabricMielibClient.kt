package cn.xm1221.MieLib.fabric

import cn.xm1221.MieLib.MielibClient
import net.fabricmc.api.ClientModInitializer

object FabricMielibClient : ClientModInitializer {
    override fun onInitializeClient() {
        MielibClient.init()
    }
}
