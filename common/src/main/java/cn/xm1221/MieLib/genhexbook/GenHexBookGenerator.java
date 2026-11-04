package cn.xm1221.MieLib.genhexbook;

import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the three stages:
 * <ol>
 *   <li>dump a hexdoc project from the live server ({@link HexdocProjectWriter});</li>
 *   <li>build it with the real {@code hexdoc} CLI ({@link HexdocRunner});</li>
 *   <li>optionally export Markdown alongside the site ({@link MdExporter}).</li>
 * </ol>
 *
 * Pattern data always comes from the live Action registry (canonical prototypes,
 * never the world's scrambled signatures).
 */
public final class GenHexBookGenerator {
    private GenHexBookGenerator() {}

    public record Result(int categories, int entries, int patterns, Path outDir, Path siteDir) {}

    public static Result generate(MinecraftServer server, Path outDir, String lang, boolean md,
                                  Integer servePort) throws Exception {
        Files.createDirectories(outDir);
        Map<String, PatternInfo> patterns = PatternLookup.all();

        // Public URL used for og:url / the site base path. Explicit property wins, then the
        // local preview server, then the deployed Cloudflare Pages site.
        String siteUrl = System.getProperty("mielib.genhexbook.siteUrl", "").strip();
        while (siteUrl.endsWith("/")) {
            siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
        }
        if (siteUrl.isEmpty()) {
            siteUrl = servePort == null
                ? "https://mielib-hexbook.pages.dev"
                : "http://127.0.0.1:" + servePort;
        }

        Path project = HexdocProjectWriter.write(server, outDir, siteUrl);
        GenHexBook.LOGGER.info("[GenHexBook] hexdoc project → {}", project);

        Path site;
        try {
            site = HexdocRunner.build(project, outDir);
        } catch (Exception e) {
            // A build error is not an install problem: only hint when hexdoc was not found.
            if (HexdocRunner.isMissing(e)) {
                for (String line : HexdocRunner.installHint()) {
                    GenHexBook.LOGGER.error(line);
                }
            }
            throw e;
        }

        int cats = 0, ents = 0;
        if (md) {
            for (String l : langs(lang)) {
                LangResolver lr = LangResolver.load(server, l);
                BookScanner.BookData book = BookScanner.scan(server, l);
                cats = Math.max(cats, book.categories().size());
                ents = Math.max(ents, book.entries().size());
                MdExporter.write(outDir, l, book, lr, patterns);
                GenHexBook.LOGGER.info("[GenHexBook] wrote markdown lang={} cats={} entries={}", l,
                    book.categories().size(), book.entries().size());
            }
        }

        return new Result(cats, ents, patterns.size(), outDir, site);
    }

    private static List<String> langs(String lang) {
        List<String> out = new ArrayList<>();
        if ("both".equals(lang)) {
            out.add("en_us");
            out.add("zh_cn");
        } else {
            out.add(lang);
        }
        return out;
    }
}
