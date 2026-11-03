package cn.xm1221.MieLib.genhexbook;

import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Orchestrates scan → render → optional markdown. */
public final class GenHexBookGenerator {
    private GenHexBookGenerator() {}

    public record Result(int categories, int entries, int patterns, Path outDir) {}

    public static Result generate(MinecraftServer server, Path outDir, String lang, boolean md) throws Exception {
        Files.createDirectories(outDir);
        Map<String, PatternInfo> patterns = PatternLookup.all();
        boolean reveal = GenHexBookConfig.revealGreatSpells();

        List<String> langs = new ArrayList<>();
        if ("both".equals(lang)) {
            langs.add("en_us");
            langs.add("zh_cn");
        } else {
            langs.add(lang);
        }

        int cats = 0, ents = 0;
        for (String l : langs) {
            LangResolver lr = LangResolver.load(server, l);
            // Fall back: if zh_cn map tiny, still overlay en_us keys already via Language
            BookScanner.BookData book = BookScanner.scan(server, l);
            cats = Math.max(cats, book.categories().size());
            ents = Math.max(ents, book.entries().size());
            HtmlRenderer.writeSite(outDir, l, book, lr, patterns, reveal);
            if (md) {
                MdExporter.write(outDir, l, book, lr, patterns);
            }
            GenHexBook.LOGGER.info("[GenHexBook] wrote lang={} cats={} entries={}", l,
                book.categories().size(), book.entries().size());
        }
        return new Result(cats, ents, patterns.size(), outDir);
    }
}
