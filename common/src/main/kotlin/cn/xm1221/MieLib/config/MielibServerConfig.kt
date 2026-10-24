package cn.xm1221.MieLib.config

import dev.architectury.event.events.common.PlayerEvent
import me.shedaniel.autoconfig.AutoConfig
import me.shedaniel.autoconfig.ConfigData
import me.shedaniel.autoconfig.ConfigHolder
import me.shedaniel.autoconfig.annotation.Config
import me.shedaniel.autoconfig.annotation.ConfigEntry.Category
import me.shedaniel.autoconfig.annotation.ConfigEntry.Gui.Tooltip
import me.shedaniel.autoconfig.annotation.ConfigEntry.Gui.TransitiveObject
import me.shedaniel.autoconfig.serializer.PartitioningSerializer
import me.shedaniel.autoconfig.serializer.PartitioningSerializer.GlobalData
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.InteractionResult
import cn.xm1221.MieLib.Mielib
import cn.xm1221.MieLib.networking.msg.MsgSyncConfigS2C

object MielibServerConfig {
    @JvmStatic
    lateinit var holder: ConfigHolder<GlobalConfig>

    @JvmStatic
    val config get() = syncedServerConfig ?: holder.config.server

    // only used on the client
    private var syncedServerConfig: ServerConfig? = null

    fun init() {
        holder = AutoConfig.register(
            GlobalConfig::class.java,
            PartitioningSerializer.wrap(::Toml4jConfigSerializer),
        )

        // prevent this holder from saving the server config; that happens in the client config gui
        holder.registerSaveListener { _, _ -> InteractionResult.FAIL }
    }

    fun initServer() {
        PlayerEvent.PLAYER_JOIN.register { player ->
            MsgSyncConfigS2C(holder.config.server).sendToPlayer(player)
        }
    }

    fun onSyncConfig(serverConfig: ServerConfig?) {
        syncedServerConfig = serverConfig
    }

    @Config(name = Mielib.MODID)
    class GlobalConfig(
        @Category("server")
        @TransitiveObject
        val server: ServerConfig = ServerConfig(),
    ) : GlobalData()

    @Config(name = "server")
    class ServerConfig : ConfigData {
        @Tooltip
        var dummyServerConfigOption: Int = 64
            private set

        /**
         * 启用 HexCLI（无头服务器上的 Hex 命令行，见 HEXCLI.md）。默认关闭。
         * 启动参数 `-Dmielib.hexcli=true|false` 优先于本项。服务端专用，不参与网络同步。
         */
        @Tooltip
        var enableHexCli: Boolean = false
            private set

        /**
         * HexCLI 远程客户端的 TCP 端口（只绑定回环地址）。0 = 每次启动自动挑一个空闲端口。
         * 启动参数 `-Dmielib.hexcli.port=<n>` 优先于本项。服务端专用，不参与网络同步。
         */
        @Tooltip
        var hexCliPort: Int = 0
            private set

        fun encode(buf: FriendlyByteBuf) {
            buf.writeInt(dummyServerConfigOption)
        }

        fun decode(buf: FriendlyByteBuf): ServerConfig {
            dummyServerConfigOption = buf.readInt()
            return this
        }
    }
}
