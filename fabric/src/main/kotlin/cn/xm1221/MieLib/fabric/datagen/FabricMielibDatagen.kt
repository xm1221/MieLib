package cn.xm1221.MieLib.fabric.datagen

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator

object FabricMielibDatagen : DataGeneratorEntrypoint {
    override fun onInitializeDataGenerator(gen: FabricDataGenerator) {
        // Common datagen runs through Forge (see forge/build.gradle.kts).
        // Keep this entrypoint so fabric.mod.json / runDatagen resolve.
        gen.createPack()
    }
}
