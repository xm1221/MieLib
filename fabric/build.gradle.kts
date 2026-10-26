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
    // Console input for the HexCLI dev servers, plus EULA / offline-mode server.properties.
    listOf("runHexcliServer", "runHexcliExampleServer").forEach { name ->
        named<JavaExec>(name) {
            standardInput = System.`in`
            doFirst {
                val run = file("run/hexcli")
                run.mkdirs()
                run.resolve("eula.txt").takeIf { !it.exists() }?.writeText("eula=true\n")
                run.resolve("server.properties").takeIf { !it.exists() }?.writeText(
                    "online-mode=false\nspawn-protection=0\nlevel-name=hexcli-world\nmotd=HexCLI\nenable-rcon=false\n"
                )
            }
        }
    }

    // `:fabric:build` also compiles the example source set (it is not put into any jar).
    named("build") { dependsOn("exampleClasses") }
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
    optional("hexparse")
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
    // Hex Casting 0.12 declares cardinal-components as hard Fabric deps; the dev runtime needs them
    // or the server refuses to start. Nothing in MieLib compiles against them.
    modLocalRuntime(libs.cardinalComponents.base)
    modLocalRuntime(libs.cardinalComponents.entity)
    modLocalRuntime(libs.cardinalComponents.item)
    modLocalRuntime(libs.cardinalComponents.block)
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
    // Optional at runtime: HexCLI only reaches HexParse by reflection, so it is a dev-runtime
    // convenience (and declared as `suggests`/optional in the metadata) rather than a dependency.
    modLocalRuntime(files(rootProject.file("libs/hexParse-fabric-1.21.1-1.12.0.pre64.jar")))
    // Loom's remapper drops the `jars` (jar-in-jar) entry from hexParse's fabric.mod.json, so the
    // nested YCLib it needs never loads. Add it explicitly; the file is that same nested jar.
    modLocalRuntime(files(rootProject.file("libs/yclib-1.21.1-0.2.jar")))
}
