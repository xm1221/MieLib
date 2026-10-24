package cn.xm1221.MieLib.hexcli.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 客户端命令行参数与 {@code client.json} 读取。 */
public final class ClientConfig {
    /** 服务器上 hexcli/client.json 的内容。 */
    public static final class Endpoint {
        public String host = "127.0.0.1";
        public int port;
        public String token;
        public int version = 1;
    }

    /** 解析后的参数。 */
    public static final class Args {
        public Path dir;
        public String host;
        public Integer port;
        public String token;
        public String exec;
        public boolean noColor;
        public boolean help;
        public final List<String> errors = new ArrayList<>();
    }

    public static final String USAGE = """
        HexCLI terminal client

        Usage: java -jar hexcli-client-<version>-all.jar [options]

        Options:
          --dir <server dir>   directory containing hexcli/client.json
                               (default: current dir, then ./run/hexcli)
          --host <host>        override the host from client.json
          --port <port>        override the port from client.json
          --token <token>      override the token from client.json
          -e, --exec <code>    run one snippet, print the reply and exit
                               (exit code 0 = ok, 1 = mishap / error)
          --no-color           disable colors (also auto-disabled when not a tty)
          -h, --help           show this help

        REPL meta-commands: :stack :reset :env [list|use <id>]
          :profile [list|show|use <name>] :media <amount> :tick <n>
          :wait [quiet] [timeout] :cmd <hexcli subcommand> :help :quit
        Any other line is run as hexParse code. Multi-line input continues
        while (), [] or {} are unbalanced. Ctrl-D / :quit exits.
        """;

    private ClientConfig() {
    }

    public static Args parse(String[] argv) {
        Args a = new Args();
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            switch (arg) {
                case "--dir" -> a.dir = Path.of(need(argv, ++i, "--dir", a));
                case "--host" -> a.host = need(argv, ++i, "--host", a);
                case "--port" -> {
                    String v = need(argv, ++i, "--port", a);
                    if (v != null) {
                        try {
                            a.port = Integer.parseInt(v);
                        } catch (NumberFormatException e) {
                            a.errors.add("bad --port '" + v + "'");
                        }
                    }
                }
                case "--token" -> a.token = need(argv, ++i, "--token", a);
                case "--exec", "-e" -> a.exec = need(argv, ++i, "--exec", a);
                case "--no-color" -> a.noColor = true;
                case "--help", "-h" -> a.help = true;
                default -> a.errors.add("unknown option '" + arg + "'");
            }
        }
        return a;
    }

    private static String need(String[] argv, int i, String name, Args a) {
        if (i >= argv.length) {
            a.errors.add(name + " needs a value");
            return null;
        }
        return argv[i];
    }

    /** 找 {@code hexcli/client.json}：显式 --dir，否则当前目录、./run/hexcli。 */
    public static Path findClientFile(Path dirArg) throws IOException {
        if (dirArg != null) {
            Path p = clientFileIn(dirArg);
            if (!Files.isRegularFile(p)) throw new IOException("no " + p);
            return p;
        }
        List<Path> candidates = List.of(
            Path.of("").toAbsolutePath(),
            Path.of("").toAbsolutePath().resolve("run").resolve("hexcli"),
            Path.of("").toAbsolutePath().resolve("fabric").resolve("run").resolve("hexcli"),
            Path.of("").toAbsolutePath().resolve("forge").resolve("run").resolve("hexcli"));
        for (Path c : candidates) {
            Path p = clientFileIn(c);
            if (Files.isRegularFile(p)) return p;
        }
        throw new IOException("no hexcli/client.json found in " + candidates
            + "; start the server with HexCLI enabled or pass --dir");
    }

    private static Path clientFileIn(Path dir) {
        Path direct = dir.resolve("client.json");
        if (Files.isRegularFile(direct)) return direct; // --dir 直接指向 hexcli/ 的情况
        return dir.resolve("hexcli").resolve("client.json");
    }

    public static Endpoint read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        try {
            Endpoint ep = new Gson().fromJson(JsonParser.parseString(text), Endpoint.class);
            if (ep == null) throw new IOException("empty " + file);
            if (ep.host == null) ep.host = "127.0.0.1";
            return ep;
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("bad JSON in " + file + ": " + e.getMessage());
        }
    }

    /** 读取并应用命令行覆盖。 */
    public static Connection.Endpoint endpoint(Args args) throws IOException {
        Endpoint ep = read(findClientFile(args.dir));
        String host = args.host != null ? args.host : ep.host;
        int port = args.port != null ? args.port : ep.port;
        String token = args.token != null ? args.token : ep.token;
        if (token == null) throw new IOException("client.json has no token");
        return new Connection.Endpoint(host, port, token, ep.version);
    }

    /** 读原始 JSON（调试 / 未来扩展用）。 */
    public static JsonObject readJson(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
