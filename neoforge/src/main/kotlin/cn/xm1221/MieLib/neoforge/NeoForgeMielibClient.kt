package cn.xm1221.MieLib.neoforge

import cn.xm1221.MieLib.MielibClient
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import thedarkcolour.kotlinforforge.neoforge.forge.LOADING_CONTEXT

object NeoForgeMielibClient {
    @Suppress("UNUSED_PARAMETER")
    fun init(event: FMLClientSetupEvent) {
        MielibClient.init()
        LOADING_CONTEXT.registerExtensionPoint(IConfigScreenFactory::class.java) {
            IConfigScreenFactory { _, parent -> MielibClient.getConfigScreen(parent) }
        }
    }
}
