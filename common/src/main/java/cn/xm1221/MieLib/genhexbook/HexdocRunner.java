package cn.xm1221.MieLib.genhexbook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Locates and runs the {@code hexdoc} CLI against a dumped project
 * (see {@link HexdocProjectWriter}), then surfaces the built site.
 *
 * <p>Resolution order for the executable:
 * <ol>
 *   <li>{@code -Dmielib.genhexbook.hexdoc=<path>}</li>
 *   <li>{@code hexdoc} on {@code PATH}</li>
 *   <li>{@code -Dmielib.genhexbook.python=<path>} → {@code <python> -m hexdoc}</li>
 *   <li>well-known venvs: {@code /tmp/hexdoc-genhexbook-venv/bin/hexdoc},
 *       {@code /tmp/hexdoc-venv/bin/hexdoc}</li>
 * </ol>
 */
public final class HexdocRunner {
    public static final String HEXDOC_PROP = "mielib.genhexbook.hexdoc";
    public static final String PYTHON_PROP = "mielib.genhexbook.python";
    public static final String TIMEOUT_PROP = "mielib.genhexbook.timeoutSeconds";

    private static final List<String> WELL_KNOWN = List.of(
        "/tmp/hexdoc-genhexbook-venv/bin/hexdoc",
        "/tmp/hexdoc-venv/bin/hexdoc"
    );

    private HexdocRunner() {}

    public record Command(List<String> argv) {
        @Override
        public String toString() {
            return String.join(" ", argv);
        }
    }

    /** Returns the command to run, or {@code null} when hexdoc cannot be found. */
    public static Command find() {
        String prop = System.getProperty(HEXDOC_PROP, "").trim();
        if (!prop.isEmpty()) {
            Path p = Path.of(prop);
            if (Files.isRegularFile(p)) return new Command(List.of(p.toString()));
            GenHexBook.LOGGER.warn("[GenHexBook] {}={} is not a file", HEXDOC_PROP, prop);
        }

        String onPath = which("hexdoc");
        if (onPath != null) return new Command(List.of(onPath));

        String python = System.getProperty(PYTHON_PROP, "").trim();
        if (!python.isEmpty() && Files.isRegularFile(Path.of(python))) {
            return new Command(List.of(python, "-m", "hexdoc"));
        }

        for (String candidate : WELL_KNOWN) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return new Command(List.of(candidate));
            }
        }
        return null;
    }

    private static String which(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            Path candidate = Path.of(dir, exe);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    /**
     * Runs {@code hexdoc build --branch main} in {@code project} and copies the
     * resulting {@code _site} tree to {@code <outDir>/site}.
     *
     * @return the site directory ({@code <outDir>/site})
     */
    public static Path build(Path project, Path outDir) throws IOException, InterruptedException {
        Command cmd = find();
        if (cmd == null) {
            throw new IOException("hexdoc not found (set -D" + HEXDOC_PROP
                + "=<path> or install it — see doc/genhexbook/README.md)");
        }

        List<String> argv = new ArrayList<>(cmd.argv());
        argv.add("build");
        argv.add("--branch");
        argv.add("main");

        Path log = outDir.resolve("hexdoc-build.log");
        Files.createDirectories(outDir);
        GenHexBook.LOGGER.info("[GenHexBook] running {} (cwd={})", String.join(" ", argv), project);

        ProcessBuilder pb = new ProcessBuilder(argv)
            .directory(project.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
        Map<String, String> env = pb.environment();
        String src = project.resolve("src").toString();
        env.put("PYTHONPATH", env.containsKey("PYTHONPATH")
            ? src + java.io.File.pathSeparator + env.get("PYTHONPATH")
            : src);

        Process proc = pb.start();
        long timeout = Long.parseLong(System.getProperty(TIMEOUT_PROP, "900"));
        if (!proc.waitFor(timeout, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IOException("hexdoc build timed out after " + timeout + "s (log: " + log + ")");
        }
        int exit = proc.exitValue();
        if (exit != 0) {
            throw new IOException("hexdoc build exited " + exit + " (log: " + log + ")\n" + tail(log, 20));
        }

        Path site = locateSite(project.resolve("_site"));
        if (site == null) {
            throw new IOException("hexdoc build finished but no _site output (log: " + log + ")");
        }

        Path dest = outDir.resolve("site");
        copyTree(site, dest);
        writeRootRedirect(dest);
        GenHexBook.LOGGER.info("[GenHexBook] hexdoc site → {}", dest);
        return dest;
    }

    /**
     * hexdoc has no top-level index: each language lives at
     * {@code src/docs/v/latest/main/<lang>/index.html}. Add a redirect so the
     * directory can be served as-is.
     */
    private static void writeRootRedirect(Path site) throws IOException {
        Path index = site.resolve("index.html");
        if (Files.exists(index)) return;
        Path langIndex = site.resolve("src/docs/v/latest/main/en_us/index.html");
        if (!Files.exists(langIndex)) return;
        String html = """
            <!DOCTYPE html>
            <html lang="en">
              <head>
                <meta charset="utf-8" />
                <meta http-equiv="refresh" content="0; url=src/docs/v/latest/main/en_us/index.html" />
                <title>Hex Book</title>
              </head>
              <body>
                <p><a href="src/docs/v/latest/main/en_us/index.html">English</a> ·
                   <a href="src/docs/v/latest/main/zh_cn/index.html">中文</a></p>
              </body>
            </html>
            """;
        Files.writeString(index, html, StandardCharsets.UTF_8);
    }

    /** hexdoc writes the book under {@code _site/src/docs/v/latest/main}. */
    private static Path locateSite(Path root) throws IOException {
        if (!Files.isDirectory(root)) return null;
        Path book = root.resolve("src/docs/v/latest/main");
        if (Files.isDirectory(book) && Files.exists(book.resolve("index.html"))) {
            return root;
        }
        if (Files.exists(root.resolve("index.html"))) return root;
        // fall back to the deepest directory containing index.html
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().equals("index.html"))
                .map(Path::getParent)
                .min(Comparator.comparingInt(Path::getNameCount))
                .orElse(null);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String tail(Path file, int lines) {
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - lines);
            return String.join("\n", all.subList(from, all.size()));
        } catch (IOException e) {
            return "";
        }
    }

    /** Bilingual install hint for the failure path. */
    public static List<String> installHint() {
        return List.of(
            "[GenHexBook] hexdoc not found. / 未找到 hexdoc。",
            "  python3 -m venv /tmp/hexdoc-venv",
            "  /tmp/hexdoc-venv/bin/pip install -r <out>/hexdoc-project/requirements.txt",
            "  -D" + HEXDOC_PROP + "=/tmp/hexdoc-venv/bin/hexdoc  (or put it on PATH)"
        );
    }
}
