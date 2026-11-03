package cn.xm1221.MieLib.genhexbook;

import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * Walk {@code assets/...} inside every jar under the server {@code mods/} folder
 * (and recursively one level of nested jars). Dedicated servers do not expose
 * client asset packs through {@code ResourceManager}, so Patchouli book JSON must
 * be read this way.
 */
public final class ModAssetWalker {
    private ModAssetWalker() {}

    public record AssetFile(String namespace, String pathUnderAssets, Path sourceJar, Path pathInJar) {
        public InputStream open() throws IOException {
            return Files.newInputStream(pathInJar);
        }
    }

    /** Visit every regular file under {@code assets/} whose relative path matches {@code pathPredicate}. */
    public static void walk(MinecraftServer server, java.util.function.Predicate<String> pathUnderAssetsPredicate,
                            BiConsumer<AssetFile, InputStream> consumer) {
        Path mods = server.getServerDirectory().toPath().resolve("mods");
        if (!Files.isDirectory(mods)) {
            GenHexBook.LOGGER.warn("[GenHexBook] no mods/ directory at {}", mods);
            return;
        }
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> s = Files.list(mods)) {
            s.filter(p -> p.getFileName().toString().endsWith(".jar")).forEach(jars::add);
        } catch (IOException e) {
            GenHexBook.LOGGER.warn("[GenHexBook] list mods failed: {}", e.toString());
            return;
        }
        for (Path jar : jars) {
            walkJar(jar, pathUnderAssetsPredicate, consumer, 0);
        }
    }

    private static void walkJar(Path jar, java.util.function.Predicate<String> pred,
                                BiConsumer<AssetFile, InputStream> consumer, int depth) {
        if (depth > 1) return;
        try (FileSystem fs = FileSystems.newFileSystem(jar, Collections.emptyMap())) {
            for (Path root : fs.getRootDirectories()) {
                Path assets = root.resolve("assets");
                if (!Files.isDirectory(assets)) continue;
                try (Stream<Path> walk = Files.walk(assets)) {
                    walk.filter(Files::isRegularFile).forEach(p -> {
                        String rel = assets.relativize(p).toString().replace('\\', '/');
                        // rel = <ns>/...
                        int slash = rel.indexOf('/');
                        if (slash <= 0) return;
                        String ns = rel.substring(0, slash);
                        String under = rel.substring(slash + 1); // path after namespace
                        if (!pred.test(under)) return;
                        try (InputStream in = Files.newInputStream(p)) {
                            consumer.accept(new AssetFile(ns, under, jar, p), in);
                        } catch (Exception ex) {
                            GenHexBook.LOGGER.warn("[GenHexBook] read {}:{}: {}", jar.getFileName(), rel, ex.toString());
                        }
                    });
                }
                // nested jars (JiJ) one level
                Path jij = root.resolve("META-INF").resolve("jars");
                if (depth == 0 && Files.isDirectory(jij)) {
                    try (Stream<Path> nest = Files.list(jij)) {
                        nest.filter(p -> p.getFileName().toString().endsWith(".jar")).forEach(nested -> {
                            try {
                                Path tmp = Files.createTempFile("genhexbook-jij-", ".jar");
                                Files.copy(nested, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                walkJar(tmp, pred, consumer, depth + 1);
                                Files.deleteIfExists(tmp);
                            } catch (Exception ex) {
                                GenHexBook.LOGGER.debug("[GenHexBook] skip nested {}: {}", nested, ex.toString());
                            }
                        });
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            GenHexBook.LOGGER.warn("[GenHexBook] open jar {}: {}", jar.getFileName(), e.toString());
        }
    }

    /** Also try classpath resource URLs (dev env). */
    public static void walkClasspath(java.util.function.Predicate<String> pred,
                                     BiConsumer<AssetFile, InputStream> consumer) {
        try {
            var cl = ModAssetWalker.class.getClassLoader();
            var urls = cl.getResources("assets");
            while (urls.hasMoreElements()) {
                var url = urls.nextElement();
                if (!"file".equals(url.getProtocol()) && !"jar".equals(url.getProtocol())) continue;
                // best-effort; primarily mods/ walk covers production
            }
        } catch (Exception ignored) {}
    }
}
