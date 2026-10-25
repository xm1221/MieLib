package cn.xm1221.MieLib.networking.msg

import dev.architectury.networking.NetworkManager.PacketContext
import cn.xm1221.MieLib.Mielib
import cn.xm1221.MieLib.networking.MielibNetworking
import cn.xm1221.MieLib.networking.handler.applyOnClient
import cn.xm1221.MieLib.networking.handler.applyOnServer
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.level.ServerPlayer
import java.util.function.Supplier

sealed interface MielibMessage

sealed interface MielibMessageC2S : MielibMessage {
    @Environment(EnvType.CLIENT)
    fun sendToServer() {
        MielibNetworking.sendToServer(this)
    }
}

sealed interface MielibMessageS2C : MielibMessage {
    fun sendToPlayer(player: ServerPlayer) {
        MielibNetworking.sendToPlayer(player, this)
    }

    fun sendToPlayers(players: Iterable<ServerPlayer>) {
        MielibNetworking.sendToPlayers(players, this)
    }
}

sealed interface MielibMessageCompanion<T : MielibMessage> {
    val type: Class<T>

    fun decode(buf: FriendlyByteBuf): T

    fun T.encode(buf: FriendlyByteBuf)

    fun apply(msg: T, supplier: Supplier<PacketContext>) {
        val ctx = supplier.get()
        when (ctx.env) {
            EnvType.SERVER, null -> {
                Mielib.LOGGER.debug("Server received packet from {}: {}", ctx.player.name.string, this)
                when (msg) {
                    is MielibMessageC2S -> msg.applyOnServer(ctx)
                    else -> Mielib.LOGGER.warn("Message not handled on server: {}", msg::class)
                }
            }
            EnvType.CLIENT -> {
                Mielib.LOGGER.debug("Client received packet: {}", this)
                when (msg) {
                    is MielibMessageS2C -> msg.applyOnClient(ctx)
                    else -> Mielib.LOGGER.warn("Message not handled on client: {}", msg::class)
                }
            }
        }
    }

    fun register() {
        when {
            MielibMessageS2C::class.java.isAssignableFrom(type) ->
                MielibNetworking.registerS2C(type, ::decode, { msg, buf -> msg.encode(buf) }) { msg, supplier ->
                    apply(msg, supplier)
                }
            MielibMessageC2S::class.java.isAssignableFrom(type) ->
                MielibNetworking.registerC2S(type, ::decode, { msg, buf -> msg.encode(buf) }) { msg, supplier ->
                    apply(msg, supplier)
                }
            else -> Mielib.LOGGER.warn("Message has no side and was not registered: {}", type.name)
        }
    }
}
