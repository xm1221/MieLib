package cn.xm1221.MieLib.genhexbook;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal Patchouli {@code $(...)} macro → HTML / Markdown. */
public final class PatchouliText {
    private static final Pattern MACRO = Pattern.compile("\\$\\(([^)]*)\\)");

    private PatchouliText() {}

    public static String toHtml(String input, LangResolver lang) {
        if (input == null) return "";
        String text = lang.resolveMaybeKey(input);
        StringBuilder out = new StringBuilder();
        Matcher m = MACRO.matcher(text);
        int last = 0;
        boolean openBold = false, openItalic = false, openLink = false, openColor = false;
        while (m.find()) {
            out.append(escapeHtml(text.substring(last, m.start())));
            String code = m.group(1);
            if (code.equals("br")) {
                out.append("<br/>");
            } else if (code.equals("p")) {
                out.append("</p><p>");
            } else if (code.equals("l") || code.equals("bold")) {
                out.append("<strong>"); openBold = true;
            } else if (code.equals("/l") || code.equals("/bold")) {
                out.append("</strong>"); openBold = false;
            } else if (code.equals("o") || code.equals("italic")) {
                out.append("<em>"); openItalic = true;
            } else if (code.equals("/o") || code.equals("/italic")) {
                out.append("</em>"); openItalic = false;
            } else if (code.equals("clear") || code.isEmpty()) {
                if (openLink) { out.append("</a>"); openLink = false; }
                if (openColor) { out.append("</span>"); openColor = false; }
                if (openBold) { out.append("</strong>"); openBold = false; }
                if (openItalic) { out.append("</em>"); openItalic = false; }
            } else if (code.startsWith("l:")) {
                String href = code.substring(2);
                out.append("<a href=\"").append(escapeAttr(mapLink(href))).append("\">");
                openLink = true;
            } else if (code.equals("/l") || code.equals("l")) {
                // handled above for bold; /l already
            } else if (code.startsWith("#") && code.length() >= 4) {
                out.append("<span style=\"color:").append(escapeAttr(code)).append("\">");
                openColor = true;
            } else if (code.startsWith("item:") || code.startsWith("thing:")) {
                String id = code.substring(code.indexOf(':') + 1);
                out.append("<code class=\"item\">").append(escapeHtml(id)).append("</code>");
            } else if (code.startsWith("k:")) {
                out.append("<kbd>").append(escapeHtml(code.substring(2))).append("</kbd>");
            } else if (code.startsWith("t:")) {
                // tooltip — ignore opener; content follows until $(/t)
                out.append("<abbr title=\"").append(escapeAttr(code.substring(2))).append("\">");
            } else if (code.equals("/t")) {
                out.append("</abbr>");
            } else {
                // unknown — drop
            }
            last = m.end();
        }
        out.append(escapeHtml(text.substring(last)));
        if (openLink) out.append("</a>");
        if (openColor) out.append("</span>");
        if (openBold) out.append("</strong>");
        if (openItalic) out.append("</em>");
        return out.toString();
    }

    public static String toMarkdown(String input, LangResolver lang) {
        if (input == null) return "";
        String text = lang.resolveMaybeKey(input);
        StringBuilder out = new StringBuilder();
        Matcher m = MACRO.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text.substring(last, m.start()));
            String code = m.group(1);
            if (code.equals("br") || code.equals("p")) out.append("\n\n");
            else if (code.equals("l") || code.equals("bold")) out.append("**");
            else if (code.equals("/l") || code.equals("/bold")) out.append("**");
            else if (code.equals("o") || code.equals("italic")) out.append("*");
            else if (code.equals("/o") || code.equals("/italic")) out.append("*");
            else if (code.startsWith("l:")) out.append("[");
            else if (code.equals("/l")) out.append("](").append(mapLink("")).append(")");
            else if (code.startsWith("item:") || code.startsWith("thing:"))
                out.append('`').append(code.substring(code.indexOf(':') + 1)).append('`');
            else if (code.startsWith("k:")) out.append('`').append(code.substring(2)).append('`');
            // colors / clear / unknown dropped
            last = m.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    private static String mapLink(String href) {
        // patchouli entry links like hexcasting:basics → relative entry page
        if (href == null) return "#";
        if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("#")) return href;
        String id = href.contains(":") ? href.replace(':', '_') : href;
        return "e/" + sanitize(id) + ".html";
    }

    static String sanitize(String id) {
        return id.replace(':', '_').replace('/', '_').replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String escapeAttr(String s) {
        return escapeHtml(s).replace("\"", "&quot;");
    }
}
