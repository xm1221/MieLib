package cn.xm1221.MieLib

import at.petrak.hexcasting.api.casting.iota.GarbageIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import dev.architectury.event.events.common.LifecycleEvent
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel

/**
 * Iota 反序列化辅助（**仅服务端可用**）。
 *
 * Hex 0.12 用 [IotaType.TYPED_CODEC]（dispatch 字段 `type`）取代了
 * `IotaType.deserialize(CompoundTag, ServerLevel)`。传入的 [Tag] 必须已经是那份 codec 的 NBT。
 *
 * - 优先传入 [level]（例如施法环境里用 `env.world` 转 [ServerLevel]）。
 * - [level] 为空时回退到已记录 [server] 的 overworld。
 * - 没有可用世界或反序列化失败时返回 [GarbageIota]。
 * - **不要在客户端调用**。
 */
object MieIotas {
    @Volatile
    @JvmStatic
    var server: MinecraftServer? = null
        private set

    fun init() {
        LifecycleEvent.SERVER_STARTED.register { server = it }
        LifecycleEvent.SERVER_STOPPED.register { server = null }
    }

    @JvmStatic
    fun tryDeserialize(tag: Tag, level: ServerLevel?): Iota {
        val lvl = level ?: server?.overworld() ?: return GarbageIota()
        val ops = lvl.registryAccess().createSerializationContext(NbtOps.INSTANCE)
        return IotaType.TYPED_CODEC.parse(ops, tag)
            .resultOrPartial { msg -> Mielib.LOGGER.warn("MieIotas: failed to deserialize iota: {}", msg) }
            .orElseGet { GarbageIota() }
    }
}
