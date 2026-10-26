package cn.xm1221.MieLib.installer;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Downloads and verifies mod files.
 *
 * <p>Rules (all idempotent, so re-running the installer is cheap):
 * <ul>
 *   <li>a file already present with the right sha512 is skipped;</li>
 *   <li>a wrong or truncated file is deleted and re-downloaded;</li>
 *   <li>every downloaded candidate is verified against sha512 (and sha1 when the manifest has one)
 *       before it is moved into place; a mismatch tries the next URL and finally fails clearly;</li>
 *   <li>{@code --mod-override name=path} (or {@code HEXCLI_MIELIB_JAR}) uses a local file but still
 *       verifies it against the manifest hashes.</li>
 * </ul>
 */
final class ModDownloader {
    /** Outcome counters for the final summary. */
    static final class Stats {
        int skipped;
        int downloaded;
        int overridden;
    }

    private final HttpClient client;
    private final Path modsDir;
    private final Mirrors mirrors;

    ModDownloader(HttpClient client, Path serverDir) {
        this(client, serverDir, Mirrors.official());
    }

    ModDownloader(HttpClient client, Path serverDir, Mirrors mirrors) {
        this.client = client;
        this.modsDir = serverDir.resolve("mods");
        this.mirrors = mirrors;
    }

    Stats install(List<Manifest.Mod> mods, Map<String, Path> overrides) {
        // Reject unknown override names before touching the filesystem.
        for (String name : overrides.keySet()) {
            if (mods.stream().noneMatch(mod -> mod.name().equals(name))) {
                throw new InstallerException("--mod-override " + name + "=... does not match any mod in the manifest");
            }
        }
        Fs.createDirectories(modsDir);
        Stats stats = new Stats();
        for (Manifest.Mod mod : mods) {
            Path target = modsDir.resolve(mod.fileName());
            Path override = overrides.get(mod.name());
            if (override != null) {
                installOverride(mod, override, target);
                stats.overridden++;
            } else if (isValid(mod, target)) {
                System.out.println("  ok       " + mod.fileName() + " (already present)");
                stats.skipped++;
            } else {
                downloadVerified(mod, target);
                stats.downloaded++;
            }
        }
        return stats;
    }

    /** Verifies every mod that is present; reports the ones that are missing or corrupt. */
    boolean verify(List<Manifest.Mod> mods) {
        boolean allOk = true;
        for (Manifest.Mod mod : mods) {
            Path target = modsDir.resolve(mod.fileName());
            if (!Files.isRegularFile(target)) {
                System.out.println("  MISSING  " + mod.fileName());
                allOk = false;
                continue;
            }
            String problem = check(mod, target);
            if (problem == null) {
                System.out.println("  ok       " + mod.fileName());
            } else {
                System.out.println("  BAD      " + mod.fileName() + " (" + problem + ")");
                allOk = false;
            }
        }
        return allOk;
    }

    private void installOverride(Manifest.Mod mod, Path override, Path target) {
        if (!Files.isRegularFile(override)) {
            throw new InstallerException("--mod-override " + mod.name() + ": no such file " + override);
        }
        String problem = check(mod, override);
        if (problem != null) {
            throw new InstallerException("--mod-override " + mod.name() + "=" + override
                + " does not match the manifest (" + problem + ")");
        }
        try {
            if (Files.isSameFile(override, target)) {
                System.out.println("  local    " + mod.fileName() + " (already in place)");
                return;
            }
        } catch (IOException ignored) {
            // target does not exist yet
        }
        Fs.copy(override, target);
        System.out.println("  local    " + mod.fileName() + " <- " + override);
    }

    private void downloadVerified(Manifest.Mod mod, Path target) {
        List<String> urls = mirrors.candidates(mod.allUrls());
        List<String> failures = new ArrayList<>();
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try {
            for (String url : urls) {
                System.out.println("  fetch    " + mod.fileName() + " <- " + url);
                try {
                    if (!Http.download(client, url, temp, mod.name())) {
                        failures.add(url + ": HTTP 404/410");
                        System.out.println("  skip     HTTP 404/410; trying the next source");
                        continue;
                    }
                } catch (InstallerException e) {
                    failures.add(url + ": " + e.getMessage());
                    System.out.println("  skip     " + e.getMessage() + "; trying the next source");
                    continue;
                }
                String problem = check(mod, temp);
                if (problem == null) {
                    moveIntoPlace(temp, target);
                    System.out.println("  verified " + mod.fileName());
                    return;
                }
                System.out.println("  REJECTED " + url + " (" + problem + "); trying the next URL");
                failures.add(url + ": " + problem);
                deleteQuietly(temp);
            }
        } finally {
            deleteQuietly(temp);
        }
        throw new InstallerException("could not download a verified copy of " + mod.name() + " ("
            + mod.fileName() + "):\n    " + String.join("\n    ", failures));
    }

    /** @return {@code null} when the file matches, otherwise a short human-readable reason. */
    private static String check(Manifest.Mod mod, Path file) {
        try {
            if (mod.size() >= 0) {
                long actual = Files.size(file);
                if (actual != mod.size()) {
                    return "size " + actual + " != expected " + mod.size();
                }
            }
            String sha512 = Hashes.sha512(file);
            if (!sha512.equalsIgnoreCase(mod.sha512())) {
                return "sha512 mismatch (got " + sha512 + ")";
            }
            if (mod.sha1() != null) {
                String sha1 = Hashes.sha1(file);
                if (!sha1.equalsIgnoreCase(mod.sha1())) {
                    return "sha1 mismatch (got " + sha1 + ")";
                }
            }
            return null;
        } catch (IOException e) {
            return "cannot read: " + e.getMessage();
        }
    }

    private static boolean isValid(Manifest.Mod mod, Path file) {
        return Files.isRegularFile(file) && check(mod, file) == null;
    }

    private static void moveIntoPlace(Path temp, Path target) {
        try {
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new InstallerException("cannot move " + temp + " to " + target + ": " + e.getMessage(), e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Parses {@code name=path} pairs from repeated {@code --mod-override} flags. */
    static Map<String, Path> parseOverrides(List<String> values) {
        Map<String, Path> out = new LinkedHashMap<>();
        for (String value : values) {
            int eq = value.indexOf('=');
            if (eq <= 0 || eq == value.length() - 1) {
                throw new InstallerException("--mod-override needs name=path, got: " + value);
            }
            String name = value.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            Path path = Path.of(value.substring(eq + 1).trim());
            if (out.put(name, path) != null) {
                throw new InstallerException("--mod-override for " + name + " given twice");
            }
        }
        // CI convenience: HEXCLI_MIELIB_JAR points at the freshly built MieLib jar.
        String mielib = System.getenv("HEXCLI_MIELIB_JAR");
        if (mielib != null && !mielib.isBlank()) {
            out.putIfAbsent("mielib", Path.of(mielib));
        }
        return out;
    }

    /** Copies the client fat jar next to the server scripts (also accepted from the archive root). */
    static void installClientJar(Path serverDir, Path clientJar) {
        Path target = serverDir.resolve("hexcli-client.jar");
        try {
            if (Files.isSameFile(clientJar, target)) {
                return;
            }
        } catch (IOException ignored) {
            // target does not exist yet
        }
        if (Files.isRegularFile(target) && sameContent(clientJar, target)) {
            System.out.println("  ok       hexcli-client.jar (already present)");
            return;
        }
        Fs.copy(clientJar, target);
        System.out.println("  copied   hexcli-client.jar <- " + clientJar);
    }

    private static boolean sameContent(Path a, Path b) {
        try {
            if (Files.size(a) != Files.size(b)) {
                return false;
            }
            return Hashes.sha512(a).equals(Hashes.sha512(b));
        } catch (IOException e) {
            return false;
        }
    }

    /** Locates the client jar: explicit path, then next to the installer jar, then the archive root. */
    static Path findClientJar(Path explicit, Path serverDir, Path archiveRoot) {
        if (explicit != null) {
            if (!Files.isRegularFile(explicit)) {
                throw new InstallerException("--client-jar does not exist: " + explicit);
            }
            return explicit;
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(archiveRoot.resolve("hexcli-client.jar"));
        candidates.add(serverDir.resolve("hexcli-client.jar"));
        try {
            // Directory containing the running installer jar (the extracted archive root).
            Path installerJar = Path.of(Installer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path parent = Files.isDirectory(installerJar) ? installerJar : installerJar.getParent();
            if (parent != null) {
                candidates.add(parent.resolve("hexcli-client.jar"));
            }
        } catch (Exception ignored) {
            // not running from a jar (tests / IDE); the fixed candidates are enough
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new InstallerException("hexcli-client.jar not found (looked in " + candidates
            + "); pass --client-jar <path>");
    }

    /** Reads a local file or URL for {@code --manifest}. */
    static String readManifestSource(HttpClient client, String source) {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return Http.fetchText(client, source, "--manifest");
        }
        Path path = Path.of(source);
        if (!Files.isRegularFile(path)) {
            throw new InstallerException("--manifest file does not exist: " + path);
        }
        return Fs.read(path, "--manifest");
    }

    /** Reads a classpath resource as UTF-8 (used for the embedded manifest). */
    static String embeddedManifest() {
        return Fs.resource("/hexcli-manifest.json");
    }
}
