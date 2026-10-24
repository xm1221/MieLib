import mielib.utils.extractNestedJar

plugins {
    id("mielib.platform")
}

val modId: String by project

architectury {
    forge()
}

loom {
    forge {
        convertAccessWideners = true
        extraAccessWideners.add(loom.accessWidenerPath.get().asFile.name)

        mixinConfig(
            "mielib-common.mixins.json",
            "mielib-forge.mixins.json",
        )
    }

    runs {
        for ((platform, outputProject) in arrayOf(
            // we're using forge to do the common datagen because fabric's datagen kind of sucks
            "common" to project(":common"),
            "forge" to project,
        )) {
            register("${platform}Datagen") {
                data()
                programArgs(
                    "--mod", modId,
                    "--all",
                    "--output", outputProject.file("src/generated/resources").absolutePath,
                    "--existing", file("src/main/resources").absolutePath,
                    "--existing", project(":common").file("src/main/resources").absolutePath,
                    "--existing-mod", "hexcasting",
                )
                property("mielib.apply-datagen-mixin", "true")
                property("mielib.$platform-datagen", "true")
            }
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
    }
}

mielibModDependencies {
    // expand versions in mods.toml
    filesMatching.add("META-INF/mods.toml")

    // transform Gradle version ranges into a Forge-compatible format
    anyVersion = ""
    mapVersions {
        replace(Regex("""\](\S+)"""), "($1")
        replace(Regex("""(\S+)\["""), "$1)")
    }

    // CurseForge/Modrinth mod dependency metadata
    requires("architectury-api")
    requires("cloth-config")
    requires(curseforge = "hexcasting", modrinth = "hex-casting")
    requires("kotlin-for-forge")
    optional("hexparse")
}

dependencies {
    forge(libs.forge)
    modApi(libs.architectury.forge)

    implementation(libs.kotlin.forge)

    modApi(libs.hexcasting.forge) { isTransitive = false }
    // Hex Casting dependencies
    // we use modLocalRuntime to add these to the development runtime, but not at compile time or for consumers of this project
    // but we use PAUCAL for datagen, so that's part of the actual implementation
    modImplementation(libs.paucal.forge)
    modLocalRuntime(libs.patchouli.forge)
    modLocalRuntime(libs.caelus)
    modLocalRuntime(libs.inline.forge) { isTransitive = false }

    modApi(libs.clothConfig.forge)

    libs.mixinExtras.common.also {
        compileOnly(it)
        annotationProcessor(it)
    }

    libs.mixinExtras.forge.also {
        localRuntime(it)
        include(it)
    }

    // HexCLI dev runtime: hexParse is optional for MieLib but required when HexCLI is enabled.
    // Its yclib dependency is jar-in-jar, which Loom does not load in dev; extract it separately.
    modLocalRuntime(libs.hexparse.forge)
    modLocalRuntime(files(extractNestedJar(
        libs.hexparse.forge.get().toString(),
        Regex("""META-INF/jars/yclib-.*\.jar"""),
        "yclib-forge.jar",
    )))
}

tasks {
    // Console input for the HexCLI dev server, plus EULA / offline-mode server.properties.
    named<JavaExec>("runHexcliServer") {
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

    shadowJar {
        exclude("fabric.mod.json")
    }
}
