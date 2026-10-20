@file:JvmName("MielibAbstractionsImpl")

package cn.xm1221.MieLib.fabric

import cn.xm1221.MieLib.registry.MielibRegistrar
import net.minecraft.core.Registry

fun <T : Any> initRegistry(registrar: MielibRegistrar<T>) {
    val registry = registrar.registry
    registrar.init { id, value -> Registry.register(registry, id, value) }
}
