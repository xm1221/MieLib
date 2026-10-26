package cn.xm1221.MieLib.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/** Small filesystem helpers. */
final class Fs {
    private Fs() {
    }

    static void createDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new InstallerException("cannot create directory " + dir + ": " + e.getMessage(), e);
        }
    }

    /** Writes {@code content} only when the file does not exist yet, so user edits survive re-runs. */
    static boolean writeIfAbsent(Path file, String content) {
        if (Files.exists(file)) {
            return false;
        }
        write(file, content);
        return true;
    }

    static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InstallerException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /** Writes a file that has to be executable on Unix. No-op for content-identical files. */
    static boolean writeExecutable(Path file, String content) {
        if (Files.isRegularFile(file)) {
            try {
                if (Files.readString(file, StandardCharsets.UTF_8).equals(content)) {
                    makeExecutable(file);
                    return false;
                }
            } catch (IOException ignored) {
                // fall through and overwrite
            }
        }
        write(file, content);
        makeExecutable(file);
        return true;
    }

    static void makeExecutable(Path file) {
        // setExecutable is a no-op / returns false on Windows; that is fine.
        file.toFile().setExecutable(true, false);
        file.toFile().setReadable(true, false);
    }

    /** Copies {@code source} to {@code target} via a temp file so a failed copy never leaves a half jar. */
    static void copy(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new InstallerException("cannot copy " + source + " to " + target + ": " + e.getMessage(), e);
        }
    }

    static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Reads a classpath resource as UTF-8; fails loudly if the jar was built without it. */
    static String resource(String name) {
        try (InputStream in = Installer.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new InstallerException("installer jar is missing embedded resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InstallerException("cannot read embedded resource " + name + ": " + e.getMessage(), e);
        }
    }

    static String read(Path file, String what) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InstallerException("cannot read " + what + " " + file + ": " + e.getMessage(), e);
        }
    }
}
