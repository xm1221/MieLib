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

    // HexParse is a soft dependency: HexCLI reaches it by reflection, so this only has to be on
    // the compile classpath for the sources that mention it. The 1.21.1 build is not on any maven
    // we can reach, so it lives in libs/.
    modCompileOnly(files(rootProject.file("libs/hexParse-fabric-1.21.1-1.12.0.pre64.jar")))

    modApi(libs.clothConfig.common)

    libs.mixinExtras.common.also {
        implementation(it)
        annotationProcessor(it)
    }
}
