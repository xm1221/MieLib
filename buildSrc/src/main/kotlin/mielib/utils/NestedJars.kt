package mielib.utils

import org.gradle.api.Project
import java.io.File
import java.util.zip.ZipFile

/**
 * Extracts a jar nested inside another mod jar (jar-in-jar) so it can be put on the dev runtime classpath.
 *
 * Loom does not load nested jars (entries under `META-INF/jars/`) in the development runtime. When a mod ships a
 * dependency that way and that dependency has no public maven, extract it once into `build/nested/<outName>`
 * and add the returned file as a `modLocalRuntime` entry.
 *
 * @param dependencyNotation the dependency to download (resolved with a non-transitive detached configuration)
 * @param entryRegex regex a zip entry name must fully match to be extracted
 * @param outName file name inside `<project>/build/nested/`
 */
fun Project.extractNestedJar(dependencyNotation: String, entryRegex: Regex, outName: String): File {
    val out = layout.buildDirectory.file("nested/$outName").get().asFile
    if (!out.exists()) {
        val raw = configurations.detachedConfiguration(dependencies.create(dependencyNotation))
            .apply { isTransitive = false }
            .singleFile
        ZipFile(raw).use { zip ->
            val entry = zip.entries().asSequence().first { entryRegex.matches(it.name) }
            out.parentFile.mkdirs()
            zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }
    return out
}
