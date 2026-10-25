import mielib.utils.extractNestedJar

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

    modApi(libs.hexcasting.fabric) {
        // If not excluded here, calls a nonexistent method and crashes the dev client
        exclude(module = "phosphor")
    }
    // Hex Casting dependencies
    // we use modLocalRuntime to add these to the development runtime, but not at compile time or for consumers of this project
    modLocalRuntime(libs.paucal.fabric)
    modLocalRuntime(libs.patchouli.fabric)
    modLocalRuntime(libs.cardinalComponents)
    modLocalRuntime(libs.serializationHooks)
    modLocalRuntime(libs.trinkets)
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

    // HexCLI dev runtime: hexParse is optional for MieLib but required when HexCLI is enabled.
    // Its yclib dependency is jar-in-jar, which Loom does not load in dev; extract it separately.
    modLocalRuntime(libs.hexparse.fabric)
    modLocalRuntime(files(extractNestedJar(
        libs.hexparse.fabric.get().toString(),
        Regex("""META-INF/jars/yclib-.*\.jar"""),
        "yclib-fabric.jar",
    )))
}
