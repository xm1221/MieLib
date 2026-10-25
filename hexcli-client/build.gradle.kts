// Standalone HexCLI terminal client. Plain Java (toolchain follows libs.versions.java): not a mod, not in any mod jar.
plugins {
    java
    application
}

group = "cn.xm1221.MieLib"
version = "1.0"

val javaVersion = libs.versions.java.get().toInt()

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.jline)
    implementation(libs.gson)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
}

tasks {
    compileJava {
        options.apply {
            encoding = "UTF-8"
            release = javaVersion
        }
    }

    named<JavaExec>("run") {
        standardInput = System.`in`
    }

    // Self-contained jar: `java -jar hexcli-client/build/libs/hexcli-client-<version>-all.jar`.
    // NOTE: the runtime classpath is only touched inside the lazy `from { }` closure (resolving it at
    // configuration time breaks Loom).
    register<Jar>("fatJar") {
        archiveClassifier = "all"
        manifest {
            attributes["Main-Class"] = "cn.xm1221.MieLib.hexcli.client.HexCliClient"
        }
        from(sourceSets.main.get().output)
        dependsOn(configurations.runtimeClasspath)
        from({
            configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
        })
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    named("build") {
        dependsOn("fatJar")
    }
}

application {
    mainClass = "cn.xm1221.MieLib.hexcli.client.HexCliClient"
}
