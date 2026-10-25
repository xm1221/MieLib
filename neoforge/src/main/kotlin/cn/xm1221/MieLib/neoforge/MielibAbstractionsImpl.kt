@file:JvmName("MielibAbstractionsImpl")

package cn.xm1221.MieLib.neoforge

import cn.xm1221.MieLib.registry.MielibRegistrar
import net.neoforged.neoforge.registries.RegisterEvent
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

fun <T : Any> initRegistry(registrar: MielibRegistrar<T>) {
    MOD_BUS.addListener { event: RegisterEvent ->
        event.register(registrar.registryKey) { helper ->
            registrar.init { id, value -> helper.register(id, value) }
        }
    }
}
