package cn.xm1221.MieLib

import at.petrak.hexcasting.api.casting.iota.GarbageIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes
import dev.architectury.event.events.common.LifecycleEvent
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel

/**
 * Iota 反序列化辅助（**仅服务端可用**）。
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
    fun tryDeserialize(full: CompoundTag, level: ServerLevel?): Iota {
        val lvl = level ?: server?.overworld() ?: return GarbageIota()
        return try {
            IotaType.deserialize(full, lvl)
        } catch (e: Exception) {
            Mielib.LOGGER.warn("MieIotas: failed to deserialize iota", e)
            GarbageIota()
        }
    }

    @JvmStatic
    fun tryDeserialize(type: IotaType<*>, data: Tag, level: ServerLevel?): Iota {
        val key = HexIotaTypes.REGISTRY.getKey(type) ?: return GarbageIota()
        val full = CompoundTag()
        full.putString(HexIotaTypes.KEY_TYPE, key.toString())
        full.put(HexIotaTypes.KEY_DATA, data)
        return tryDeserialize(full, level)
    }
}
