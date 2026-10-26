// HexCLI one-click installer: plain Java 17, no third-party runtime dependencies.
//
// Produces:
//   build/libs/hexcli-installer-<hexcliInstallerVersion>.jar   (Main-Class, embeds /hexcli-manifest.json)
//   build/distributions/hexcli-<ver>-{linux.tar.gz,macos.tar.gz,windows.zip} + SHA256SUMS
//
// The archives contain ONLY the installer jar, the hexcli-client fat jar, the bootstrap scripts
// and README.txt - no mod jars and no Minecraft jars. Everything else is downloaded and verified
// by the installer at run time.
//
// The archive writer is tools/PackageDist.java. It is compiled by a dedicated JavaCompile task
// (not packaged into the installer jar) so the exact same code can also be run by hand:
//   java hexcli-installer/tools/PackageDist.java --version ... --output ...
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.process.CommandLineArgumentProvider

plugins {
    java
}

group = "cn.xm1221.MieLib"

// The installer has its own version, independent of modVersion.
val installerVersion = providers.gradleProperty("hexcliInstallerVersion").get()
version = installerVersion

val javaVersion = libs.versions.java.get().toInt()

val manifestFile = layout.projectDirectory.file("manifest/hexcli-manifest.json")
val distDir = layout.projectDirectory.dir("dist")
val packageDistSource = layout.projectDirectory.file("tools/PackageDist.java")
val packageDistClasses = layout.buildDirectory.dir("distTool-classes")

repositories {
    // No dependencies, but the java plugin wants a repository for its configurations.
    mavenCentral()
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
}

dependencies {
    // Intentionally empty: the installer must run on a bare JRE 17.
}

tasks {
    compileJava {
        options.apply {
            encoding = "UTF-8"
            release = javaVersion
        }
    }

    // Build-time packaging tool only — never part of the installer jar.
    val compilePackageDist = register<JavaCompile>("compilePackageDist") {
        source(packageDistSource)
        classpath = files()
        destinationDirectory.set(packageDistClasses)
        options.apply {
            encoding = "UTF-8"
            release.set(javaVersion)
        }
    }

    // The manifest JSON is generated/verified by tools/gen-manifest.py; fail early with a useful
    // message instead of silently embedding a stale or missing file.
    val checkManifest = register("checkManifest") {
        group = "verification"
        description = "Checks that manifest/hexcli-manifest.json exists and looks valid."
        val file = manifestFile.asFile
        // Only declare the file as an input when it exists: otherwise Gradle fails while
        // snapshotting inputs and the message below never runs.
        inputs.property("manifestExists", file.isFile)
        if (file.isFile) {
            inputs.file(file).withPathSensitivity(PathSensitivity.RELATIVE)
        }
        doLast {
            if (!file.isFile) {
                throw GradleException(
                    "Missing ${file}. Generate it with:\n" +
                        "  python3 hexcli-installer/tools/gen-manifest.py " +
                        "--mielib-jar <built fabric jar> --mielib-url <url>"
                )
            }
            // Structural sanity check only; the installer and gen-manifest.py do the real validation.
            val text = file.readText()
            for (key in listOf("installerVersion", "minecraft", "fabricLoader", "serverLauncher", "mods")) {
                if (!text.contains("\"$key\"")) {
                    throw GradleException("$file does not look like a HexCLI manifest (missing \"$key\")")
                }
            }
        }
    }

    jar {
        dependsOn(checkManifest)
        archiveBaseName.set("hexcli-installer")
        archiveVersion.set(installerVersion)
        manifest {
            attributes(
                "Main-Class" to "cn.xm1221.MieLib.installer.Installer",
                "Implementation-Title" to "HexCLI installer",
                "Implementation-Version" to installerVersion,
            )
        }
        // Embed the manifest at the jar root as /hexcli-manifest.json.
        from(manifestFile)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // The self-contained client jar lives in the sibling project.
    val clientFatJar = project(":hexcli-client").tasks.named<Jar>("fatJar")

    val packageDist = register<JavaExec>("packageDist") {
        group = "distribution"
        description = "Builds the one-click installer archives for Linux, macOS and Windows."
        dependsOn(jar, clientFatJar, compilePackageDist)
        // The tool deletes its own build/distributions/.staging; keep Gradle from complaining
        // about files it did not declare as outputs.
        outputs.upToDateWhen { false }

        classpath = files(packageDistClasses)
        mainClass.set("PackageDist")

        // Only the three archives + SHA256SUMS are declared: staging is an implementation detail.
        val outputDir = layout.buildDirectory.dir("distributions")
        val installerJar = jar.flatMap { it.archiveFile }
        val clientJar = clientFatJar.flatMap { it.archiveFile }

        inputs.file(installerJar)
        inputs.file(clientJar)
        inputs.dir(distDir)
        inputs.file(packageDistSource)
        for (platform in listOf("linux.tar.gz", "macos.tar.gz", "windows.zip")) {
            outputs.file(outputDir.map { it.file("hexcli-$installerVersion-$platform") })
        }
        outputs.file(outputDir.map { it.file("SHA256SUMS") })

        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "--version", installerVersion,
                "--output", outputDir.get().asFile.absolutePath,
                "--installer", installerJar.get().asFile.absolutePath,
                "--client", clientJar.get().asFile.absolutePath,
                "--dist", distDir.asFile.absolutePath,
            )
        })
    }

    // `build` is what CI runs; make it produce the archives too.
    named("build") {
        dependsOn(packageDist)
    }
}
