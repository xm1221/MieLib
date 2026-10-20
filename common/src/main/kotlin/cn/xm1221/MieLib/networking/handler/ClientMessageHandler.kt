package cn.xm1221.MieLib.networking.handler

import dev.architectury.networking.NetworkManager.PacketContext
import cn.xm1221.MieLib.config.MielibServerConfig
import cn.xm1221.MieLib.networking.msg.*

fun MielibMessageS2C.applyOnClient(ctx: PacketContext) = ctx.queue {
    when (this) {
        is MsgSyncConfigS2C -> {
            MielibServerConfig.onSyncConfig(serverConfig)
        }

        // add more client-side message handlers here
    }
}
