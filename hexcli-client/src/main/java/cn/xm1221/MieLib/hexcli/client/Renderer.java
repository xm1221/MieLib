package cn.xm1221.MieLib.hexcli.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * 把服务器回复 / 事件渲染成终端文本（ANSI，JLine {@link AttributedStringBuilder}）。
 * <p>
 * {@code --no-color} 或非 tty 时退化成纯文本（不带任何样式）。
 */
public final class Renderer {
    private final boolean color;

    public Renderer(boolean color) {
        this.color = color;
    }

    public boolean color() {
        return color;
    }

    /** 终端声明支持 24 位色（COLORTERM=truecolor/24bit，或 Windows Terminal 的 WT_SESSION）时按真彩色输出，否则 256 色。 */
    public static int colorCount(org.jline.terminal.Terminal terminal) {
        String ct = System.getenv("COLORTERM");
        if (ct != null && (ct.equalsIgnoreCase("truecolor") || ct.equalsIgnoreCase("24bit"))) return 0x1000000;
        if (System.getenv("WT_SESSION") != null) return 0x1000000;
        Integer n = terminal == null ? null : terminal.getNumericCapability(org.jline.utils.InfoCmp.Capability.max_colors);
        return n == null || n < 256 ? 256 : n;
    }

    /** 不带 ACS 线框替换的 ANSI 文本。 */
    public String ansi(AttributedString s, org.jline.terminal.Terminal terminal) {
        if (!color) return s.toString();
        return s.toAnsi(colorCount(terminal), org.jline.utils.AttributedCharSequence.ForceMode.None, null, null, null);
    }

    // ---- 样式小工具 ----

    private AttributedStyle s() {
        return AttributedStyle.DEFAULT;
    }

    private AttributedStyle fgStyle(String hex) {
        if (!color) return s();
        int[] rgb = rgb(hex);
        if (rgb == null) return s();
        // Minecraft 的深色（DARK_BLUE / DARK_PURPLE ...）在深色终端上看不清：太暗就往白色提亮
        double lum = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
        if (lum < 80) for (int k = 0; k < 3; k++) rgb[k] = rgb[k] + (255 - rgb[k]) * 45 / 100;
        return s().foreground(rgb[0], rgb[1], rgb[2]);
    }

    private AttributedStyle dimStyle() {
        return color ? s().faint() : s();
    }

    private AttributedStyle redStyle() {
        return color ? s().foreground(AttributedStyle.RED) : s();
    }

    private AttributedStyle greenStyle() {
        return color ? s().foreground(AttributedStyle.GREEN) : s();
    }

    /** {@code #rrggbb} → {r,g,b}；不是合法颜色返回 null。 */
    public static int[] rgb(String hex) {
        if (hex == null || !hex.startsWith("#") || hex.length() != 7) return null;
        try {
            int v = Integer.parseInt(hex.substring(1), 16);
            return new int[]{(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- 一条回复 ----

    /** 渲染一个 reply 消息；返回要打印的行。 */
    public List<AttributedString> reply(JsonObject msg) {
        JsonObject data = Connection.obj(msg, "data");
        boolean ok = Connection.bool(msg, "ok", true);
        List<AttributedString> out = new ArrayList<>();
        JsonArray lines = Connection.array(msg, "lines");
        String error = data == null ? null : Connection.str(data, "error");

        if (data != null && data.has("cast")) {
            out.addAll(renderRun(data));
            if (error != null && !error.isBlank()) out.add(errorLine(error));
            return out;
        }
        if (data != null && data.has("stack") && !data.has("profiles") && !data.has("envs")) {
            out.addAll(renderStackReply(data));
            return out;
        }
        // 其它（profile / env / media / command / ...）：服务器给的文本行原样打印
        for (JsonElement l : lines) out.add(plain(l.getAsString()));
        if (error != null && !error.isBlank() && lines.isEmpty()) out.add(errorLine(error));
        if (out.isEmpty() && !ok) out.add(errorLine(error == null ? "request failed" : error));
        return out;
    }

    private List<AttributedString> renderRun(JsonObject data) {
        List<AttributedString> out = new ArrayList<>();
        JsonObject cast = Connection.obj(data, "cast");
        String error = Connection.str(data, "error");
        for (JsonElement m : Connection.array(data, "parserMessages")) {
            out.add(plain("hexParse: " + m.getAsString()));
        }
        if (cast != null) out.addAll(renderCast(cast));
        else if (error == null) out.add(styled("(no cast)", dimStyle()));
        for (JsonElement p : Connection.array(data, "prints")) out.add(plain("print: " + p.getAsString()));
        for (JsonElement m : Connection.array(data, "messages")) out.add(plain("message: " + m.getAsString()));
        String impetus = Connection.str(data, "impetus");
        if (impetus != null) out.add(styled("impetus: " + impetus, dimStyle()));
        JsonObject media = Connection.obj(data, "media");
        if (media != null) {
            String text = Connection.str(media, "text");
            if (text != null) out.add(styled("media: " + text, dimStyle()));
        }
        if (error != null && !error.isBlank()) out.add(errorLine(error));
        return out;
    }

    private List<AttributedString> renderCast(JsonObject cast) {
        List<AttributedString> out = new ArrayList<>();
        String res = Connection.str(cast, "resolution");
        int steps = Connection.integer(cast, "steps", 0);
        long ops = cast.has("ops") && !cast.get("ops").isJsonNull() ? cast.get("ops").getAsLong() : 0;

        AttributedStringBuilder head = new AttributedStringBuilder();
        head.append("cast #" + Connection.integer(cast, "id", 0) + "  ");
        if (res == null) {
            head.append("(no patterns)");
        } else {
            head.append(res, "EVALUATED".equals(res) ? greenStyle() : redStyle());
        }
        head.append(String.format("   steps %d  ops %d", steps, ops), dimStyle());
        out.add(head.toAttributedString());

        out.addAll(renderStack(Connection.array(cast, "stack")));

        JsonObject rm = Connection.obj(cast, "ravenmind");
        if (rm != null) {
            AttributedStringBuilder b = new AttributedStringBuilder().append("ravenmind: ");
            appendIota(b, rm);
            out.add(b.toAttributedString());
        } else {
            out.add(styled("ravenmind: (none)", dimStyle()));
        }

        for (JsonElement m : Connection.array(cast, "mishaps")) out.add(errorLine(m.getAsString()));

        if (cast.has("mediaUsed") && !cast.get("mediaUsed").isJsonNull()) {
            double dust = cast.has("mediaDust") ? cast.get("mediaDust").getAsDouble() : 0;
            out.add(styled(String.format("media used: %d (%.2f dust)", cast.get("mediaUsed").getAsLong(), dust), dimStyle()));
        }
        return out;
    }

    private List<AttributedString> renderStack(JsonArray stack) {
        List<AttributedString> out = new ArrayList<>();
        if (stack.size() == 0) {
            out.add(styled("(empty stack)", dimStyle()));
            return out;
        }
        for (int i = 0; i < stack.size(); i++) {
            JsonObject iota = stack.get(i).getAsJsonObject();
            boolean top = i == stack.size() - 1;
            AttributedStringBuilder b = new AttributedStringBuilder();
            b.append(String.format("  [%d] ", i), dimStyle());
            b.append(top ? "▶ " : "  ");
            appendIota(b, iota);
            out.add(b.toAttributedString());
        }
        return out;
    }

    /** 列表最多显示这么多个元素，再多就 {@code … (+N)}。 */
    private static final int MAX_LIST = 500;
    /** 括号按深度轮换的颜色（都够亮，深色背景上也看得清）。 */
    private static final String[] BRACKETS = {"#cba6f7", "#89b4fa", "#f9e2af", "#fab387"};

    /** 列表 iota 横排成一行：{@code [a, [b, c], d]}，元素按各自类型上色，括号按深度上色；不手动换行。 */
    private void appendList(AttributedStringBuilder b, JsonArray children, int depth) {
        AttributedStyle br = fgStyle(BRACKETS[depth % BRACKETS.length]);
        b.append("[", br);
        int n = Math.min(children.size(), MAX_LIST);
        for (int i = 0; i < n; i++) {
            if (i > 0) b.append(", ", dimStyle());
            appendIota(b, children.get(i).getAsJsonObject(), depth + 1);
        }
        if (children.size() > n) b.append(", … (+" + (children.size() - n) + ")", dimStyle());
        b.append("]", br);
    }

    /** 按 rich 分段 / iota 颜色上色。 */
    private void appendIota(AttributedStringBuilder b, JsonObject iota) {
        appendIota(b, iota, 0);
    }

    private void appendIota(AttributedStringBuilder b, JsonObject iota, int depth) {
        if (iota.has("children") && iota.get("children").isJsonArray()) {
            appendList(b, iota.getAsJsonArray("children"), depth);
            return;
        }
        String text = Connection.str(iota, "text");
        if (text == null) text = "";
        if (color && iota.has("rich") && iota.get("rich").isJsonArray()) {
            JsonArray rich = iota.getAsJsonArray("rich");
            if (rich.size() > 0) {
                for (JsonElement e : rich) {
                    JsonObject seg = e.getAsJsonObject();
                    String s = Connection.str(seg, "text");
                    if (s == null) continue;
                    AttributedStyle st = fgStyle(Connection.str(seg, "color"));
                    if (Connection.bool(seg, "bold", false)) st = st.bold();
                    if (Connection.bool(seg, "italic", false)) st = st.italic();
                    if (Connection.bool(seg, "underlined", false)) st = st.underline();
                    b.append(s, st);
                }
                return;
            }
        }
        b.append(text, fgStyle(Connection.str(iota, "color")));
    }

    private List<AttributedString> renderStackReply(JsonObject data) {
        List<AttributedString> out = new ArrayList<>();
        out.addAll(renderStack(Connection.array(data, "stack")));
        String rm = Connection.str(data, "ravenmind");
        out.add(rm == null ? styled("ravenmind: (none)", dimStyle()) : plain("ravenmind: " + rm));
        int parens = Connection.integer(data, "parens", 0);
        if (parens > 0) out.add(styled("open introspection depth: " + parens, dimStyle()));
        return out;
    }

    /** 单步头：{@code [i/N] <iota>}。 */
    public AttributedString stepHeader(int i, int total, JsonObject preview) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        b.append("[" + i + "/" + total + "] ", color ? AttributedStyle.BOLD.foreground(AttributedStyle.CYAN) : AttributedStyle.DEFAULT);
        if (preview != null) appendIota(b, preview);
        return b.toAttributedString();
    }

    // ---- 事件 ----

    public List<AttributedString> event(JsonObject msg) {
        List<AttributedString> out = new ArrayList<>();
        String kind = Connection.str(msg, "event");
        if ("cast".equals(kind)) {
            JsonObject cast = Connection.obj(msg, "cast");
            if (cast != null) {
                out.add(styled("— spawned cast —", dimStyle()));
                out.addAll(renderCast(cast));
            }
        } else if ("print".equals(kind)) {
            String text = Connection.str(msg, "text");
            if (text != null) out.add(plain("print: " + text));
        }
        return out;
    }

    // ---- 小工具 ----

    private AttributedString plain(String s) {
        return new AttributedStringBuilder().append(s).toAttributedString();
    }

    private AttributedString styled(String s, AttributedStyle style) {
        return new AttributedStringBuilder().append(s, style).toAttributedString();
    }

    private AttributedString errorLine(String text) {
        return styled("! " + text, redStyle());
    }

    /** 渲染结果转纯文本（--exec / 非 tty 用）。 */
    public static String plainText(List<AttributedString> lines) {
        StringBuilder sb = new StringBuilder();
        for (AttributedString l : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l.toString());
        }
        return sb.toString();
    }
}
