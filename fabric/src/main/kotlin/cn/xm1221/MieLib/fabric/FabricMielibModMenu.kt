package cn.xm1221.MieLib.fabric

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import cn.xm1221.MieLib.MielibClient

object FabricMielibModMenu : ModMenuApi {
    override fun getModConfigScreenFactory() = ConfigScreenFactory(MielibClient::getConfigScreen)
}
