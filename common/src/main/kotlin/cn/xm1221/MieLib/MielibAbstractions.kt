@file:JvmName("MielibAbstractions")

package cn.xm1221.MieLib

import dev.architectury.injectables.annotations.ExpectPlatform
import cn.xm1221.MieLib.registry.MielibRegistrar

fun initRegistries(vararg registries: MielibRegistrar<*>) {
    for (registry in registries) {
        initRegistry(registry)
    }
}

@ExpectPlatform
fun <T : Any> initRegistry(registrar: MielibRegistrar<T>) {
    throw AssertionError()
}
