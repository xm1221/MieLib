package cn.xm1221.MieLib.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates the Java chosen with {@code --java} / {@code HEXCLI_JAVA}: must exist and be 17+. */
final class JavaCheck {
    static final int MIN_MAJOR = 17;
    private static final Pattern VERSION = Pattern.compile("version \"(1\\.)?(\\d+)");

    private JavaCheck() {
    }

    /**
     * @param value a java executable or a JDK/JRE home directory, or {@code null}
     * @return the absolute java executable, or {@code null} when {@code value} is null/blank
     */
    static String resolve(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Path path = Path.of(value.trim());
        if (Files.isDirectory(path)) {
            Path found = null;
            for (String rel : new String[] {"bin/java", "bin/java.exe", "Contents/Home/bin/java"}) {
                if (Files.isRegularFile(path.resolve(rel))) {
                    found = path.resolve(rel);
                    break;
                }
            }
            if (found == null) {
                throw new InstallerException("--java / HEXCLI_JAVA: " + path + " is a directory without bin/java");
            }
            path = found;
        }
        if (!Files.isRegularFile(path)) {
            throw new InstallerException("--java / HEXCLI_JAVA: no such file " + path);
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (!windows && !Files.isExecutable(path)) {
            throw new InstallerException("--java / HEXCLI_JAVA: not executable: " + path);
        }
        String absolute = path.toAbsolutePath().normalize().toString();
        int major = major(absolute);
        if (major < 0) {
            throw new InstallerException("--java / HEXCLI_JAVA: " + absolute + " is not a working Java (no version from \"-version\")");
        }
        if (major < MIN_MAJOR) {
            throw new InstallerException("--java / HEXCLI_JAVA: " + absolute + " is Java " + major
                + ", HexCLI needs Java " + MIN_MAJOR + "+");
        }
        return absolute;
    }

    /** Major version from {@code java -version}, or -1. */
    static int major(String java) {
        try {
            Process process = new ProcessBuilder(java, "-version").redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            return parseMajor(output);
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    static int parseMajor(String versionOutput) {
        Matcher m = VERSION.matcher(versionOutput);
        return m.find() ? Integer.parseInt(m.group(2)) : -1;
    }
}
