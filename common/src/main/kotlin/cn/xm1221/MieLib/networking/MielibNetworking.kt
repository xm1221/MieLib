package cn.xm1221.MieLib.networking

import dev.architectury.networking.NetworkChannel
import cn.xm1221.MieLib.Mielib
import cn.xm1221.MieLib.networking.msg.MielibMessageCompanion

object MielibNetworking {
    val CHANNEL: NetworkChannel = NetworkChannel.create(Mielib.id("networking_channel"))

    fun init() {
        for (subclass in MielibMessageCompanion::class.sealedSubclasses) {
            subclass.objectInstance?.register(CHANNEL)
        }
    }
}
