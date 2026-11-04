package cn.xm1221.MieLib.genhexbook;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Writes a self-contained <a href="https://pypi.org/project/hexdoc/">hexdoc</a> project
 * into {@code <out>/hexdoc-project/}:
 *
 * <ul>
 *   <li>{@code resources/} — {@code assets/} and {@code data/} merged from every jar in
 *       {@code mods/} (dedicated servers do not expose client asset packs through the
 *       {@code ResourceManager});</li>
 *   <li>{@code patterns-registry.json} — pattern stubs straight from the live Action
 *       registry, so no source scraping is needed;</li>
 *   <li>{@code hexdoc.toml}, {@code .env} — config;</li>
 *   <li>{@code src/}, {@code requirements.txt}, {@code build.sh} — the companion from
 *       {@code doc/genhexbook/}.</li>
 * </ul>
 *
 * {@link HexdocRunner} then invokes the real {@code hexdoc build}.
 */
public final class HexdocProjectWriter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Companion resources shipped inside the mod jar under {@code /genhexbook/}. */
    private static final String COMPANION_ROOT = "/genhexbook/";
    private static final List<String> COMPANION_FILES = List.of(
        "hexdoc.toml.template",
        "requirements.txt",
        ".env.template",
        "build.sh",
        "README.md",
        "src/hexdoc_livehexbook/__init__.py",
        "src/hexdoc_livehexbook-0.0.1.dist-info/METADATA",
        "src/hexdoc_livehexbook-0.0.1.dist-info/entry_points.txt"
    );

    private HexdocProjectWriter() {}

    public static Path write(MinecraftServer server, Path outDir, String siteUrl) throws IOException {
        Path project = outDir.resolve("hexdoc-project");
        Path resources = project.resolve("resources");
        Files.createDirectories(resources);

        int files = dumpResources(server, resources);
        GenHexBook.LOGGER.info("[GenHexBook] dumped {} resource files → {}", files, resources);

        writePatterns(project.resolve("patterns-registry.json"));
        copyCompanion(project);

        Path template = project.resolve("hexdoc.toml.template");
        Files.copy(template, project.resolve("hexdoc.toml"), StandardCopyOption.REPLACE_EXISTING);

        String env = readCompanion(".env.template");
        if (env == null) {
            env = "GITHUB_REPOSITORY=xm1221/MieLib\nGITHUB_SHA=main\n";
        }
        env = env.replaceAll("(?m)^GITHUB_PAGES_URL=.*$", "GITHUB_PAGES_URL=" + siteUrl);
        Files.writeString(project.resolve(".env"), env, StandardCharsets.UTF_8);

        return project;
    }

    /** Merge {@code assets/} + {@code data/} of every {@code mods/*.jar} into {@code dest}. */
    private static int dumpResources(MinecraftServer server, Path dest) throws IOException {
        Path mods = server.getServerDirectory().toPath().resolve("mods");
        if (!Files.isDirectory(mods)) {
            GenHexBook.LOGGER.warn("[GenHexBook] no mods/ directory at {}", mods);
            return 0;
        }
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> s = Files.list(mods)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(jars::add);
        }
        int[] count = {0};
        for (Path jar : jars) {
            copyJarResources(jar, dest, count, 0);
        }
        return count[0];
    }

    private static void copyJarResources(Path jar, Path dest, int[] count, int depth) {
        if (depth > 1) return;
        try (FileSystem fs = FileSystems.newFileSystem(jar, Collections.emptyMap())) {
            for (Path root : fs.getRootDirectories()) {
                for (String top : new String[]{"assets", "data"}) {
                    Path dir = root.resolve(top);
                    if (!Files.isDirectory(dir)) continue;
                    try (Stream<Path> walk = Files.walk(dir)) {
                        walk.filter(Files::isRegularFile).forEach(p -> {
                            try {
                                Path target = dest.resolve(dir.relativize(p).toString().replace('\\', '/'));
                                if (Files.exists(target)) return; // first jar wins (mods sorted by name)
                                Files.createDirectories(target.getParent());
                                try (InputStream in = Files.newInputStream(p)) {
                                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                                }
                                count[0]++;
                            } catch (Exception ex) {
                                GenHexBook.LOGGER.debug("[GenHexBook] skip {}: {}", p, ex.toString());
                            }
                        });
                    }
                }
                Path jij = root.resolve("META-INF").resolve("jars");
                if (depth == 0 && Files.isDirectory(jij)) {
                    try (Stream<Path> nest = Files.list(jij)) {
                        nest.filter(p -> p.getFileName().toString().endsWith(".jar")).forEach(nested -> {
                            try {
                                Path tmp = Files.createTempFile("genhexbook-jij-", ".jar");
                                Files.copy(nested, tmp, StandardCopyOption.REPLACE_EXISTING);
                                copyJarResources(tmp, dest, count, depth + 1);
                                Files.deleteIfExists(tmp);
                            } catch (Exception ex) {
                                GenHexBook.LOGGER.debug("[GenHexBook] skip nested {}: {}", nested, ex.toString());
                            }
                        });
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            GenHexBook.LOGGER.warn("[GenHexBook] open jar {}: {}", jar.getFileName(), e.toString());
        }
    }

    /** Pattern stubs: id / startdir / signature / is_per_world, from the live registry. */
    private static void writePatterns(Path target) throws IOException {
        List<Stub> stubs = new ArrayList<>();
        for (PatternInfo p : PatternLookup.list()) {
            stubs.add(new Stub(p.id(), p.startDir().toUpperCase(Locale.ROOT), p.signature(), p.perWorld()));
        }
        Files.writeString(target, GSON.toJson(stubs), StandardCharsets.UTF_8);
        GenHexBook.LOGGER.info("[GenHexBook] wrote {} pattern stubs → {}", stubs.size(), target);
    }

    private record Stub(String id, String startdir, String signature, boolean is_per_world) {}

    private static void copyCompanion(Path project) throws IOException {
        int copied = 0;
        for (String rel : COMPANION_FILES) {
            String content = readCompanion(rel);
            if (content == null) {
                GenHexBook.LOGGER.warn("[GenHexBook] companion resource /genhexbook/{} missing", rel);
                continue;
            }
            Path target = project.resolve(rel);
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (rel.endsWith("build.sh")) {
                content = content.replace("\r\n", "\n");
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            if (rel.endsWith("build.sh")) {
                try {
                    target.toFile().setExecutable(true);
                } catch (Exception ignored) {
                }
            }
            copied++;
        }
        GenHexBook.LOGGER.info("[GenHexBook] copied {} companion files", copied);
    }

    private static String readCompanion(String rel) {
        try (InputStream in = HexdocProjectWriter.class.getResourceAsStream(COMPANION_ROOT + rel)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            GenHexBook.LOGGER.warn("[GenHexBook] read companion {}: {}", rel, e.toString());
            return null;
        }
    }
}
