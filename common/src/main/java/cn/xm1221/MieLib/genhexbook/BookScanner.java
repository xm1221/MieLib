package cn.xm1221.MieLib.genhexbook;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-safe thehexbook loader. Dedicated servers do not expose client {@code assets/}
 * through ResourceManager, so we read Patchouli JSON straight from mod jars under {@code mods/}.
 */
public final class BookScanner {
    public static final String BOOK = "thehexbook";

    private BookScanner() {}

    public record BookData(
        List<BookModels.Category> categories,
        List<BookModels.Entry> entries
    ) {}

    public static BookData scan(MinecraftServer server, String lang) {
        Map<String, BookModels.Category> cats = new LinkedHashMap<>();
        Map<String, BookModels.Entry> ents = new LinkedHashMap<>();

        loadLangTree(server, lang, cats, ents);
        if (!"en_us".equals(lang)) {
            Map<String, BookModels.Category> fbCats = new LinkedHashMap<>();
            Map<String, BookModels.Entry> fbEnts = new LinkedHashMap<>();
            loadLangTree(server, "en_us", fbCats, fbEnts);
            for (var e : fbCats.entrySet()) cats.putIfAbsent(e.getKey(), e.getValue());
            for (var e : fbEnts.entrySet()) ents.putIfAbsent(e.getKey(), e.getValue());
        }

        List<BookModels.Category> catList = new ArrayList<>(cats.values());
        catList.sort(Comparator.comparingInt(BookModels.Category::sortnum).thenComparing(BookModels.Category::id));
        List<BookModels.Entry> entList = new ArrayList<>(ents.values());
        entList.sort(Comparator.comparingInt(BookModels.Entry::sortnum).thenComparing(BookModels.Entry::id));
        GenHexBook.LOGGER.info("[GenHexBook] scanned lang={} → {} categories, {} entries", lang, catList.size(), entList.size());
        return new BookData(catList, entList);
    }

    private static void loadLangTree(MinecraftServer server, String lang,
                                     Map<String, BookModels.Category> cats,
                                     Map<String, BookModels.Entry> ents) {
        String catPrefix = "patchouli_books/" + BOOK + "/" + lang + "/categories/";
        String entPrefix = "patchouli_books/" + BOOK + "/" + lang + "/entries/";
        ModAssetWalker.walk(server, under -> under.startsWith(catPrefix) && under.endsWith(".json"), (file, in) -> {
            try {
                String id = idFromUnder(file.namespace(), underAfter(file.pathUnderAssets(), catPrefix));
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                BookModels.Category c = parseCategory(id, o);
                cats.putIfAbsent(c.id(), c);
            } catch (Exception ex) {
                GenHexBook.LOGGER.warn("[GenHexBook] category {}: {}", file.pathUnderAssets(), ex.toString());
            }
        });
        ModAssetWalker.walk(server, under -> under.startsWith(entPrefix) && under.endsWith(".json"), (file, in) -> {
            try {
                String id = idFromUnder(file.namespace(), underAfter(file.pathUnderAssets(), entPrefix));
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                BookModels.Entry e = parseEntry(id, o);
                ents.putIfAbsent(e.id(), e);
            } catch (Exception ex) {
                GenHexBook.LOGGER.warn("[GenHexBook] entry {}: {}", file.pathUnderAssets(), ex.toString());
            }
        });
    }

    private static String underAfter(String under, String prefix) {
        return under.substring(prefix.length());
    }

    private static String idFromUnder(String ns, String rest) {
        if (rest.endsWith(".json")) rest = rest.substring(0, rest.length() - 5);
        return ns + ":" + rest;
    }

    private static BookModels.Category parseCategory(String id, JsonObject o) {
        return new BookModels.Category(
            id,
            str(o, "name"),
            str(o, "description"),
            str(o, "parent"),
            o.has("sortnum") ? o.get("sortnum").getAsInt() : 0,
            str(o, "icon")
        );
    }

    private static BookModels.Entry parseEntry(String id, JsonObject o) {
        List<BookModels.Page> pages = BookModels.emptyPages();
        if (o.has("pages") && o.get("pages").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("pages")) {
                if (el.isJsonObject()) pages.add(parsePage(el.getAsJsonObject()));
                else if (el.isJsonPrimitive()) pages.add(new BookModels.TextPage(el.getAsString()));
            }
        }
        return new BookModels.Entry(
            id,
            str(o, "name"),
            str(o, "category"),
            str(o, "icon"),
            o.has("sortnum") ? o.get("sortnum").getAsInt() : 0,
            str(o, "advancement"),
            pages
        );
    }

    private static BookModels.Page parsePage(JsonObject o) {
        String type = o.has("type") ? o.get("type").getAsString() : "patchouli:text";
        String t = type.contains(":") ? type : "patchouli:" + type;
        return switch (t) {
            case "patchouli:text", "text" -> new BookModels.TextPage(str(o, "text"));
            case "hexcasting:pattern", "hexcasting:manual_pattern", "hexcasting:lookup_pattern" ->
                new BookModels.PatternPage(str(o, "op_id"), str(o, "input"), str(o, "output"),
                    str(o, "text"), str(o, "anchor"));
            case "patchouli:spotlight", "spotlight" ->
                new BookModels.SpotlightPage(str(o, "item"), str(o, "text"), str(o, "title"));
            case "patchouli:crafting", "crafting" ->
                new BookModels.CraftingPage(firstRecipe(o), str(o, "text"), str(o, "title"));
            case "patchouli:image", "image" -> {
                List<String> imgs = new ArrayList<>();
                if (o.has("images") && o.get("images").isJsonArray()) {
                    JsonArray a = o.getAsJsonArray("images");
                    for (JsonElement e : a) imgs.add(e.getAsString());
                }
                yield new BookModels.ImagePage(imgs, str(o, "text"), str(o, "title"));
            }
            default -> new BookModels.OtherPage(t, o.toString());
        };
    }

    private static String firstRecipe(JsonObject o) {
        if (o.has("recipe")) return o.get("recipe").getAsString();
        if (o.has("recipes") && o.get("recipes").isJsonArray() && !o.getAsJsonArray("recipes").isEmpty())
            return o.getAsJsonArray("recipes").get(0).getAsString();
        return "";
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return "";
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive()) return e.getAsString();
        return e.toString();
    }
}
