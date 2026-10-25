// A convention plugin that should be applied to all Minecraft-related subprojects, including common.

@file:Suppress("UnstableApiUsage")

package mielib

import kotlin.io.path.div
import libs
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("mielib.java")

    `maven-publish`
    id("dev.architectury.loom")
    id("at.petra-k.pkpcpbp.PKJson5Plugin")
}

val modId: String by project
val platform: String by project

base.archivesName = "${modId}-$platform"

loom {
    silentMojangMappingsLicense()
    accessWidenerPath = project(":common").file("src/main/resources/mielib.accesswidener")

    mixin {
        // Loom 1.7+ disables the mixin AP by default. GuiSpellcasting.render must be remapped
        // (see f69bc70); without a refmap Fabric looks up a method literally named `render`.
        useLegacyMixinAp.set(true)
        // the default name includes both archivesName and the subproject, resulting in the platform showing up twice
        // default: mielib-common-common-refmap.json
        // fixed:   mielib-common.refmap.json
        defaultRefmapName = "${base.archivesName.get()}.refmap.json"
    }
}

pkJson5 {
    autoProcessJson5 = true
    autoProcessJson5Flattening = true
}

dependencies {
    minecraft(libs.minecraft)

    mappings(loom.layered {
        officialMojangMappings()
        parchment(libs.parchment)
    })

    annotationProcessor(libs.bundles.asm)
}

publishing {
    repositories {
        val repoDir = (findProperty("mavenRepo") as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv("MAVEN_REPO")?.takeIf { it.isNotBlank() }
        if (repoDir != null) {
            maven {
                name = "xm1221Maven"
                url = uri(repoDir)
            }
        }
    }
    publications {
        register<MavenPublication>("maven") {
            artifactId = base.archivesName.get()

            val javaComponent = components.findByName("java")
            if (javaComponent != null) {
                from(javaComponent)
            } else {
                project.afterEvaluate {
                    from(components["java"])
                }
            }
        }
    }
}

sourceSets {
    main {
        kotlin {
            srcDir(file("src/main/java"))
        }
        resources {
            srcDir(file("src/generated/resources"))
        }
    }
}
