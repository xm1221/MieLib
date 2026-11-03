package cn.xm1221.MieLib.genhexbook;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.locale.Language;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves translation keys for a target lang. Loads assets/.../lang/&lt;code&gt;.json
 * and a light parse of *.flatten.json5 (Hex Casting style).
 */
public final class LangResolver {
    private final Map<String, String> map = new HashMap<>();
    private final String lang;

    public LangResolver(String lang) {
        this.lang = lang;
    }

    public String lang() { return lang; }

    public static LangResolver load(MinecraftServer server, String lang) {
        LangResolver r = new LangResolver(lang);
        ResourceManager rm = server.getResourceManager();
        // JSON lang files
        try {
            Map<net.minecraft.resources.ResourceLocation, Resource> found =
                rm.listResources("lang", rl -> {
                    String p = rl.getPath();
                    return p.equals("lang/" + lang + ".json") || p.equals("lang/" + lang + ".flatten.json5");
                });
            for (var e : found.entrySet()) {
                String path = e.getKey().getPath();
                try (var in = e.getValue().open();
                     var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    if (path.endsWith(".json")) {
                        JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                        for (var en : obj.entrySet()) {
                            if (en.getValue().isJsonPrimitive()) {
                                r.map.put(en.getKey(), en.getValue().getAsString());
                            }
                        }
                    } else if (path.endsWith(".flatten.json5")) {
                        parseFlattenJson5(reader, r.map);
                    }
                } catch (Exception ex) {
                    GenHexBook.LOGGER.warn("[GenHexBook] lang load {}: {}", e.getKey(), ex.toString());
                }
            }
        } catch (Exception ex) {
            GenHexBook.LOGGER.warn("[GenHexBook] listResources lang failed: {}", ex.toString());
        }
        return r;
    }

    /** Very small json5-flat parser: "key": "value", lines; strips // comments. */
    static void parseFlattenJson5(BufferedReader reader, Map<String, String> out) throws Exception {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            String t = line.trim();
            if (t.startsWith("//")) continue;
            int cmt = t.indexOf("//");
            if (cmt >= 0) t = t.substring(0, cmt);
            sb.append(t).append('\n');
        }
        String body = sb.toString().trim();
        if (!body.startsWith("{")) body = "{" + body;
        if (!body.endsWith("}")) body = body + "}";
        // trailing commas
        body = body.replaceAll(",\\s*}", "}");
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            for (var en : obj.entrySet()) {
                JsonElement v = en.getValue();
                if (v.isJsonPrimitive()) out.put(en.getKey(), v.getAsString());
            }
        } catch (Exception e) {
            // fallback line-based
            for (String l : body.split("\n")) {
                l = l.trim();
                if (!l.contains(":")) continue;
                int colon = l.indexOf(':');
                String k = l.substring(0, colon).trim().replaceAll("^\"|\"$", "");
                String v = l.substring(colon + 1).trim().replaceAll(",$", "");
                if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                v = v.replace("\\n", "\n").replace("\\\"", "\"");
                if (!k.isEmpty()) out.put(k, v);
            }
        }
    }

    public String get(String key) {
        if (key == null) return "";
        String v = map.get(key);
        if (v != null) return v;
        // Minecraft language (usually en_us on dedicated server)
        try {
            if (Language.getInstance().has(key)) return Language.getInstance().getOrDefault(key);
        } catch (Exception ignored) {}
        return key;
    }

    /** If value looks like a bare translation key, resolve; else return as-is. */
    public String resolveMaybeKey(String raw) {
        if (raw == null) return "";
        if (raw.indexOf(' ') < 0 && raw.indexOf('.') > 0 && raw.length() < 200 && !raw.contains("\n")) {
            String v = get(raw);
            if (!v.equals(raw)) return v;
        }
        // Also try always as key when it starts with known prefixes
        if (raw.startsWith("hexcasting.") || raw.startsWith("patchouli.") || raw.startsWith("mielib.")
            || raw.startsWith("miehex.") || raw.startsWith("hexparse.")) {
            return get(raw);
        }
        return raw;
    }
}
