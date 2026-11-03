package cn.xm1221.MieLib.hexcli.remote;

import cn.xm1221.MieLib.Mielib;
import cn.xm1221.MieLib.config.MielibServerConfig;
import cn.xm1221.MieLib.hexcli.HexCli;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.architectury.platform.Platform;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * HexCLI 的 HTTP JSON 端点（可选，默认关闭；浏览器前端用）。
 * <p>
 * 浏览器不能直接连 {@link HexCliRemote} 的 TCP，所以这里用 JDK 自带的
 * {@link com.sun.net.httpserver.HttpServer} 包一层：{@code GET /v1/health} +
 * {@code POST /v1/rpc}，命令与 {@link HexCliOps} 一一对应，施法逻辑不在这里重写。
 * <p>
 * 开关与地址见 {@link #httpEnabled()} 等：系统属性优先，其次 MieLib 服务端配置。
 * 启动时把 host / port / token / protocol 写进 {@code <server dir>/hexcli/http.json}，停止时删除
 * （与 {@code client.json} 对称）。所有游戏操作都通过 {@code server.submit(...).get(...)} 在服务器线程执行。
 * <p>
 * 协议文档见 {@code docs/hexcli-http.md}（权威版本在 HexSimulator 仓库的 {@code docs/PROTOCOL.md}）。
 */
public final class HexCliHttp {
    public static final int PROTOCOL_VERSION = 1;
    /** 与 PROTOCOL.md 的 {@code /v1/health.server} 一致。 */
    public static final String SERVER_NAME = "mielib-hexcli";

    /** 启用 HTTP 端点的系统属性。 */
    public static final String PROP_ENABLE = "mielib.hexcli.http";
    /** 端口（0 = 自动挑空闲端口）的系统属性。 */
    public static final String PROP_PORT = "mielib.hexcli.http.port";
    /** 绑定地址的系统属性（默认显式 127.0.0.1，不用 getLoopbackAddress()，见 {@link #bindSetting()}）。 */
    public static final String PROP_BIND = "mielib.hexcli.http.bind";
    /** CORS 白名单的系统属性（{@code *} 或逗号分隔的 origin，允许 {@code http://host:*} 通配端口）。 */
    public static final String PROP_CORS = "mielib.hexcli.http.cors";

    /** 默认 CORS 白名单：本机任意端口的页面。 */
    public static final String DEFAULT_CORS = "http://127.0.0.1:*,http://localhost:*";

    /** 请求体最大字节数（与 TCP remote 的单行上限一致）。 */
    private static final int MAX_BODY = 1024 * 1024;
    /** 单次 RPC 等待服务器线程的最长时间。 */
    private static final long RPC_TIMEOUT_SECONDS = 120;

    private static final Gson GSON = new Gson();
    private static final Object LOCK = new Object();
    private static @Nullable HexCliHttp current;

    private final HttpServer http;
    private final MinecraftServer server;
    private final String token;
    private final Path httpFile;
    private final List<String> cors;
    private final ExecutorService workers;

    private HexCliHttp(HttpServer http, MinecraftServer server, String token, Path httpFile, List<String> cors) {
        this.http = http;
        this.server = server;
        this.token = token;
        this.httpFile = httpFile;
        this.cors = cors;
        this.workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "HexCLI-http");
            t.setDaemon(true);
            return t;
        });
    }

    /** 由 HexCLI 在 SERVER_STARTED 调用（仅在 HTTP 开关打开且 HexCLI 已 active 时）。 */
    public static void start(MinecraftServer server) {
        synchronized (LOCK) {
            if (current != null) stopLocked();
            if (!httpEnabled()) return;
            try {
                String bind = bindSetting();
                // 显式用 getByName：InetAddress.getLoopbackAddress() 在 Forge 上常返回 ::1，
                // 而前端 / 文档都写 127.0.0.1，IPv4 客户端会连不上。
                InetAddress addr = InetAddress.getByName(bind);
                int wanted = Math.max(0, portSetting());
                HttpServer http = HttpServer.create(new InetSocketAddress(addr, wanted), 64);
                String token = HexCliRemote.token();
                if (token == null) token = newToken();

                Path file = httpFile(server);
                Files.createDirectories(file.getParent());
                JsonObject obj = new JsonObject();
                obj.addProperty("host", bind);
                obj.addProperty("port", http.getAddress().getPort());
                obj.addProperty("token", token);
                obj.addProperty("protocol", PROTOCOL_VERSION);
                obj.addProperty("version", PROTOCOL_VERSION);
                Files.writeString(file, GSON.toJson(obj) + "\n", StandardCharsets.UTF_8);

                HexCliHttp endpoint = new HexCliHttp(http, server, token, file, corsSetting());
                http.createContext("/v1/health", endpoint::handleHealth);
                http.createContext("/v1/rpc", endpoint::handleRpc);
                http.setExecutor(endpoint.workers);
                http.start();
                current = endpoint;
                HexCli.LOGGER.info("[HexCLI] HTTP endpoint http://{}:{}/v1 (see hexcli/http.json)",
                    bind, http.getAddress().getPort());
            } catch (IOException | RuntimeException e) {
                HexCli.LOGGER.error("[HexCLI] could not start the HTTP endpoint: {}", e.toString());
            }
        }
    }

    /** 由 HexCLI 在 SERVER_STOPPING 调用。 */
    public static void stop() {
        synchronized (LOCK) {
            if (current != null) stopLocked();
        }
    }

    private static void stopLocked() {
        HexCliHttp h = current;
        current = null;
        if (h != null) h.shutdown();
    }

    private void shutdown() {
        try {
            http.stop(0);
        } catch (RuntimeException ignored) {
        }
        workers.shutdownNow();
        try {
            Files.deleteIfExists(httpFile);
        } catch (IOException e) {
            HexCli.LOGGER.warn("[HexCLI] could not delete {}: {}", httpFile, e.toString());
        }
    }

    // ---- 开关（系统属性优先，其次 MieLib 服务端配置） ----

    /** HTTP 端点是否启用；还需要 HexCLI 本身已启用（调用方保证）。 */
    public static boolean httpEnabled() {
        String prop = System.getProperty(PROP_ENABLE);
        if (prop != null) return prop.equals("true");
        try {
            return MielibServerConfig.getConfig().getEnableHexCliHttp();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static int portSetting() {
        String prop = System.getProperty(PROP_PORT);
        if (prop != null && !prop.isBlank()) {
            try {
                return Integer.parseInt(prop.trim());
            } catch (NumberFormatException e) {
                HexCli.LOGGER.warn("[HexCLI] ignoring bad -D{}={}", PROP_PORT, prop);
            }
        }
        try {
            return MielibServerConfig.getConfig().getHexCliHttpPort();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    /** 绑定地址：默认显式 127.0.0.1（不要用 InetAddress.getLoopbackAddress()，它常是 ::1）。 */
    private static String bindSetting() {
        String prop = System.getProperty(PROP_BIND);
        if (prop != null && !prop.isBlank()) return prop.trim();
        try {
            String cfg = MielibServerConfig.getConfig().getHexCliHttpBind();
            if (cfg != null && !cfg.isBlank()) return cfg.trim();
        } catch (RuntimeException | LinkageError ignored) {
            // 配置未初始化：用默认值
        }
        return "127.0.0.1";
    }

    private static List<String> corsSetting() {
        String raw = System.getProperty(PROP_CORS);
        if (raw == null || raw.isBlank()) {
            try {
                raw = MielibServerConfig.getConfig().getHexCliHttpCors();
            } catch (RuntimeException | LinkageError e) {
                raw = null;
            }
        }
        if (raw == null || raw.isBlank()) raw = DEFAULT_CORS;
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Path httpFile(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve("hexcli").resolve("http.json");
    }

    private static String modVersion() {
        try {
            return Platform.getOptionalMod(Mielib.MODID).map(m -> m.getVersion()).orElse("dev");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ---- 处理请求 ----

    private void handleHealth(HttpExchange ex) {
        try {
            if (preflightOrReject(ex)) return;
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                corsHeaders(ex);
                sendJson(ex, 405, errorJson(null, "method not allowed; use GET"));
                return;
            }
            corsHeaders(ex);
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("protocol", PROTOCOL_VERSION);
            out.addProperty("server", SERVER_NAME);
            out.addProperty("version", modVersion());
            sendJson(ex, 200, out);
        } catch (Throwable t) {
            HexCli.LOGGER.error("[HexCLI] HTTP health failed: {}", t.toString(), t);
            try { sendJson(ex, 500, errorJson(null, rootMessage(t))); } catch (Throwable ignored) {}
        } finally {
            ex.close();
        }
    }

    private void handleRpc(HttpExchange ex) {
        try {
            if (preflightOrReject(ex)) return;
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                corsHeaders(ex);
                sendJson(ex, 405, errorJson(null, "method not allowed; use POST"));
                return;
            }
            corsHeaders(ex);

            JsonObject req;
            try {
                req = readBody(ex.getRequestBody());
            } catch (JsonParseException | IOException e) {
                sendJson(ex, 400, errorJson(null, "malformed JSON: " + e.getMessage()));
                return;
            }
            if (req == null) {
                sendJson(ex, 400, errorJson(null, "request body must be a JSON object"));
                return;
            }

            JsonElement id = req.get("id");
            if (!authorized(ex, req)) {
                sendJson(ex, 401, errorJson(id, "bad token"));
                return;
            }

            String cmd = str(req, "cmd");
            if (cmd == null) {
                sendJson(ex, 400, errorJson(id, "missing cmd"));
                return;
            }


            String code = strOr(req, "code", "");
            String label = strOr(req, "label", null);
            String name = strOr(req, "name", "");
            // env.use 的字段与请求 id 无关：优先 "env"，兼容 "envId"
            String env = strOr(req, "env", strOr(req, "envId", null));

            Supplier<HexCliReply> task = command(cmd, code, label, name, env);
            if (task == null) {
                JsonObject data = new JsonObject();
                data.addProperty("error", "unknown cmd '" + cmd + "'");
                sendJson(ex, 200, replyJson(id, new HexCliReply(List.of(), data, false)));
                return;
            }

            HexCliReply reply;
            try {
                reply = submit(task).get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (RejectedExecutionException | IllegalStateException e) {
                sendJson(ex, 200, errorJson(id, "server is not accepting work: " + e.getMessage()));
                return;
            } catch (TimeoutException e) {
                sendJson(ex, 200, errorJson(id, "timed out after " + RPC_TIMEOUT_SECONDS + "s"));
                return;
            } catch (ExecutionException e) {
                sendJson(ex, 200, errorJson(id, rootMessage(e)));
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendJson(ex, 200, errorJson(id, "interrupted"));
                return;
            }
            sendJson(ex, 200, replyJson(id, reply));
        } catch (RuntimeException e) {
            HexCli.LOGGER.warn("[HexCLI] HTTP request failed: {}", e.toString());
            try {
                sendJson(ex, 500, errorJson(null, rootMessage(e)));
            } catch (RuntimeException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    private CompletableFuture<HexCliReply> submit(Supplier<HexCliReply> task) {
        return server.submit(task);
    }

    /** 命令映射：与 {@link HexCliRemote} 的 TCP 请求类型一一对应。返回 null 表示未知 cmd。 */
    private @Nullable Supplier<HexCliReply> command(String cmd, String code, @Nullable String label,
                                                    String name, @Nullable String env) {
        MinecraftServer s = server;
        switch (cmd) {
            case "ping":
                return () -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("pong", true);
                    return HexCliReply.ok(List.of(), data);
                };
            case "run":
                return () -> HexCliOps.run(s, code);
            case "reset":
                return () -> HexCliOps.reset(s);
            case "stack":
                return () -> HexCliOps.stack(s);
            case "step.begin":
                return () -> HexCliOps.stepBegin(s, code, label);
            case "step.next":
                return () -> HexCliOps.stepNext(s);
            case "step.abort":
                return () -> HexCliOps.stepAbort(s);
            case "complete":
                return () -> HexCliOps.complete(s);
            case "profile.list":
                return () -> HexCliOps.profileList(s);
            case "profile.show":
                return () -> HexCliOps.profileShow(s);
            case "profile.use":
                return () -> HexCliOps.profileUse(s, name);
            case "env.list":
                return () -> HexCliOps.envList(s);
            case "env.use": {
                String target = env;
                if (target == null) {
                    return () -> HexCliReply.fail(List.of(
                        "[HexCLI] env.use missing field 'env'"), errorData("env.use missing field 'env'"));
                }
                return () -> HexCliOps.envUse(s, target);
            }
            default:
                return null;
        }
    }

    // ---- 鉴权 / CORS ----

    private boolean authorized(HttpExchange ex, JsonObject req) {
        String presented = null;
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header != null) {
            String h = header.trim();
            if (h.regionMatches(true, 0, "Bearer ", 0, 7)) presented = h.substring(7).trim();
        }
        if (presented == null || presented.isEmpty()) {
            JsonElement t = req.get("token");
            if (t != null && !t.isJsonNull()) {
                try {
                    presented = t.getAsString();
                } catch (RuntimeException ignored) {
                }
            }
        }
        if (presented == null) return false;
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
            token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 处理 OPTIONS 预检；来源不在白名单时直接 403。
     *
     * @return true 表示已经响应（预检 / 被拒），调用方应直接返回
     */
    private boolean preflightOrReject(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !originAllowed(origin)) {
            corsHeaders(ex);
            sendJson(ex, 403, errorJson(null, "origin not allowed"));
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            corsHeaders(ex);
            ex.getResponseHeaders().set("Access-Control-Max-Age", "600");
            try {
                ex.sendResponseHeaders(204, -1);
            } catch (IOException ignored) {
            }
            return true;
        }
        return false;
    }

    private boolean originAllowed(String origin) {
        String o = origin.toLowerCase(Locale.ROOT);
        for (String entry : cors) {
            if ("*".equals(entry) || entry.equals(o)) return true;
            // http://localhost:* —— 只通配端口
            if (entry.endsWith(":*")) {
                String prefix = entry.substring(0, entry.length() - 1); // 保留末尾 ':'
                if (o.startsWith(prefix)) {
                    String port = o.substring(prefix.length());
                    if (!port.isEmpty() && port.chars().allMatch(Character::isDigit)) return true;
                }
            }
        }
        return false;
    }

    private void corsHeaders(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || !originAllowed(origin)) return;
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        ex.getResponseHeaders().set("Vary", "Origin");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
    }

    // ---- JSON / IO 小工具 ----

    private static @Nullable JsonObject readBody(InputStream in) throws IOException {
        byte[] buf = in.readNBytes(MAX_BODY + 1);
        if (buf.length > MAX_BODY) throw new IOException("request body too large (>" + MAX_BODY + " bytes)");
        String body = new String(buf, StandardCharsets.UTF_8);
        if (body.isBlank()) return null;
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) throw new JsonParseException("not a JSON object");
        return parsed.getAsJsonObject();
    }

    private static JsonObject replyJson(@Nullable JsonElement id, HexCliReply reply) {
        JsonObject o = new JsonObject();
        if (id != null) o.add("id", id);
        o.addProperty("ok", reply.ok());
        JsonArray lines = new JsonArray();
        for (String l : reply.lines()) lines.add(l);
        o.add("lines", lines);
        o.add("data", reply.data());
        return o;
    }

    private static JsonObject errorJson(@Nullable JsonElement id, String error) {
        JsonObject data = new JsonObject();
        data.addProperty("error", error == null ? "unknown error" : error);
        JsonObject o = new JsonObject();
        if (id != null && !(id instanceof JsonNull)) o.add("id", id);
        o.addProperty("ok", false);
        o.add("lines", new JsonArray());
        o.add("data", data);
        return o;
    }

    private static JsonObject errorData(String error) {
        JsonObject data = new JsonObject();
        data.addProperty("error", error);
        return data;
    }

    private static void sendJson(HttpExchange ex, int status, JsonObject body) {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        try {
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            // 客户端断开，或响应头已发送失败
            HexCli.LOGGER.debug("[HexCLI] HTTP write failed: {}", e.toString());
        }
    }

    private static @Nullable String str(JsonObject msg, String key) {
        JsonElement e = msg.get(key);
        if (e == null || e.isJsonNull()) return null;
        try {
            return e.getAsString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String strOr(JsonObject msg, String key, @Nullable String dflt) {
        String s = str(msg, key);
        return s == null ? dflt : s;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
