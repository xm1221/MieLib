package cn.xm1221.MieLib

import net.minecraft.resources.ResourceLocation
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import cn.xm1221.MieLib.config.MielibServerConfig
import cn.xm1221.MieLib.networking.MielibNetworking


object Mielib {
    const val MODID = "mielib"

    @JvmField
    val LOGGER: Logger = LogManager.getLogger(MODID)

    @JvmStatic
    fun id(path: String) = ResourceLocation(MODID, path)

    fun init() {
        MielibServerConfig.init()
        initRegistries(

        )
        MielibNetworking.init()
    }

    fun initServer() {
        MielibServerConfig.initServer()
    }
}
