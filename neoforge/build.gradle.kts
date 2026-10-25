plugins {
    id("mielib.platform")
}

val modId: String by project

architectury {
    neoForge()
}

loom {
    runs {
        register("commonDatagen") {
            data()
            programArgs(
                "--mod", modId,
                "--all",
                "--output", project(":common").file("src/generated/resources").absolutePath,
                "--existing", file("src/main/resources").absolutePath,
                "--existing", project(":common").file("src/main/resources").absolutePath,
                "--existing-mod", "hexcasting",
            )
            property("mielib.apply-datagen-mixin", "true")
            property("mielib.common-datagen", "true")
        }
        register("neoforgeDatagen") {
            data()
            programArgs(
                "--mod", modId,
                "--all",
                "--output", file("src/generated/resources").absolutePath,
                "--existing", file("src/main/resources").absolutePath,
                "--existing", project(":common").file("src/main/resources").absolutePath,
                "--existing-mod", "hexcasting",
            )
            property("mielib.apply-datagen-mixin", "true")
            property("mielib.neoforge-datagen", "true")
        }
    }
}

mielibModDependencies {
    filesMatching.add("META-INF/neoforge.mods.toml")

    anyVersion = ""
    mapVersions {
        replace(Regex("""\](\S+)"""), "($1")
        replace(Regex("""(\S+)\["""), "$1)")
    }

    requires("architectury-api")
    requires("cloth-config")
    requires(curseforge = "hexcasting", modrinth = "hex-casting")
    requires("kotlin-for-forge")
}

val hexNeoForgeJar = rootProject.file("libs/hexcasting-neoforge-1.21.1-0.12.0-devel-pre-64.jar")

dependencies {
    neoForge(libs.neoforge)
    implementation(libs.architectury.neoforge)

    // KFF is the runtime Kotlin loader. Sources are compiled by this project's Kotlin plugin.
    modImplementation(libs.kotlin.neoforge) {
        exclude(group = "net.neoforged.fancymodloader", module = "loader")
    }

    // BlameJared has no Hex Casting 1.21 artifacts. This jar is Mojmap (named), same bytes as
    // MieHexRevolution's 1.21 common/libs copy of 0.12.0-devel-pre-64.
    implementation(files(hexNeoForgeJar))

    modImplementation(libs.paucal.neoforge)
    modLocalRuntime(libs.patchouli.neoforge)
    modLocalRuntime(libs.caelus)
    modLocalRuntime(libs.inline.neoforge) { isTransitive = false }

    modApi(libs.clothConfig.neoforge)

    libs.mixinExtras.common.also {
        compileOnly(it)
        annotationProcessor(it)
    }
}

tasks {
    shadowJar {
        exclude("fabric.mod.json")
    }
}

// HexCLI is not part of the 1.21 compile. Sources stay for the follow-up port.
sourceSets.main {
    java {
        exclude("cn/xm1221/MieLib/hexcli/**")
    }
    kotlin {
        exclude("cn/xm1221/MieLib/hexcli/**")
    }
}
