package cn.xm1221.MieLib.installer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parsed and validated {@code hexcli-manifest.json}.
 *
 * <p>The manifest is the single source of truth for what the installer downloads:
 * the Fabric server launcher plus one entry per runtime mod.
 */
final class Manifest {
    /** A single downloadable mod file. */
    record Mod(
        String name,
        String source,
        String project,
        String versionId,
        String fileName,
        String url,
        String sha512,
        String sha1,
        long size,
        List<String> fallbackUrls
    ) {
        /** Primary URL followed by the fallbacks, in order. */
        List<String> allUrls() {
            List<String> urls = new ArrayList<>(1 + fallbackUrls.size());
            urls.add(url);
            urls.addAll(fallbackUrls);
            return urls;
        }
    }

    /** The Fabric server launcher jar (downloads the vanilla server from Mojang on first start). */
    record ServerLauncher(String url, String fileName) {
    }

    final String installerVersion;
    final String minecraft;
    final String fabricLoader;
    final String fabricInstaller;
    final ServerLauncher serverLauncher;
    final List<Mod> mods;

    private Manifest(String installerVersion, String minecraft, String fabricLoader, String fabricInstaller,
                     ServerLauncher serverLauncher, List<Mod> mods) {
        this.installerVersion = installerVersion;
        this.minecraft = minecraft;
        this.fabricLoader = fabricLoader;
        this.fabricInstaller = fabricInstaller;
        this.serverLauncher = serverLauncher;
        this.mods = List.copyOf(mods);
    }

    static Manifest parse(String json, String origin) {
        Map<String, Object> root = Json.asObject(Json.parse(json), origin);
        String installerVersion = Json.requiredString(root, "installerVersion", origin);
        String minecraft = Json.requiredString(root, "minecraft", origin);
        String fabricLoader = Json.requiredString(root, "fabricLoader", origin);
        String fabricInstaller = Json.requiredString(root, "fabricInstaller", origin);

        Map<String, Object> launcher = Json.asObject(
            require(root, "serverLauncher", origin), origin + ".serverLauncher");
        ServerLauncher serverLauncher = new ServerLauncher(
            requireHttpUrl(launcher, "url", origin + ".serverLauncher"),
            safeFileName(Json.requiredString(launcher, "fileName", origin + ".serverLauncher"),
                origin + ".serverLauncher.fileName"));

        List<Object> rawMods = Json.asArray(require(root, "mods", origin), origin + ".mods");
        List<Mod> mods = new ArrayList<>(rawMods.size());
        for (int i = 0; i < rawMods.size(); i++) {
            String what = origin + ".mods[" + i + "]";
            Map<String, Object> m = Json.asObject(rawMods.get(i), what);
            String name = Json.requiredString(m, "name", what);
            String source = Json.requiredString(m, "source", what);
            String project = Json.optionalString(m, "project", what);
            String versionId = Json.optionalString(m, "versionId", what);
            String fileName = safeFileName(Json.requiredString(m, "fileName", what), what + ".fileName");
            String url = requireHttpUrl(m, "url", what);
            String sha512 = requireHash(m, "sha512", 128, what);
            String sha1 = Json.optionalString(m, "sha1", what);
            if (sha1 != null) {
                sha1 = requireHash(m, "sha1", 40, what);
            }
            long size = m.containsKey("size") && m.get("size") != null
                ? Json.asLong(m.get("size"), what + ".size") : -1L;
            List<String> fallbacks = new ArrayList<>();
            for (String fallback : Json.optionalStringList(m, "fallbackUrls", what)) {
                fallbacks.add(requireUrl(fallback, what + ".fallbackUrls"));
            }
            if (mods.stream().anyMatch(existing -> existing.name().equals(name))) {
                throw new InstallerException(origin + ": duplicate mod \"" + name + "\"");
            }
            if (mods.stream().anyMatch(existing -> existing.fileName().equals(fileName))) {
                throw new InstallerException(origin + ": duplicate mod file \"" + fileName + "\"");
            }
            mods.add(new Mod(name, source, project, versionId, fileName, url, sha512, sha1, size, List.copyOf(fallbacks)));
        }
        return new Manifest(installerVersion, minecraft, fabricLoader, fabricInstaller, serverLauncher, mods);
    }

    private static Object require(Map<String, Object> object, String key, String what) {
        Object value = object.get(key);
        if (value == null) {
            throw new InstallerException(what + " is missing \"" + key + "\"");
        }
        return value;
    }

    private static String requireHttpUrl(Map<String, Object> object, String key, String what) {
        return requireUrl(Json.requiredString(object, key, what), what + "." + key);
    }

    private static String requireUrl(String url, String what) {
        Http.uri(url, what); // validates scheme and host
        return url;
    }

    /** Hashes are compared as lowercase hex; reject anything that is not exactly the right length. */
    private static String requireHash(Map<String, Object> object, String key, int length, String what) {
        String value = Json.requiredString(object, key, what).toLowerCase(Locale.ROOT);
        if (value.length() != length || !value.matches("[0-9a-f]+")) {
            throw new InstallerException(what + "." + key + " is not a valid " + (length == 128 ? "sha512" : "sha1")
                + " hash: " + value);
        }
        return value;
    }

    /**
     * File names come from a remote document and are used as path components, so refuse
     * anything that could escape {@code <dir>/mods}.
     */
    private static String safeFileName(String name, String what) {
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")
            || name.indexOf('\0') >= 0 || name.indexOf(':') >= 0) {
            throw new InstallerException(what + " is not a safe file name: " + name);
        }
        return name;
    }
}
