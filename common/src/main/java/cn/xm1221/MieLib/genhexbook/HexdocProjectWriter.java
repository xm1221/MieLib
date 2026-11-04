package cn.xm1221.MieLib.genhexbook;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
import java.util.concurrent.TimeUnit;
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
        // Start clean: a previous dump would otherwise make every file "already present"
        // and the run would report 0 dumped files.
        deleteRecursively(resources);
        Files.createDirectories(resources);

        int files = dumpResources(server, resources);
        GenHexBook.LOGGER.info("[GenHexBook] dumped {} resource files → {}", files, resources);
        copyClientAssets(resources);

        writePatterns(project.resolve("patterns-registry.json"));
        copyCompanion(project);

        Path template = project.resolve("hexdoc.toml.template");
        Files.copy(template, project.resolve("hexdoc.toml"), StandardCopyOption.REPLACE_EXISTING);

        String env = readCompanion(".env.template");
        if (env == null) {
            env = "GITHUB_REPOSITORY=xm1221/MieLib\nGITHUB_SHA=main\n";
        }
        env = setEnvLine(env, "GITHUB_PAGES_URL", siteUrl);
        // hexdoc builds internal texture URLs as <asset_url>/resources/assets/<ns>/textures/...
        // where asset_url defaults to raw.githubusercontent.com/<repo>/<sha> (a 404 for us).
        // DEBUG_GITHUBUSERCONTENT overrides asset_url, so point it at the published site: the
        // runner copies the texture tree to <site>/resources/assets/.
        env = setEnvLine(env, "DEBUG_GITHUBUSERCONTENT", siteUrl);
        Files.writeString(project.resolve(".env"), env, StandardCharsets.UTF_8);

        initGitRepo(project);
        return project;
    }

    /**
     * Sets {@code key=value} in a dotenv-style text: replaces every existing line for
     * {@code key}, or appends one when the template does not mention it.
     */
    private static String setEnvLine(String env, String key, String value) {
        String line = key + "=" + value;
        String replaced = env.replaceAll("(?m)^" + java.util.regex.Pattern.quote(key) + "=.*$",
            java.util.regex.Matcher.quoteReplacement(line));
        if (!replaced.equals(env) || env.contains(key + "=")) {
            return replaced;
        }
        return (replaced.isEmpty() || replaced.endsWith("\n") ? replaced : replaced + "\n") + line + "\n";
    }

    /**
     * hexdoc runs {@code git rev-parse --show-toplevel} and refuses to build outside a
     * repository. Best-effort: log and continue when git is unavailable.
     */
    private static void initGitRepo(Path project) {
        if (!runGit(project, "init")) {
            GenHexBook.LOGGER.warn("[GenHexBook] git init failed in {} (hexdoc may refuse to build)", project);
            return;
        }
        // A fresh repository has no HEAD; hexdoc needs a commit to resolve the branch.
        runGit(project, "add", "-A");
        if (!runGit(project, "-c", "user.email=xm1221@qq.com", "-c", "user.name=xm1221",
                "commit", "-m", "hexdoc-dump", "--allow-empty")) {
            GenHexBook.LOGGER.warn("[GenHexBook] git commit failed in {}", project);
        }
    }

    private static boolean runGit(Path cwd, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(argv)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            GenHexBook.LOGGER.debug("[GenHexBook] git {}: {}", String.join(" ", args), e.toString());
            return false;
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        }
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
                                String rel = dir.relativize(p).toString().replace('\\', '/');
                                if (!keepResource(rel)) return;
                                // hexdoc expects resources/assets/... and resources/data/..., so keep the top-level segment.
                                Path target = dest.resolve(top).resolve(rel);
                                if (Files.exists(target)) return; // first jar wins (mods sorted by name)
                                Files.createDirectories(target.getParent());
                                try (InputStream in = Files.newInputStream(p)) {
                                    byte[] bytes = in.readAllBytes();
                                    if (isLangFile(rel)) {
                                        String error = langJsonError(bytes);
                                        if (error != null) {
                                            GenHexBook.LOGGER.warn("[GenHexBook] skip invalid lang {} ({}): {}", rel, error, p);
                                            return;
                                        }
                                    }
                                    Files.write(target, bytes);
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

    /** Optional vanilla client jar, needed to render addon items with vanilla model parents. */
    private static final String CLIENT_JAR_PROPERTY = "mielib.genhexbook.clientJar";
    private static final List<String> CLIENT_ASSET_DIRS = List.of(
        "assets/minecraft/models",
        "assets/minecraft/textures/item",
        "assets/minecraft/textures/block",
        "assets/minecraft/blockstates"
    );

    /**
     * Copies the vanilla client assets hexdoc needs from {@code -Dmielib.genhexbook.clientJar}.
     *
     * <p>Dedicated servers ship no client jar, so addon items whose models inherit from vanilla
     * parents (e.g. {@code minecraft:item/generated}) would otherwise render as missing images.
     * Files already dumped from {@code mods/} are not overwritten, so mod overrides win.
     */
    private static int copyClientAssets(Path dest) {
        String value = System.getProperty(CLIENT_JAR_PROPERTY);
        if (value == null || value.isBlank()) return 0;
        Path jar = Path.of(value.strip());
        if (!Files.isRegularFile(jar)) {
            GenHexBook.LOGGER.warn("[GenHexBook] {} points to a missing client jar: {}",
                CLIENT_JAR_PROPERTY, jar);
            return 0;
        }
        int[] count = {0};
        try (FileSystem fs = FileSystems.newFileSystem(jar, Collections.emptyMap())) {
            for (Path root : fs.getRootDirectories()) {
                for (String dirName : CLIENT_ASSET_DIRS) {
                    Path dir = root.resolve(dirName);
                    if (!Files.isDirectory(dir)) continue;
                    try (Stream<Path> walk = Files.walk(dir)) {
                        walk.filter(Files::isRegularFile).forEach(p -> {
                            try {
                                Path target = dest.resolve(root.relativize(p).toString());
                                if (Files.exists(target)) return;
                                Files.createDirectories(target.getParent());
                                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                                count[0]++;
                            } catch (Exception ex) {
                                GenHexBook.LOGGER.debug("[GenHexBook] skip client asset {}: {}", p, ex.toString());
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            GenHexBook.LOGGER.warn("[GenHexBook] open client jar {}: {}", jar.getFileName(), e.toString());
            return count[0];
        }
        GenHexBook.LOGGER.info("[GenHexBook] copied {} vanilla client asset files from {}",
            count[0], jar.getFileName());
        return count[0];
    }

    /** Languages hexdoc can handle; anything else (e.g. cloth-config's {@code en_ud}) breaks it. */
    private static final List<String> KEPT_LANGS = List.of("en_us", "zh_cn");
    private static final java.util.regex.Pattern LOCALE_SEGMENT =
        java.util.regex.Pattern.compile("[a-z]{2,3}_[a-z]{2,4}");

    /**
     * Keeps only {@code en_us}/{@code zh_cn} language data. hexdoc parses every language it
     * finds, so invalid BCP47 tags and malformed translations from unrelated mods would
     * abort the whole build.
     */
    private static boolean keepResource(String rel) {
        // <ns>/lang/<locale>.json
        if (rel.endsWith(".json")) {
            String[] parts = rel.split("/");
            if (parts.length == 3 && parts[1].equals("lang")) {
                return KEPT_LANGS.contains(parts[2].substring(0, parts[2].length() - ".json".length())
                    .toLowerCase(Locale.ROOT));
            }
        }
        // <ns>/patchouli_books/<book>/<locale>/...
        int books = rel.indexOf("patchouli_books/");
        if (books >= 0) {
            String[] parts = rel.substring(books + "patchouli_books/".length()).split("/");
            if (parts.length >= 2 && LOCALE_SEGMENT.matcher(parts[1]).matches()) {
                return KEPT_LANGS.contains(parts[1].toLowerCase(Locale.ROOT));
            }
        }
        return true;
    }

    private static boolean isLangFile(String rel) {
        String[] parts = rel.split("/");
        return parts.length == 3 && parts[1].equals("lang") && rel.endsWith(".json");
    }

    /**
     * Returns {@code null} when {@code bytes} is a parseable JSON object, otherwise a short
     * reason. hexdoc hands every language file to a strict JSON5 parser, so a merely
     * brace-balanced file (e.g. patchouli's upstream {@code zh_cn.json}, which is missing a
     * comma) still aborts the whole build.
     */
    private static String langJsonError(byte[] bytes) {
        int start = 0;
        int end = bytes.length;
        // UTF-8 BOM
        if (end >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        while (start < end && isSpace(bytes[start])) start++;
        while (end > start && isSpace(bytes[end - 1])) end--;
        if (end - start < 2 || bytes[start] != '{' || bytes[end - 1] != '}') {
            return "not a JSON object";
        }
        try {
            JsonElement element = JsonParser.parseString(new String(bytes, start, end - start, StandardCharsets.UTF_8));
            return element.isJsonObject() ? null : "not a JSON object";
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n';
    }

    /**
     * Pattern stubs: id / startdir / signature / is_per_world, from the live registry.
     *
     * <p>Only addon patterns are exported: hexdoc-hexcasting already loads vanilla
     * {@code hexcasting:} patterns from its packaged {@code hexcasting.patterns.hexdoc.json},
     * and repeating them makes hexdoc fail with {@code Duplicate pattern id}.
     */
    private static void writePatterns(Path target) throws IOException {
        List<Stub> stubs = new ArrayList<>();
        for (PatternInfo p : PatternLookup.list()) {
            if (p.id().startsWith("hexcasting:")) continue;
            stubs.add(new Stub(p.id(), p.startDir().toUpperCase(Locale.ROOT), p.signature(), p.perWorld()));
        }
        Files.writeString(target, GSON.toJson(stubs), StandardCharsets.UTF_8);
        GenHexBook.LOGGER.info("[GenHexBook] wrote {} addon pattern stubs → {}", stubs.size(), target);
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
