package cn.xm1221.MieLib

import net.minecraft.resources.ResourceLocation
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import cn.xm1221.MieLib.config.MielibServerConfig
import cn.xm1221.MieLib.hexcli.HexCli
import cn.xm1221.MieLib.networking.MielibNetworking


object Mielib {
    const val MODID = "mielib"

    @JvmField
    val LOGGER: Logger = LogManager.getLogger(MODID)

    @JvmStatic
    fun id(path: String) = ResourceLocation.fromNamespaceAndPath(MODID, path)

    fun init() {
        MielibServerConfig.init()
        MieIotas.init()
        initRegistries(

        )
        MielibNetworking.init()
        HexCli.init()
    }

    fun initServer() {
        MielibServerConfig.initServer()
    }
}
