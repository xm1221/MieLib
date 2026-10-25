package cn.xm1221.MieLib.networking

import dev.architectury.networking.NetworkManager
import dev.architectury.platform.Platform
import dev.architectury.utils.Env
import dev.architectury.utils.GameInstance
import cn.xm1221.MieLib.Mielib
import cn.xm1221.MieLib.networking.msg.MielibMessage
import cn.xm1221.MieLib.networking.msg.MielibMessageC2S
import cn.xm1221.MieLib.networking.msg.MielibMessageCompanion
import cn.xm1221.MieLib.networking.msg.MielibMessageS2C
import io.netty.buffer.Unpooled
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.core.RegistryAccess
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.function.Supplier

object MielibNetworking {
    private val s2c = HashMap<Class<*>, Outbound>()
    private val c2s = HashMap<Class<*>, Outbound>()

    fun init() {
        for (subclass in MielibMessageCompanion::class.sealedSubclasses) {
            subclass.objectInstance?.register()
        }
    }

    /**
     * Same id [dev.architectury.networking.NetworkChannel] derived from the message class.
     * Only one direction is registered for it.
     */
    private fun packetId(type: Class<*>): ResourceLocation {
        val suffix = UUID.nameUUIDFromBytes(type.name.toByteArray(StandardCharsets.UTF_8))
            .toString()
            .replace("-", "")
        return Mielib.id("networking_channel/$suffix")
    }

    @Suppress("DEPRECATION")
    fun <T : MielibMessage> registerS2C(
        type: Class<T>,
        decode: (FriendlyByteBuf) -> T,
        encode: (T, FriendlyByteBuf) -> Unit,
        apply: (T, Supplier<NetworkManager.PacketContext>) -> Unit,
    ) {
        val id = packetId(type)
        s2c[type] = Outbound(id) { msg, buf ->
            @Suppress("UNCHECKED_CAST")
            encode(msg as T, buf)
        }
        // NeoForge 1.21 payload ids are unique across directions, so this must not also
        // be registered C2S. Fabric strips the S2C receiver on a dedicated server
        // (AbstractMethodError); the server only needs the payload type in order to send.
        if (Platform.getEnvironment() == Env.CLIENT) {
            val receiver = NetworkManager.NetworkReceiver<RegistryFriendlyByteBuf> { buf, context ->
                apply(decode(buf)) { context }
            }
            NetworkManager.registerReceiver(NetworkManager.s2c(), id, receiver)
        } else {
            NetworkManager.registerS2CPayloadType(id)
        }
    }

    @Suppress("DEPRECATION")
    fun <T : MielibMessage> registerC2S(
        type: Class<T>,
        decode: (FriendlyByteBuf) -> T,
        encode: (T, FriendlyByteBuf) -> Unit,
        apply: (T, Supplier<NetworkManager.PacketContext>) -> Unit,
    ) {
        val id = packetId(type)
        c2s[type] = Outbound(id) { msg, buf ->
            @Suppress("UNCHECKED_CAST")
            encode(msg as T, buf)
        }
        val receiver = NetworkManager.NetworkReceiver<RegistryFriendlyByteBuf> { buf, context ->
            apply(decode(buf)) { context }
        }
        NetworkManager.registerReceiver(NetworkManager.c2s(), id, receiver)
    }

    fun sendToPlayer(player: ServerPlayer, message: MielibMessageS2C) {
        sendS2C(player.registryAccess(), listOf(player), message)
    }

    fun sendToPlayers(players: Iterable<ServerPlayer>, message: MielibMessageS2C) {
        val first = players.firstOrNull() ?: return
        sendS2C(first.registryAccess(), players, message)
    }

    @Environment(EnvType.CLIENT)
    @Suppress("DEPRECATION")
    fun sendToServer(message: MielibMessageC2S) {
        val connection = GameInstance.getClient().connection
            ?: throw IllegalStateException("Unable to send packet to the server while not in game!")
        val outbound = c2s[message.javaClass]
            ?: error("Unregistered MieLib C2S message: ${message.javaClass.name}")
        withBuf(connection.registryAccess()) { buf ->
            outbound.encode(message, buf)
            NetworkManager.sendToServer(outbound.id, buf)
        }
    }

    @Suppress("DEPRECATION")
    private fun sendS2C(access: RegistryAccess, players: Iterable<ServerPlayer>, message: MielibMessageS2C) {
        val outbound = s2c[message.javaClass]
            ?: error("Unregistered MieLib S2C message: ${message.javaClass.name}")
        withBuf(access) { buf ->
            outbound.encode(message, buf)
            NetworkManager.sendToPlayers(players, outbound.id, buf)
        }
    }

    private inline fun withBuf(access: RegistryAccess, block: (RegistryFriendlyByteBuf) -> Unit) {
        val buf = RegistryFriendlyByteBuf(Unpooled.buffer(), access)
        try {
            block(buf)
        } finally {
            buf.release()
        }
    }

    private class Outbound(val id: ResourceLocation, val encode: (Any, FriendlyByteBuf) -> Unit)
}
