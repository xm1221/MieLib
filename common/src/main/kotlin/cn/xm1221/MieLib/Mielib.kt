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
        MieIotas.init()
        initRegistries(

        )
        MielibNetworking.init()
        // HexCLI 默认关闭；开关只看系统属性 / 服务端配置（见 HEXCLI.md）
        cn.xm1221.MieLib.hexcli.HexCli.init()
    }

    fun initServer() {
        MielibServerConfig.initServer()
    }
}
