import java.util.zip.ZipFile

// HexCLI — MieLib 的开发用子项目（里程碑 1：仅 Fabric）。
// 无头专用服务端 + 控制台输入 hexParse 代码执行法术。独立 mod id `hexcli`，不会打进 MieLib 的 jar。
//
// 不套 mielib.minecraft 约定插件：HexCLI 有自己的 mod id / 运行配置，也不需要 accesswidener、发布等。
// Kotlin：仓库已升到 Kotlin 2.2.21 + Architectury Loom 1.9（自带 kotlin-metadata-jvm），
// 能正确重映射用 Kotlin 2.2 编译的 hexParse，所以这里也应用 Kotlin 插件，Java / Kotlin 都可以写。
// （之前失败的根因是 Loom 1.7 内置的 kotlinx-metadata-jvm 最高只认 2.0 元数据，不是项目的 Kotlin 版本。）

plugins {
    java
    kotlin("jvm")
    id("dev.architectury.loom")
}

val mavenGroup: String by project
val modVersion: String by project
val minecraftVersion = libs.versions.minecraft.get()

group = mavenGroup
version = "$modVersion+$minecraftVersion-SNAPSHOT"
base.archivesName = "hexcli-fabric"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
}

// 示例附属（mod id `hexcli_example`）：只用于开发运行时，不进 hexcli 的 jar。
// 见 src/example/java：演示 HexCLI 环境注册 API 的软依赖写法。
sourceSets {
    create("example") {
        compileClasspath += main.get().compileClasspath + main.get().output
        runtimeClasspath += main.get().runtimeClasspath + main.get().output
    }
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = libs.versions.java.get().toInt()
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.blamejared.com") }
    maven { url = uri("https://maven.fabricmc.net/") }
    maven { url = uri("https://maven.hexxy.media") }
    maven { url = uri("https://maven.ladysnake.org/releases") }
    maven { url = uri("https://maven.parchmentmc.org") }
    maven { url = uri("https://maven.shedaniel.me") }
    maven { url = uri("https://maven.terraformersmc.com/releases") }
    maven { url = uri("https://jitpack.io") }
    exclusiveContent {
        filter { includeGroup("maven.modrinth") }
        forRepository { maven { url = uri("https://api.modrinth.com/maven") } }
    }
}

loom {
    silentMojangMappingsLicense()
    mixin {
        defaultRefmapName = "hexcli.refmap.json"
    }
    mods {
        create("hexcli") { sourceSet(sourceSets.main.get()) }
        create("hexcli_example") { sourceSet(sourceSets["example"]) }
    }
    runs {
        named("server") {
            runDir = "run"
            programArg("nogui")
            ideConfigGenerated(false)
        }
        named("client") { ideConfigGenerated(false) }
        create("exampleServer") {
            server()
            runDir = "run"
            programArg("nogui")
            source(sourceSets["example"])
            ideConfigGenerated(false)
        }
    }
}

// hexParse 把 yclib 作为 jar-in-jar 内嵌，但开发环境不会加载内嵌 jar，而 yclib 又没有公开 maven。
// 这里在配置阶段把内嵌的 yclib 解出来，作为单独的开发运行时 mod 交给 Loom 重映射。
val nestedYclib: File = run {
    val out = layout.buildDirectory.file("nested/yclib.jar").get().asFile
    if (!out.exists()) {
        val raw = configurations.detachedConfiguration(dependencies.create(libs.hexparse.fabric.get().toString()))
            .apply { isTransitive = false }
            .singleFile
        ZipFile(raw).use { zip ->
            val entry = zip.entries().asSequence().first { it.name.matches(Regex("META-INF/jars/yclib-.*\\.jar")) }
            out.parentFile.mkdirs()
            zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }
    out
}

dependencies {
    minecraft(libs.minecraft)
    mappings(loom.layered {
        officialMojangMappings()
        parchment(libs.parchment)
    })

    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)
    modImplementation(libs.kotlin.fabric)

    modImplementation(libs.architectury.fabric) {
        exclude(group = "net.fabricmc", module = "fabric-loader")
    }
    modImplementation(libs.hexcasting.fabric) {
        exclude(module = "phosphor")
    }
    modImplementation(libs.clothConfig.fabric) {
        exclude(group = "net.fabricmc.fabric-api")
    }

    // 仓库里当前的 MieLib（不是 mavenLocal 里的旧版本），这样 HexCLI 测的就是正在开发的 MieLib
    implementation(project(path = ":common", configuration = "namedElements")) { isTransitive = false }
    implementation(project(path = ":fabric", configuration = "namedElements")) { isTransitive = false }

    // hexParse：必需依赖（Modrinth WjFyIzFj，版本 wt2k5ALr = hexParse-fabric-1.20.1-1.12.0）
    modImplementation(libs.hexparse.fabric)
    modLocalRuntime(files(nestedYclib))

    // Hex Casting 运行时依赖（与 :fabric 一致，只进开发运行时）
    modLocalRuntime(libs.paucal.fabric)
    modLocalRuntime(libs.patchouli.fabric)
    modLocalRuntime(libs.cardinalComponents)
    modLocalRuntime(libs.serializationHooks)
    modLocalRuntime(libs.trinkets)
    modLocalRuntime(libs.inline.fabric) { isTransitive = false }

    libs.mixinExtras.fabric.also {
        localRuntime(it)
        annotationProcessor(it)
    }
}

tasks {
    // 让 `./gradlew :hexcli:runServer` 能从终端 / 管道读控制台输入
    listOf("runServer", "runExampleServer").forEach { name ->
        named<JavaExec>(name) {
            standardInput = System.`in`
            doFirst {
                val run = file("run")
                run.mkdirs()
                // 开发用：自动同意 EULA、离线模式
                run.resolve("eula.txt").takeIf { !it.exists() }?.writeText("eula=true\n")
                run.resolve("server.properties").takeIf { !it.exists() }?.writeText(
                    "online-mode=false\nspawn-protection=0\nlevel-name=hexcli-world\nmotd=HexCLI\nenable-rcon=false\n"
                )
            }
        }
    }
    // `:hexcli:build` 也编译示例 source set（示例不进 jar：jar 只用 main）。
    named("build") { dependsOn("exampleClasses") }
    processResources {
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
    }
}
