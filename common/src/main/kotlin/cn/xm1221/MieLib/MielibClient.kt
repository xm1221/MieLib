package cn.xm1221.MieLib

import cn.xm1221.MieLib.config.MielibClientConfig
import me.shedaniel.autoconfig.AutoConfig
import net.minecraft.client.gui.screens.Screen

object MielibClient {
    fun init() {
        MielibClientConfig.init()
    }

    fun getConfigScreen(parent: Screen): Screen {
        return AutoConfig.getConfigScreen(MielibClientConfig.GlobalConfig::class.java, parent).get()
    }
}
