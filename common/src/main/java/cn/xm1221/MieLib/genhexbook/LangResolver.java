package cn.xm1221.MieLib.genhexbook;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.locale.Language;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves translation keys for a target lang. Loads lang JSON / flatten.json5
 * from mod jars (assets are not on the dedicated-server ResourceManager).
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
        String jsonName = "lang/" + lang + ".json";
        String flatName = "lang/" + lang + ".flatten.json5";
        ModAssetWalker.walk(server, under -> under.equals(jsonName) || under.equals(flatName), (file, in) -> {
            try {
                if (file.pathUnderAssets().endsWith(".json")) {
                    JsonObject obj = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                    for (var en : obj.entrySet()) {
                        if (en.getValue().isJsonPrimitive()) {
                            r.map.putIfAbsent(en.getKey(), en.getValue().getAsString());
                        }
                    }
                } else {
                    try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        Map<String, String> tmp = new HashMap<>();
                        parseFlattenJson5(reader, tmp);
                        tmp.forEach(r.map::putIfAbsent);
                    }
                }
            } catch (Exception ex) {
                GenHexBook.LOGGER.warn("[GenHexBook] lang {}:{}: {}", file.namespace(), file.pathUnderAssets(), ex.toString());
            }
        });
        GenHexBook.LOGGER.info("[GenHexBook] lang {} loaded {} keys", lang, r.map.size());
        return r;
    }

    static void parseFlattenJson5(BufferedReader reader, Map<String, String> out) throws Exception {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            String t = line.trim();
            if (t.startsWith("//")) continue;
            int cmt = t.indexOf("//");
            if (cmt >= 0) {
                // only strip if // is outside quotes — good enough for flatten files
                boolean inQ = false;
                int cut = -1;
                for (int i = 0; i < t.length() - 1; i++) {
                    char c = t.charAt(i);
                    if (c == '"' && (i == 0 || t.charAt(i - 1) != '\\')) inQ = !inQ;
                    if (!inQ && c == '/' && t.charAt(i + 1) == '/') { cut = i; break; }
                }
                if (cut >= 0) t = t.substring(0, cut);
            }
            sb.append(t).append('\n');
        }
        String body = sb.toString().trim();
        if (!body.startsWith("{")) body = "{" + body;
        if (!body.endsWith("}")) body = body + "}";
        body = body.replaceAll(",\\s*}", "}");
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            for (var en : obj.entrySet()) {
                JsonElement v = en.getValue();
                if (v.isJsonPrimitive()) out.put(en.getKey(), v.getAsString());
            }
        } catch (Exception e) {
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
        try {
            if (Language.getInstance().has(key)) return Language.getInstance().getOrDefault(key);
        } catch (Exception ignored) {}
        return key;
    }

    public String resolveMaybeKey(String raw) {
        if (raw == null) return "";
        if (raw.startsWith("hexcasting.") || raw.startsWith("patchouli.") || raw.startsWith("mielib.")
            || raw.startsWith("miehex.") || raw.startsWith("hexparse.") || raw.startsWith("minecraft.")) {
            return get(raw);
        }
        if (raw.indexOf(' ') < 0 && raw.indexOf('.') > 0 && raw.length() < 200 && !raw.contains("\n")) {
            String v = get(raw);
            if (!v.equals(raw)) return v;
        }
        return raw;
    }
}
