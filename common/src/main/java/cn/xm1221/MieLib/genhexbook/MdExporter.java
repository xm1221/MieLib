package cn.xm1221.MieLib.genhexbook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class MdExporter {
    private MdExporter() {}

    public static void write(Path outDir, String lang, BookScanner.BookData book,
                             LangResolver lr, Map<String, PatternInfo> patterns) throws IOException {
        Path mdRoot = outDir.resolve("md").resolve(lang);
        Files.createDirectories(mdRoot);
        StringBuilder summary = new StringBuilder("# thehexbook (").append(lang).append(")\n\n");
        summary.append("- Categories: ").append(book.categories().size()).append("\n");
        summary.append("- Entries: ").append(book.entries().size()).append("\n\n");

        for (BookModels.Category c : book.categories()) {
            summary.append("## ").append(lr.resolveMaybeKey(c.nameKey())).append("\n\n");
            summary.append("- id: `").append(c.id()).append("`\n\n");
        }

        for (BookModels.Entry e : book.entries()) {
            String file = PatchouliText.sanitize(e.id()) + ".md";
            summary.append("- [").append(lr.resolveMaybeKey(e.nameKey())).append("](").append(file).append(")\n");
            StringBuilder body = new StringBuilder();
            body.append("# ").append(lr.resolveMaybeKey(e.nameKey())).append("\n\n");
            body.append("`").append(e.id()).append("` · category `").append(e.category()).append("`\n\n");
            int i = 0;
            for (BookModels.Page page : e.pages()) {
                body.append("## Page ").append(++i).append("\n\n");
                body.append(render(page, lr, patterns)).append("\n\n");
            }
            Files.writeString(mdRoot.resolve(file), body.toString(), StandardCharsets.UTF_8);
        }
        Files.writeString(mdRoot.resolve("SUMMARY.md"), summary.toString(), StandardCharsets.UTF_8);
    }

    private static String render(BookModels.Page page, LangResolver lr, Map<String, PatternInfo> patterns) {
        if (page instanceof BookModels.TextPage t) return PatchouliText.toMarkdown(t.text(), lr);
        if (page instanceof BookModels.PatternPage p) {
            PatternInfo info = patterns.get(p.opId());
            if (info == null) info = PatternLookup.lookup(p.opId());
            StringBuilder b = new StringBuilder();
            b.append("**Pattern** `").append(p.opId()).append("`");
            if (!p.input().isEmpty()) b.append(" · in `").append(p.input()).append("`");
            if (!p.output().isEmpty()) b.append(" · out `").append(p.output()).append("`");
            b.append("\n\n");
            if (info != null) {
                if (info.perWorld()) b.append("> per-world / great spell (canonical prototype)\n\n");
                b.append("```\n").append(info.startDir()).append(" ").append(info.signature()).append("\n```\n\n");
            }
            if (!p.text().isEmpty()) b.append(PatchouliText.toMarkdown(p.text(), lr)).append("\n");
            return b.toString();
        }
        if (page instanceof BookModels.SpotlightPage s)
            return "Spotlight `" + s.item() + "`\n\n" + PatchouliText.toMarkdown(s.text(), lr);
        if (page instanceof BookModels.CraftingPage c)
            return "Crafting `" + c.recipe() + "`\n\n" + PatchouliText.toMarkdown(c.text(), lr);
        if (page instanceof BookModels.ImagePage img)
            return "Images: " + String.join(", ", img.images()) + "\n\n" + PatchouliText.toMarkdown(img.text(), lr);
        if (page instanceof BookModels.OtherPage o)
            return "Page type `" + o.type() + "`\n\n```json\n" + o.rawJson() + "\n```\n";
        return "";
    }
}
