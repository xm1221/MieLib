plugins {
    id("mielib.minecraft")
}

architectury {
    common("fabric", "neoforge")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(kotlin("reflect"))

    // We depend on fabric loader here to use the fabric @Environment annotations and get the mixin dependencies
    // Do NOT use other classes from fabric loader
    modImplementation(libs.fabric.loader)
    modApi(libs.architectury)

    // Fabric Hex jar is intermediary. Loom remaps it for the common compile.
    // There is no hexcasting-*-1.21.1 artifact on maven.blamejared.com.
    modApi(files(rootProject.file("libs/hexcasting-fabric-1.21.1-0.12.0-devel-pre-64.jar")))

    modCompileOnly(libs.paucal.common)

    modApi(libs.clothConfig.common)

    libs.mixinExtras.common.also {
        implementation(it)
        annotationProcessor(it)
    }
}

// HexCLI still targets Hex 0.11 NBT iota APIs (IotaType.deserialize / CastingImage.getUserData).
// Those were removed in Hex 0.12. Keep the sources in-tree, but do not compile them on 1.21.
sourceSets.main {
    java {
        exclude("cn/xm1221/MieLib/hexcli/**")
        exclude("cn/xm1221/MieLib/mixin/hexcli/**")
    }
    kotlin {
        exclude("cn/xm1221/MieLib/hexcli/**")
        exclude("cn/xm1221/MieLib/mixin/hexcli/**")
    }
}
