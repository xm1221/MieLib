package cn.xm1221.MieLib.installer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Download source selection: rewrites manifest URLs to a mirror before downloading.
 *
 * <p>Rules are simple prefix rewrites ({@code from=to}); the first matching rule wins. Hashes are
 * always checked against the manifest, so a mirror can never change what gets installed. Unless
 * {@code --no-fallback} is given, the original (official) URL is still tried after the mirror.
 *
 * <p>Sources, combined in this order:
 * <ol>
 *   <li>{@code --url-rewrite from=to} (repeatable) / {@code HEXCLI_URL_REWRITE} ({@code ;}-separated)</li>
 *   <li>{@code --github-proxy <prefix>} / {@code HEXCLI_GITHUB_PROXY}: {@code <prefix>https://github.com/...}</li>
 *   <li>the preset from {@code --mirror <name>} / {@code HEXCLI_MIRROR}</li>
 * </ol>
 */
final class Mirrors {
    /** Known presets. */
    static final Map<String, List<String[]>> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("official", List.of());
        // MCIM (mod.mcimirror.top) serves cdn.modrinth.com byte-for-byte; BMCLAPI mirrors Fabric
        // meta and maven. BMCLAPI's fabric-meta does not serve the server-launcher jar for every
        // version, which is fine: the official URL is the fallback.
        PRESETS.put("bmclapi", List.of(
            new String[] {"https://cdn.modrinth.com/", "https://mod.mcimirror.top/"},
            new String[] {"https://meta.fabricmc.net/", "https://bmclapi2.bangbang93.com/fabric-meta/"},
            new String[] {"https://maven.fabricmc.net/", "https://bmclapi2.bangbang93.com/maven/"}
        ));
    }

    static final Map<String, String> ALIASES = Map.of(
        "default", "official",
        "none", "official",
        "cn", "bmclapi",
        "china", "bmclapi",
        "mcim", "bmclapi"
    );

    private final List<String[]> rules;
    private final String description;
    final boolean fallbackToOfficial;

    private Mirrors(List<String[]> rules, String description, boolean fallbackToOfficial) {
        this.rules = List.copyOf(rules);
        this.description = description;
        this.fallbackToOfficial = fallbackToOfficial;
    }

    static Mirrors official() {
        return new Mirrors(List.of(), "official", true);
    }

    /**
     * Builds the rule set.
     *
     * @param preset      {@code --mirror} value (or {@code null} for {@code HEXCLI_MIRROR} / official)
     * @param rewrites    {@code --url-rewrite} values (env {@code HEXCLI_URL_REWRITE} is appended)
     * @param githubProxy {@code --github-proxy} value (or {@code null} for {@code HEXCLI_GITHUB_PROXY})
     */
    static Mirrors create(String preset, List<String> rewrites, String githubProxy, boolean noFallback) {
        return create(preset, rewrites, githubProxy, noFallback,
            System.getenv("HEXCLI_MIRROR"), System.getenv("HEXCLI_URL_REWRITE"), System.getenv("HEXCLI_GITHUB_PROXY"));
    }

    static Mirrors create(String preset, List<String> rewrites, String githubProxy, boolean noFallback,
                          String envMirror, String envRewrite, String envGithubProxy) {
        List<String[]> rules = new ArrayList<>();
        List<String> parts = new ArrayList<>();

        List<String> allRewrites = new ArrayList<>(rewrites);
        if (envRewrite != null && !envRewrite.isBlank()) {
            for (String item : envRewrite.split("[;\\s]+")) {
                if (!item.isBlank()) {
                    allRewrites.add(item);
                }
            }
        }
        for (String value : allRewrites) {
            int eq = value.indexOf('=');
            if (eq <= 0 || eq == value.length() - 1) {
                throw new InstallerException("--url-rewrite needs from=to (URL prefixes), got: " + value);
            }
            String from = value.substring(0, eq).trim();
            String to = value.substring(eq + 1).trim();
            Http.uri(from, "--url-rewrite source");
            Http.uri(to, "--url-rewrite target");
            rules.add(new String[] {from, to});
            parts.add(from + " -> " + to);
        }

        String proxy = githubProxy != null ? githubProxy : blankToNull(envGithubProxy);
        if (proxy != null) {
            proxy = proxy.trim();
            Http.uri(proxy, "--github-proxy");
            if (!proxy.endsWith("/")) {
                proxy = proxy + "/";
            }
            rules.add(new String[] {"https://github.com/", proxy + "https://github.com/"});
            parts.add("github via " + proxy);
        }

        String name = preset != null ? preset : blankToNull(envMirror);
        if (name == null) {
            name = "official";
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        key = ALIASES.getOrDefault(key, key);
        List<String[]> presetRules = PRESETS.get(key);
        if (presetRules == null) {
            throw new InstallerException("unknown --mirror / HEXCLI_MIRROR \"" + name + "\" (known: "
                + String.join(", ", PRESETS.keySet()) + "; for anything else use --url-rewrite from=to)");
        }
        rules.addAll(presetRules);
        parts.add(0, key);

        return new Mirrors(rules, String.join(", ", parts), !noFallback);
    }

    boolean isOfficial() {
        return rules.isEmpty();
    }

    String description() {
        return description + (rules.isEmpty() || !fallbackToOfficial ? "" : " (official URLs as fallback)");
    }

    /** Applies the first matching rule, or returns {@code url} unchanged. */
    String rewrite(String url) {
        for (String[] rule : rules) {
            if (url.startsWith(rule[0])) {
                return rule[1] + url.substring(rule[0].length());
            }
        }
        return url;
    }

    /** Mirror URLs first, then (with fallback enabled) the original URLs; no duplicates. */
    List<String> candidates(List<String> urls) {
        List<String> out = new ArrayList<>();
        for (String url : urls) {
            String rewritten = rewrite(url);
            if (!out.contains(rewritten)) {
                out.add(rewritten);
            }
        }
        if (fallbackToOfficial) {
            for (String url : urls) {
                if (!out.contains(url)) {
                    out.add(url);
                }
            }
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
