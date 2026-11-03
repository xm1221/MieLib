package cn.xm1221.MieLib.genhexbook;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HtmlRenderer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private HtmlRenderer() {}

    public static void writeSite(Path outDir, String lang, BookScanner.BookData book,
                                 LangResolver lr, Map<String, PatternInfo> patterns,
                                 boolean revealGreat) throws IOException {
        Path langDir = outDir.resolve(lang);
        Files.createDirectories(langDir.resolve("c"));
        Files.createDirectories(langDir.resolve("e"));
        writeRootAssets(outDir);
        writePatternsJson(outDir, patterns);

        // index
        StringBuilder side = new StringBuilder();
        Map<String, List<BookModels.Entry>> byCat = new LinkedHashMap<>();
        for (BookModels.Category c : book.categories()) byCat.put(c.id(), new ArrayList<>());
        List<BookModels.Entry> uncategorized = new ArrayList<>();
        for (BookModels.Entry e : book.entries()) {
            if (byCat.containsKey(e.category())) byCat.get(e.category()).add(e);
            else uncategorized.add(e);
        }

        side.append("<nav class=\"sidebar\"><h1>").append(PatchouliText.escapeHtml(lang))
            .append(" · thehexbook</h1><ul class=\"cats\">");
        for (BookModels.Category c : book.categories()) {
            String cname = lr.resolveMaybeKey(c.nameKey());
            side.append("<li><a href=\"c/").append(PatchouliText.sanitize(c.id())).append(".html\">")
                .append(PatchouliText.escapeHtml(cname)).append("</a><ul>");
            for (BookModels.Entry e : byCat.getOrDefault(c.id(), List.of())) {
                side.append("<li><a href=\"e/").append(PatchouliText.sanitize(e.id())).append(".html\">")
                    .append(PatchouliText.escapeHtml(lr.resolveMaybeKey(e.nameKey()))).append("</a></li>");
            }
            side.append("</ul></li>");
        }
        if (!uncategorized.isEmpty()) {
            side.append("<li><span>Other</span><ul>");
            for (BookModels.Entry e : uncategorized) {
                side.append("<li><a href=\"e/").append(PatchouliText.sanitize(e.id())).append(".html\">")
                    .append(PatchouliText.escapeHtml(lr.resolveMaybeKey(e.nameKey()))).append("</a></li>");
            }
            side.append("</ul></li>");
        }
        side.append("</ul></nav>");

        String indexBody = "<p>Hex Casting book export via MieLib <code>/genHexBook</code>. "
            + "Pattern geometry from the <strong>live action registry</strong>.</p>"
            + "<p>Categories: " + book.categories().size() + " · Entries: " + book.entries().size()
            + " · Patterns in registry: " + patterns.size() + "</p>"
            + "<p><a href=\"../patterns.json\">patterns.json</a></p>";
        writeHtml(langDir.resolve("index.html"), "thehexbook (" + lang + ")", side.toString(), indexBody, "../");

        for (BookModels.Category c : book.categories()) {
            StringBuilder body = new StringBuilder();
            body.append("<h1>").append(PatchouliText.escapeHtml(lr.resolveMaybeKey(c.nameKey()))).append("</h1>");
            body.append("<div class=\"desc\">").append(PatchouliText.toHtml(c.descKey(), lr)).append("</div><ul>");
            for (BookModels.Entry e : byCat.getOrDefault(c.id(), List.of())) {
                body.append("<li><a href=\"../e/").append(PatchouliText.sanitize(e.id())).append(".html\">")
                    .append(PatchouliText.escapeHtml(lr.resolveMaybeKey(e.nameKey()))).append("</a></li>");
            }
            body.append("</ul>");
            // sidebar with adjusted relative links
            String sideC = side.toString()
                .replace("href=\"c/", "href=\"")
                .replace("href=\"e/", "href=\"../e/");
            writeHtml(langDir.resolve("c").resolve(PatchouliText.sanitize(c.id()) + ".html"),
                lr.resolveMaybeKey(c.nameKey()), sideC, body.toString(), "../../");
        }

        for (BookModels.Entry e : book.entries()) {
            StringBuilder body = new StringBuilder();
            body.append("<h1>").append(PatchouliText.escapeHtml(lr.resolveMaybeKey(e.nameKey()))).append("</h1>");
            body.append("<p class=\"meta\"><code>").append(PatchouliText.escapeHtml(e.id())).append("</code>");
            if (e.advancement() != null && !e.advancement().isEmpty()) {
                body.append(" · advancement <code>").append(PatchouliText.escapeHtml(e.advancement())).append("</code>");
            }
            body.append("</p>");
            int pi = 0;
            for (BookModels.Page page : e.pages()) {
                body.append("<section class=\"page\" id=\"p").append(pi++).append("\">");
                body.append(renderPage(page, lr, patterns, revealGreat));
                body.append("</section>");
            }
            String sideE = side.toString()
                .replace("href=\"c/", "href=\"../c/")
                .replace("href=\"e/", "href=\"");
            writeHtml(langDir.resolve("e").resolve(PatchouliText.sanitize(e.id()) + ".html"),
                lr.resolveMaybeKey(e.nameKey()), sideE, body.toString(), "../../");
        }

        // root index redirect-ish
        if (!Files.exists(outDir.resolve("index.html"))) {
            Files.writeString(outDir.resolve("index.html"),
                "<!doctype html><meta charset=utf-8><title>hexbook</title>"
                    + "<p><a href=\"" + lang + "/index.html\">Open " + lang + "</a></p>"
                    + "<p><a href=\"patterns.json\">patterns.json</a></p>",
                StandardCharsets.UTF_8);
        } else {
            // append lang link
            String cur = Files.readString(outDir.resolve("index.html"));
            if (!cur.contains(lang + "/index.html")) {
                Files.writeString(outDir.resolve("index.html"),
                    cur.replace("</body>", "<p><a href=\"" + lang + "/index.html\">" + lang + "</a></p></body>")
                        .replace("</html>", "<p><a href=\"" + lang + "/index.html\">" + lang + "</a></p></html>"),
                    StandardCharsets.UTF_8);
            }
        }
    }

    private static String renderPage(BookModels.Page page, LangResolver lr,
                                     Map<String, PatternInfo> patterns, boolean revealGreat) {
        if (page instanceof BookModels.TextPage t) {
            return "<div class=\"text\"><p>" + PatchouliText.toHtml(t.text(), lr) + "</p></div>";
        }
        if (page instanceof BookModels.PatternPage p) {
            PatternInfo info = patterns.get(p.opId());
            if (info == null) info = PatternLookup.lookup(p.opId());
            StringBuilder b = new StringBuilder();
            b.append("<div class=\"pattern-page\">");
            String title = info != null ? lr.get(info.translationKey()) : p.opId();
            if (title.equals(info != null ? info.translationKey() : null)) title = p.opId();
            b.append("<h2>").append(PatchouliText.escapeHtml(title == null ? p.opId() : title)).append("</h2>");
            b.append("<p class=\"op\"><code>").append(PatchouliText.escapeHtml(p.opId())).append("</code>");
            if (p.input() != null && !p.input().isEmpty())
                b.append(" · in <code>").append(PatchouliText.escapeHtml(p.input())).append("</code>");
            if (p.output() != null && !p.output().isEmpty())
                b.append(" · out <code>").append(PatchouliText.escapeHtml(p.output())).append("</code>");
            b.append("</p>");
            if (info != null) {
                boolean hideSig = info.perWorld() && !revealGreat;
                String sig = hideSig ? "" : info.signature();
                String start = info.startDir();
                if (info.perWorld()) {
                    b.append("<p class=\"badge per-world\">per-world / great spell");
                    if (hideSig) b.append(" — showing canonical prototype; in-game shapes differ per world");
                    b.append("</p>");
                    // always show canonical prototype from registry (not scrungled), even when "hidden"
                    sig = info.signature();
                }
                b.append(PatternSvg.svg(start, sig, info.perWorld(), 180));
                b.append("<p class=\"sig\"><code>").append(PatchouliText.escapeHtml(start))
                    .append(" ").append(PatchouliText.escapeHtml(sig)).append("</code></p>");
                b.append("<canvas class=\"pattern\" width=\"180\" height=\"180\" data-start=\"")
                    .append(PatchouliText.escapeAttr(start.toLowerCase().replace('_', '_')))
                    .append("\" data-string=\"").append(PatchouliText.escapeAttr(sig))
                    .append("\" data-per-world=\"").append(info.perWorld() ? "True" : "False")
                    .append("\"></canvas>");
            } else {
                b.append("<p class=\"warn\">pattern not in registry: ")
                    .append(PatchouliText.escapeHtml(p.opId())).append("</p>");
            }
            if (p.text() != null && !p.text().isEmpty()) {
                b.append("<div class=\"text\"><p>").append(PatchouliText.toHtml(p.text(), lr)).append("</p></div>");
            }
            b.append("</div>");
            return b.toString();
        }
        if (page instanceof BookModels.SpotlightPage s) {
            return "<div class=\"spotlight\"><h3>Spotlight <code>"
                + PatchouliText.escapeHtml(s.item()) + "</code></h3><p>"
                + PatchouliText.toHtml(s.text(), lr) + "</p></div>";
        }
        if (page instanceof BookModels.CraftingPage c) {
            return "<div class=\"crafting\"><h3>Crafting <code>"
                + PatchouliText.escapeHtml(c.recipe()) + "</code></h3><p>"
                + PatchouliText.toHtml(c.text(), lr) + "</p></div>";
        }
        if (page instanceof BookModels.ImagePage img) {
            return "<div class=\"image\"><p>images: "
                + PatchouliText.escapeHtml(String.join(", ", img.images())) + "</p><p>"
                + PatchouliText.toHtml(img.text(), lr) + "</p></div>";
        }
        if (page instanceof BookModels.OtherPage o) {
            return "<div class=\"other\"><p>page type <code>"
                + PatchouliText.escapeHtml(o.type()) + "</code></p><pre>"
                + PatchouliText.escapeHtml(o.rawJson()) + "</pre></div>";
        }
        return "";
    }

    private static void writeHtml(Path file, String title, String sidebar, String body, String assetPrefix) throws IOException {
        String html = "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"/>"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
            + "<title>" + PatchouliText.escapeHtml(title) + "</title>"
            + "<link rel=\"stylesheet\" href=\"" + assetPrefix + "style.css\"/>"
            + "</head><body><div class=\"layout\">"
            + sidebar
            + "<main class=\"content\">" + body + "</main></div>"
            + "<script type=\"module\" src=\"" + assetPrefix + "pattern.js\"></script>"
            + "</body></html>\n";
        Files.writeString(file, html, StandardCharsets.UTF_8);
    }

    private static void writeRootAssets(Path outDir) throws IOException {
        Files.createDirectories(outDir);
        if (!Files.exists(outDir.resolve("style.css"))) {
            Files.writeString(outDir.resolve("style.css"), STYLE, StandardCharsets.UTF_8);
        }
        if (!Files.exists(outDir.resolve("pattern.js"))) {
            Files.writeString(outDir.resolve("pattern.js"), PATTERN_JS, StandardCharsets.UTF_8);
        }
        if (!Files.exists(outDir.resolve("index.html"))) {
            Files.writeString(outDir.resolve("index.html"),
                "<!doctype html><html><head><meta charset=utf-8><title>hexbook</title>"
                    + "<link rel=stylesheet href=style.css></head><body><h1>hexbook</h1>"
                    + "<p>MieLib /genHexBook export</p></body></html>\n",
                StandardCharsets.UTF_8);
        }
    }

    private static void writePatternsJson(Path outDir, Map<String, PatternInfo> patterns) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PatternInfo p : patterns.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("startdir", p.startDir());
            m.put("signature", p.signature());
            m.put("perWorld", p.perWorld());
            m.put("translationKey", p.translationKey());
            list.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("patterns", list);
        Files.writeString(outDir.resolve("patterns.json"), GSON.toJson(root), StandardCharsets.UTF_8);
    }

    private static final String STYLE = """
        :root { color-scheme: dark; --bg:#0d1117; --panel:#161b22; --text:#e6edf3; --muted:#8b949e; --acc:#58a6ff; --warn:#f0883e; }
        * { box-sizing: border-box; }
        body { margin:0; font: 15px/1.55 system-ui,sans-serif; background:var(--bg); color:var(--text); }
        .layout { display:flex; min-height:100vh; }
        .sidebar { width:280px; flex:0 0 280px; background:var(--panel); border-right:1px solid #30363d; padding:1rem; overflow:auto; }
        .sidebar h1 { font-size:1rem; margin:0 0 1rem; color:var(--muted); }
        .sidebar ul { list-style:none; padding-left:0.75rem; margin:0.25rem 0; }
        .sidebar a { color:var(--acc); text-decoration:none; }
        .sidebar a:hover { text-decoration:underline; }
        .content { flex:1; padding:1.5rem 2rem; max-width:920px; }
        code,kbd { background:#21262d; padding:0.1em 0.35em; border-radius:4px; font-size:0.92em; }
        .pattern-page { background:var(--panel); padding:1rem; border-radius:10px; margin:1rem 0; }
        .badge.per-world { color:var(--warn); font-weight:600; }
        .pattern-svg { margin:0.5rem 0; }
        canvas.pattern { display:none; } /* SVG is primary; canvas optional enhancement */
        .page { margin:1.25rem 0; padding-bottom:1rem; border-bottom:1px solid #21262d; }
        .meta { color:var(--muted); }
        .warn { color:var(--warn); }
        pre { white-space:pre-wrap; background:#21262d; padding:0.75rem; border-radius:8px; overflow:auto; }
        """;

    /** Minimal canvas drawer (hexdoc-compatible data attrs). SVG is shown by default. */
    private static final String PATTERN_JS = """
        "use strict";
        function startAngle(str) {
          switch ((str||"").toLowerCase()) {
            case "east": return 0; case "north_east": return 1; case "northeast": return 1;
            case "north_west": return 2; case "northwest": return 2; case "west": return 3;
            case "south_west": return 4; case "southwest": return 4; case "south_east": return 5; case "southeast": return 5;
            default: return 0;
          }
        }
        function offsetAngle(ch) {
          switch (ch) { case "w": return 0; case "q": return 1; case "a": return 2; case "s": return 3; case "d": return 4; case "e": return 5; default: return -1; }
        }
        function draw(canvas) {
          const str = canvas.dataset.string || "";
          let angle = startAngle(canvas.dataset.start);
          const perWorld = canvas.dataset.perWorld === "True";
          const points = [[0,0]]; let last = points[0];
          let minP = last, maxP = last;
          for (const ch of ("w" + str)) {
            const add = offsetAngle(ch); if (add < 0) continue;
            angle = (angle + add) % 6;
            const t = (Math.PI/3)*angle;
            const np = [last[0]+Math.cos(t), last[1]-Math.sin(t)];
            points.push(np); last = np;
            minP = [Math.min(minP[0],np[0]), Math.min(minP[1],np[1])];
            maxP = [Math.max(maxP[0],np[0]), Math.max(maxP[1],np[1])];
          }
          const size = Math.min(canvas.width, canvas.height)*0.8;
          const scale = size / Math.max(3, Math.max(maxP[1]-minP[1], maxP[0]-minP[0]));
          const center = [(minP[0]+maxP[0])*0.5, (minP[1]+maxP[1])*0.5];
          const pts = points.map(p => [canvas.width*0.5+scale*(p[0]-center[0]), canvas.height*0.5+scale*(p[1]-center[1])]);
          const ctx = canvas.getContext("2d");
          ctx.fillStyle = "#0d1117"; ctx.fillRect(0,0,canvas.width,canvas.height);
          ctx.strokeStyle = perWorld ? "#f0883e" : "#58a6ff"; ctx.lineWidth = 3; ctx.lineJoin = "round"; ctx.lineCap = "round";
          ctx.beginPath();
          pts.forEach((p,i) => i?ctx.lineTo(p[0],p[1]):ctx.moveTo(p[0],p[1]));
          ctx.stroke();
          pts.forEach((p,i) => { ctx.fillStyle = (i===0 && !perWorld) ? "#e8c36a" : "#c9d1d9"; ctx.beginPath(); ctx.arc(p[0],p[1], (i===0&&!perWorld)?4.5:3, 0, Math.PI*2); ctx.fill(); });
          canvas.style.display = "inline-block";
        }
        document.querySelectorAll("canvas.pattern").forEach(draw);
        """;
}
