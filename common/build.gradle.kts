plugins {
    id("mielib.minecraft")
}

architectury {
    common("fabric", "forge")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(kotlin("reflect"))

    // We depend on fabric loader here to use the fabric @Environment annotations and get the mixin dependencies
    // Do NOT use other classes from fabric loader
    modImplementation(libs.fabric.loader)
    modApi(libs.architectury)

    modApi(libs.hexcasting.common)

    modApi(libs.clothConfig.common)

    // HexCLI (part of MieLib) compiles against hexParse but it is NOT a hard dependency of MieLib:
    // compile only, and the code that touches hexParse classes only runs when HexCLI is enabled.
    modCompileOnly(libs.hexparse.fabric) { isTransitive = false }

    libs.mixinExtras.common.also {
        implementation(it)
        annotationProcessor(it)
    }
}

// /genHexBook dumps a hexdoc project on the server; ship the companion
// (doc/genhexbook) inside the jar so no checkout is needed at runtime.
tasks.processResources {
    from(rootProject.file("doc/genhexbook")) {
        into("genhexbook")
    }
}
