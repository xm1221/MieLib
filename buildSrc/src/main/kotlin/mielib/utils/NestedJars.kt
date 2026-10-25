package mielib.utils

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import java.io.File
import java.util.zip.ZipFile

/**
 * Extracts a jar nested inside another mod jar (jar-in-jar) so it can be put on the dev runtime classpath.
 *
 * Loom does not load nested jars (entries under `META-INF/jars/`) in the development runtime. When a mod ships a
 * dependency that way and that dependency has no public maven, extract it into `build/nested/<outName>`
 * and add the returned collection as a `modLocalRuntime` entry.
 *
 * Everything here is lazy: the outer jar is declared in a dedicated resolvable configuration and only resolved when
 * the returned file collection is first queried (by Loom, after it has set up its repositories). Resolving eagerly
 * during script evaluation breaks Loom's Minecraft setup on a clean checkout
 * ("Cannot mutate content repository descriptor 'Mojang' after repository has been used").
 *
 * @param dependencyNotation the dependency containing the nested jar (resolved non-transitively)
 * @param entryRegex regex a zip entry name must fully match to be extracted
 * @param outName file name inside `<project>/build/nested/`
 */
fun Project.extractNestedJar(dependencyNotation: String, entryRegex: Regex, outName: String): FileCollection {
    val source = configurations.create("nestedJarSource" + outName.replace(Regex("[^A-Za-z0-9]"), "")) {
        isCanBeResolved = true
        isCanBeConsumed = false
        isTransitive = false
    }
    dependencies.add(source.name, dependencyNotation)
    val out = layout.buildDirectory.file("nested/$outName")
    return files(provider {
        val target = out.get().asFile
        if (!target.exists()) {
            ZipFile(source.singleFile).use { zip ->
                val entry = zip.entries().asSequence().first { entryRegex.matches(it.name) }
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        target
    })
}
