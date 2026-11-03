package cn.xm1221.MieLib.genhexbook;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-safe thehexbook loader: walks ResourceManager JSON under
 * assets/.../patchouli_books/thehexbook/&lt;lang&gt;/{categories,entries}/**.
 * Does not use Patchouli's client BookContentsBuilder.
 */
public final class BookScanner {
    public static final String BOOK = "thehexbook";

    private BookScanner() {}

    public record BookData(
        List<BookModels.Category> categories,
        List<BookModels.Entry> entries
    ) {}

    public static BookData scan(MinecraftServer server, String lang) {
        ResourceManager rm = server.getResourceManager();
        Map<String, BookModels.Category> cats = new LinkedHashMap<>();
        Map<String, BookModels.Entry> ents = new LinkedHashMap<>();

        // Prefer requested lang, then fall back to en_us for missing ids
        loadLangTree(rm, lang, cats, ents);
        if (!"en_us".equals(lang)) {
            Map<String, BookModels.Category> fbCats = new LinkedHashMap<>();
            Map<String, BookModels.Entry> fbEnts = new LinkedHashMap<>();
            loadLangTree(rm, "en_us", fbCats, fbEnts);
            for (var e : fbCats.entrySet()) cats.putIfAbsent(e.getKey(), e.getValue());
            for (var e : fbEnts.entrySet()) ents.putIfAbsent(e.getKey(), e.getValue());
        }

        List<BookModels.Category> catList = new ArrayList<>(cats.values());
        catList.sort(Comparator.comparingInt(BookModels.Category::sortnum).thenComparing(BookModels.Category::id));
        List<BookModels.Entry> entList = new ArrayList<>(ents.values());
        entList.sort(Comparator.comparingInt(BookModels.Entry::sortnum).thenComparing(BookModels.Entry::id));
        return new BookData(catList, entList);
    }

    private static void loadLangTree(ResourceManager rm, String lang,
                                     Map<String, BookModels.Category> cats,
                                     Map<String, BookModels.Entry> ents) {
        String prefixCat = "patchouli_books/" + BOOK + "/" + lang + "/categories";
        String prefixEnt = "patchouli_books/" + BOOK + "/" + lang + "/entries";
        try {
            Map<ResourceLocation, Resource> catRes = rm.listResources(prefixCat, rl -> rl.getPath().endsWith(".json"));
            for (var e : catRes.entrySet()) {
                try {
                    BookModels.Category c = parseCategory(e.getKey(), e.getValue());
                    if (c != null) cats.putIfAbsent(c.id(), c);
                } catch (Exception ex) {
                    GenHexBook.LOGGER.warn("[GenHexBook] category {}: {}", e.getKey(), ex.toString());
                }
            }
        } catch (Exception ex) {
            GenHexBook.LOGGER.warn("[GenHexBook] list categories ({}): {}", lang, ex.toString());
        }
        try {
            Map<ResourceLocation, Resource> entRes = rm.listResources(prefixEnt, rl -> rl.getPath().endsWith(".json"));
            for (var e : entRes.entrySet()) {
                try {
                    BookModels.Entry ent = parseEntry(e.getKey(), e.getValue());
                    if (ent != null) ents.putIfAbsent(ent.id(), ent);
                } catch (Exception ex) {
                    GenHexBook.LOGGER.warn("[GenHexBook] entry {}: {}", e.getKey(), ex.toString());
                }
            }
        } catch (Exception ex) {
            GenHexBook.LOGGER.warn("[GenHexBook] list entries ({}): {}", lang, ex.toString());
        }
    }

    private static String idFromPath(ResourceLocation rl, String kind) {
        // assets/<ns>/patchouli_books/thehexbook/<lang>/<kind>/foo/bar.json
        // → <ns>:foo/bar
        String path = rl.getPath();
        String marker = "/" + kind + "/";
        int i = path.indexOf(marker);
        String rest = i >= 0 ? path.substring(i + marker.length()) : path;
        if (rest.endsWith(".json")) rest = rest.substring(0, rest.length() - 5);
        return rl.getNamespace() + ":" + rest;
    }

    private static BookModels.Category parseCategory(ResourceLocation rl, Resource res) throws Exception {
        try (var reader = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(reader).getAsJsonObject();
            String id = idFromPath(rl, "categories");
            return new BookModels.Category(
                id,
                str(o, "name"),
                str(o, "description"),
                str(o, "parent"),
                o.has("sortnum") ? o.get("sortnum").getAsInt() : 0,
                str(o, "icon")
            );
        }
    }

    private static BookModels.Entry parseEntry(ResourceLocation rl, Resource res) throws Exception {
        try (var reader = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(reader).getAsJsonObject();
            String id = idFromPath(rl, "entries");
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
