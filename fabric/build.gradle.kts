plugins {
    id("mielib.platform")
}

architectury {
    fabric()
}

// HexCLI example addon (mod id `hexcli_example`, dev-only): demonstrates the env registration API.
// It is only compiled for the `runHexcliExampleServer` run and never ends up in any jar.
sourceSets {
    create("example") {
        compileClasspath += main.get().compileClasspath + main.get().output
        runtimeClasspath += main.get().runtimeClasspath + main.get().output
    }
}

fabricApi {
    configureDataGeneration {
        outputDirectory = file("src/generated/resources")
        modId = providers.gradleProperty("modId")
        strictValidation = true
        addToResources = false
    }
}

loom {
    // Let the Fabric dev runtime see MieLib and the example addon as separate mods.
    mods {
        create("mielib") {
            sourceSet(sourceSets.main.get())
            sourceSet(project(":common").sourceSets.main.get())
        }
        create("hexcli_example") {
            sourceSet(sourceSets["example"])
        }
    }

    runs {
        named("datagen") {
            property("mielib.apply-datagen-mixin", "true")
        }

        // HexCLI (off by default, enabled with -Dmielib.hexcli=true)
        register("hexcliServer") {
            server()
            name("HexCLI Server")
            runDir = "run/hexcli"
            programArg("nogui")
            property("mielib.hexcli", "true")
            ideConfigGenerated(false)
        }
        register("hexcliExampleServer") {
            server()
            name("HexCLI Example Server")
            runDir = "run/hexcli"
            programArg("nogui")
            property("mielib.hexcli", "true")
            source(sourceSets["example"])
            ideConfigGenerated(false)
        }
    }
}

tasks {
    // HexCLI dev runs stay registered, but HexCLI and the example addon are not compiled on 1.21.
    matching { it.name == "compileExampleJava" || it.name == "compileExampleKotlin" || it.name == "exampleClasses" }.configureEach {
        enabled = false
    }
}

mielibModDependencies {
    // expand versions in fabric.mod.json
    filesMatching.add("fabric.mod.json")

    // transform Gradle version ranges into a Fabric-compatible format
    anyVersion = "*"
    mapVersions {
        replace(",", " ")
        replace(Regex("""\s+"""), " ")
        replace(Regex("""\[(\S+)"""), ">=$1")
        replace(Regex("""(\S+)\]"""), "<=$1")
        replace(Regex("""\](\S+)"""), ">$1")
        replace(Regex("""(\S+)\["""), "<$1")
    }

    // CurseForge/Modrinth mod dependency metadata
    requires("architectury-api")
    requires("cloth-config")
    requires(curseforge = "hexcasting", modrinth = "hex-casting")
    requires("fabric-api")
    requires("fabric-language-kotlin")
    optional("modmenu")
}

dependencies {
    modApi(libs.fabric.api)
    modImplementation(libs.fabric.loader)

    modImplementation(libs.kotlin.fabric)

    modApi(libs.architectury.fabric) {
        // Fix for the "two fabric loaders" loading crash
        exclude(group = "net.fabricmc", module = "fabric-loader")
    }

    // Intermediary Hex jar; Loom remaps it. Same 0.12.0-devel-pre-64 bytes as the sibling 1.21 repos.
    modApi(files(rootProject.file("libs/hexcasting-fabric-1.21.1-0.12.0-devel-pre-64.jar")))
    modLocalRuntime(libs.paucal.fabric)
    modLocalRuntime(libs.patchouli.fabric)
    modLocalRuntime(libs.inline.fabric) { isTransitive = false }

    libs.mixinExtras.fabric.also {
        localRuntime(it)
        include(it)
        annotationProcessor(it)
    }

    modApi(libs.clothConfig.fabric) {
        exclude(group = "net.fabricmc.fabric-api")
    }
    modImplementation(libs.modMenu)
}

// HexCLI is not part of the 1.21 compile (Hex 0.12 dropped the NBT iota helpers it calls).
sourceSets.main {
    java {
        exclude("cn/xm1221/MieLib/hexcli/**")
    }
    kotlin {
        exclude("cn/xm1221/MieLib/hexcli/**")
    }
}
