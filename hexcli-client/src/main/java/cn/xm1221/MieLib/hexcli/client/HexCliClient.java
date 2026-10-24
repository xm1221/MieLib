package cn.xm1221.MieLib.hexcli.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jline.reader.EndOfFileException;
import org.jline.reader.EOFError;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.InfoCmp;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HexCLI 终端客户端：连到服务器上的 HexCLI 远程端点（回环地址 + token），
 * 交互式 REPL 或 {@code --exec} 单次执行。
 */
public final class HexCliClient {
    private static final String PROMPT = "hex> ";
    private static final String CONTINUATION = "...> ";

    /** 刚在 run 回复里显示过的 print 文本（用于丢弃随之而来的重复 print 事件）。 */
    private final java.util.Set<String> recentPrints = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile Thread dedupeTimer;
    private final ClientConfig.Args args;
    private final Renderer renderer;
    private final Completer completer = new Completer();
    private volatile Connection conn;
    private volatile boolean quitting = false;
    private Terminal terminal;
    private LineReader reader;

    private HexCliClient(ClientConfig.Args args, boolean color) {
        this.args = args;
        this.renderer = new Renderer(color);
    }

    public static void main(String[] argv) {
        ClientConfig.Args args = ClientConfig.parse(argv);
        if (args.help) {
            System.out.println(ClientConfig.USAGE);
            return;
        }
        for (String e : args.errors) System.err.println("hexcli: " + e);
        if (!args.errors.isEmpty()) {
            System.err.println();
            System.err.println(ClientConfig.USAGE);
            System.exit(2);
        }
        boolean tty = System.console() != null;
        boolean color = !args.noColor && tty;
        HexCliClient client = new HexCliClient(args, color);
        try {
            if (args.exec != null) {
                System.exit(client.execOnce(args.exec));
            }
            client.repl();
            System.exit(0);
        } catch (IOException e) {
            System.err.println("hexcli: " + e.getMessage());
            System.exit(1);
        }
    }

    // ---- 连接 ----

    /** 连一次（失败抛 IOException）。 */
    private Connection connect() throws IOException {
        Connection.Endpoint ep = ClientConfig.endpoint(args);
        Connection c = Connection.open(ep, 5000);
        c.onClose(this::onDisconnect);
        c.onEvent(this::onEvent);
        conn = c;
        return c;
    }

    private void onEvent(JsonObject msg) {
        // print 事件也包含自己 run 里打印的那些（服务器对每次 print 都推送）：短时间内去重。
        if ("print".equals(Connection.str(msg, "event"))) {
            String text = Connection.str(msg, "text");
            if (text != null && recentPrints.remove(text)) return;
        }
        List<AttributedString> lines = renderer.event(msg);
        printLines(lines);
    }

    private void printLines(List<AttributedString> lines) {
        if (lines.isEmpty()) return;
        if (reader != null && terminal != null) {
            for (AttributedString l : lines) reader.printAbove(l);
        } else {
            PrintWriter out = terminal == null ? new PrintWriter(System.out, true) : terminal.writer();
            for (AttributedString l : lines) out.println(l.toAnsi(terminal));
            out.flush();
        }
    }

    private void onDisconnect() {
        if (quitting) return;
        printNotice("connection lost; reconnecting every 2s...");
        Thread t = new Thread(() -> {
            while (!quitting) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                if (quitting) return;
                try {
                    Connection c = connect();
                    printNotice("reconnected to " + c.profile() + " (env " + c.env() + ")");
                    refreshCompletion(c);
                    return;
                } catch (IOException e) {
                    // 继续重试；端口 / token 每次服务器启动都会变，所以每轮重读 client.json
                }
            }
        }, "hexcli-reconnect");
        t.setDaemon(true);
        t.start();
    }

    private void printNotice(String text) {
        printLines(List.of(new AttributedString("[hexcli] " + text)));
    }

    private void refreshCompletion(Connection c) {
        try {
            JsonObject reply = c.request("complete").get(5, TimeUnit.SECONDS);
            completer.update(Connection.obj(reply, "data"));
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            // 补全拿不到不影响使用
        }
    }

    // ---- --exec ----

    private int execOnce(String code) throws IOException {
        if (code.strip().startsWith(":")) {
            System.err.println("hexcli: --exec takes hex code; meta-commands work in the REPL (e.g. pipe them via stdin)");
            return 2;
        }
        quitting = true; // 单次执行：断开时不要触发自动重连
        try (Connection c = connect()) {
            JsonObject reply = c.request("run", "code", code).join();
            List<AttributedString> lines = renderer.reply(reply);
            PrintWriter out = new PrintWriter(System.out, true);
            for (AttributedString l : lines) {
                out.println(renderer.color() ? l.toAnsi(256, true) : l.toString());
            }
            out.flush();
            if (!Connection.bool(reply, "ok", false)) return 1;
            JsonObject data = Connection.obj(reply, "data");
            JsonObject cast = data == null ? null : Connection.obj(data, "cast");
            JsonArray mishaps = cast == null ? null : Connection.array(cast, "mishaps");
            return mishaps != null && mishaps.size() > 0 ? 1 : 0;
        }
    }

    // ---- REPL ----

    private void repl() throws IOException {
        terminal = TerminalBuilder.builder().system(true).build();
        reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(completer)
            .parser(new HexParser())
            .variable(LineReader.HISTORY_FILE, Path.of(System.getProperty("user.home"), ".hexcli_history"))
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
            .variable(LineReader.SECONDARY_PROMPT_PATTERN, CONTINUATION)
            .build();

        try {
            connect();
            printNotice("connected to " + conn.profile() + " (env " + conn.env() + "); :help for meta-commands");
            refreshCompletion(conn);
        } catch (IOException e) {
            printNotice(e.getMessage());
            onDisconnect();
        }

        while (!quitting) {
            String line;
            try {
                line = reader.readLine(PROMPT);
            } catch (UserInterruptException e) {
                continue;
            } catch (EndOfFileException e) {
                break;
            }
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            try {
                if (trimmed.startsWith(":")) meta(trimmed);
                else send(trimmed);
            } catch (RuntimeException e) {
                printNotice("error: " + e.getMessage());
            }
        }
        quitting = true;
        Connection c = conn;
        if (c != null) c.close();
        terminal.writer().println();
        terminal.flush();
    }

    private void send(String code) {
        Connection c = conn;
        if (c == null || c.isClosed()) {
            printNotice("not connected");
            return;
        }
        JsonObject reply;
        try {
            reply = c.request("run", "code", code).get(120, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            printNotice("request timed out");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException e) {
            printNotice("not connected (" + rootMessage(e) + ")");
            return;
        }
        JsonObject data = Connection.obj(reply, "data");
        if (data != null) {
            for (JsonElement p : Connection.array(data, "prints")) recentPrints.add(p.getAsString());
            if (!recentPrints.isEmpty()) {
                if (dedupeTimer == null || !dedupeTimer.isAlive()) {
                    dedupeTimer = new Thread(() -> {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ignored) {
                        }
                        recentPrints.clear();
                    }, "hexcli-print-dedupe");
                    dedupeTimer.setDaemon(true);
                    dedupeTimer.start();
                }
            }
        }
        printLines(renderer.reply(reply));
    }

    private void meta(String line) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].strip() : "";
        switch (cmd) {
            case ":quit", ":q", ":exit" -> quitting = true;
            case ":help" -> printLines(help());
            case ":run" -> {
                if (rest.isEmpty()) printNotice("usage: :run <code>");
                else send(rest);
            }
            case ":stack" -> requestAndPrint("stack");
            case ":reset" -> requestAndPrint("reset");
            case ":env" -> {
                String[] a = rest.split("\\s+", 2);
                if (rest.isEmpty() || a[0].equalsIgnoreCase("list")) requestAndPrint("env.list");
                else if (a[0].equalsIgnoreCase("use")) {
                    if (a.length < 2) printNotice("usage: :env use <id>");
                    else requestAndPrint("env.use", "id", a[1].strip());
                } else printNotice("usage: :env [list|use <id>]");
            }
            case ":profile" -> {
                String[] a = rest.split("\\s+", 2);
                if (rest.isEmpty() || a[0].equalsIgnoreCase("list")) requestAndPrint("profile.list");
                else if (a[0].equalsIgnoreCase("show")) requestAndPrint("profile.show");
                else if (a[0].equalsIgnoreCase("use")) {
                    if (a.length < 2) printNotice("usage: :profile use <name>");
                    else requestAndPrint("profile.use", "name", a[1].strip());
                } else printNotice("usage: :profile [list|show|use <name>]");
            }
            case ":media" -> {
                if (rest.isEmpty()) printNotice("usage: :media <amount|infinite>");
                else requestAndPrint("media", "amount", rest);
            }
            case ":tick" -> {
                if (rest.isEmpty()) printNotice("usage: :tick <n>");
                else requestAndPrint(intField("tick", "ticks", rest));
            }
            case ":wait" -> {
                String[] a = rest.split("\\s+");
                JsonObject o = new JsonObject();
                o.addProperty("type", "wait");
                try {
                    if (a.length > 0 && !a[0].isBlank()) o.addProperty("quiet", Integer.parseInt(a[0]));
                    if (a.length > 1) o.addProperty("timeout", Integer.parseInt(a[1]));
                } catch (NumberFormatException e) {
                    printNotice("usage: :wait [quiet] [timeout] (integers)");
                    return;
                }
                requestAndPrint(o);
            }
            case ":cmd" -> {
                if (rest.isEmpty()) printNotice("usage: :cmd <hexcli subcommand>");
                else requestAndPrint("command", "line", rest);
            }
            default -> printNotice("unknown meta-command " + cmd + " (:help)");
        }
    }

    private List<AttributedString> help() {
        return List.of(
            new AttributedString("  plain input            run hexParse code"),
            new AttributedString("  :stack                 show the session stack"),
            new AttributedString("  :reset                 reset to the profile's initial image"),
            new AttributedString("  :env [list|use <id>]   list / switch casting environments"),
            new AttributedString("  :profile [list|show|use <name>]"),
            new AttributedString("  :media <amount|infinite>"),
            new AttributedString("  :tick <n>              let n ticks pass"),
            new AttributedString("  :wait [quiet] [timeout]"),
            new AttributedString("  :cmd <hexcli ...>      run any hexcli subcommand (caster/world/...)"),
            new AttributedString("  :run <code>            same as plain input"),
            new AttributedString("  :help / :quit (Ctrl-D)"));
    }

    /** 数值参数按数字发（服务器那边读的是 int）。 */
    private JsonObject intField(String type, String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        try {
            o.addProperty(key, Integer.parseInt(value.strip()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + value);
        }
        return o;
    }

    private void requestAndPrint(String type, String... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        requestAndPrint(o);
    }

    private void requestAndPrint(JsonObject req) {
        Connection c = conn;
        if (c == null || c.isClosed()) {
            printNotice("not connected");
            return;
        }
        String type = Connection.str(req, "type");
        long timeout = "wait".equals(type) ? 1300 : ("tick".equals(type) ? 1300 : 120);
        try {
            JsonObject reply = c.request(req).get(timeout, TimeUnit.SECONDS);
            printLines(renderer.reply(reply));
            if ("complete".equals(type)) completer.update(Connection.obj(reply, "data"));
        } catch (TimeoutException e) {
            printNotice("request timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            printNotice("not connected (" + rootMessage(e) + ")");
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /**
     * 多行输入：() [] {} 不平衡时抛 {@link EOFError}，JLine 会继续读下一行（续行提示符）。
     */
    static final class HexParser extends DefaultParser {
        @Override
        public ParsedLine parse(String line, int cursor, ParseContext context) {
            ParsedLine parsed = super.parse(line, cursor, context);
            if (context != ParseContext.COMPLETE) {
                if (unbalanced(line)) throw new EOFError(-1, -1, "unbalanced brackets");
            }
            return parsed;
        }

        private static boolean unbalanced(String text) {
            Deque<Character> stack = new ArrayDeque<>();
            boolean inString = false;
            char quote = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inString) {
                    if (c == '\\') i++;
                    else if (c == quote) inString = false;
                    continue;
                }
                if (c == '"' || c == '\'') {
                    inString = true;
                    quote = c;
                } else if (c == '(' || c == '[' || c == '{') {
                    stack.push(c);
                } else if (c == ')' || c == ']' || c == '}') {
                    if (stack.isEmpty()) return false; // 多出来的右括号：交给服务器报错
                    char open = stack.pop();
                    if ((c == ')' && open != '(') || (c == ']' && open != '[') || (c == '}' && open != '{')) return false;
                }
            }
            return !stack.isEmpty() || inString;
        }
    }
}
