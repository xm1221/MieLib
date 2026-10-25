package cn.xm1221.MieLib.hexcli.remote;

import cn.xm1221.MieLib.config.MielibServerConfig;
import cn.xm1221.MieLib.hexcli.CastEvent;
import cn.xm1221.MieLib.hexcli.CastObserver;
import cn.xm1221.MieLib.hexcli.HexCli;
import cn.xm1221.MieLib.hexcli.HexCliEnvs;
import cn.xm1221.MieLib.hexcli.HexCliSession;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HexCLI 远程客户端服务器（只在 HexCLI 启用时启动）。
 * <p>
 * 只绑定回环地址（{@link InetAddress#getLoopbackAddress()}），端口来自 {@code -Dmielib.hexcli.port}
 * 或 MieLib 服务端配置 {@code hexCliPort}（0 = 自动挑空闲端口），随机 token 写进
 * {@code <server dir>/hexcli/client.json}，服务器停止时删除。
 * <p>
 * 协议：换行分隔的 UTF-8 JSON。首条消息必须是 {@code {"type":"hello","token":"..."}}；
 * 之后 {@code {"id":<int>,"type":"..."}} 请求 → {@code {"id":..,"type":"reply","ok":..,"lines":[..],"data":{..}}}；
 * 服务器主动推送 {@code {"type":"event","event":"cast"|"print",...}}。
 * 所有游戏操作都通过 {@code server.submit(...)} 在服务器线程上执行，socket 线程只做 IO。
 */
public final class HexCliRemote {
    public static final int PROTOCOL_VERSION = 1;
    /** 单行 JSON 的最大长度。 */
    private static final int MAX_LINE = 1024 * 1024;
    /** 每个连接最多排队多少条待写消息（慢客户端时丢弃）。 */
    private static final int MAX_QUEUED = 4096;
    private static final Gson GSON = new Gson();

    private static final Object LOCK = new Object();
    private static @Nullable HexCliRemote current;

    private final MinecraftServer server;
    private final ServerSocket socket;
    private final String token;
    private final Path clientFile;
    private final Set<Conn> conns = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread acceptThread;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "HexCLI-remote-conn");
        t.setDaemon(true);
        return t;
    });
    private @Nullable Runnable unsubscribeCasts;

    private HexCliRemote(MinecraftServer server, ServerSocket socket, String token, Path clientFile) {
        this.server = server;
        this.socket = socket;
        this.token = token;
        this.clientFile = clientFile;
        this.acceptThread = new Thread(this::acceptLoop, "HexCLI-remote");
        this.acceptThread.setDaemon(true);
    }

    /** 由 HexCLI 在 SERVER_STARTED 调用。 */
    public static void start(MinecraftServer server) {
        synchronized (LOCK) {
            if (current != null) stopLocked();
            try {
                InetAddress loopback = InetAddress.getLoopbackAddress();
                int wanted = portSetting();
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(loopback, Math.max(0, wanted)));
                String token = newToken();
                Path file = clientFile(server);
                Path dir = file.getParent();
                Files.createDirectories(dir);
                JsonObject obj = new JsonObject();
                obj.addProperty("host", "127.0.0.1");
                obj.addProperty("port", ss.getLocalPort());
                obj.addProperty("token", token);
                obj.addProperty("version", PROTOCOL_VERSION);
                Files.writeString(file, GSON.toJson(obj) + "\n", StandardCharsets.UTF_8);
                HexCliRemote remote = new HexCliRemote(server, ss, token, file);
                remote.unsubscribeCasts = CastObserver.subscribe(ev -> {
                    if (!ev.root) remote.broadcastCast(ev);
                });
                remote.acceptThread.start();
                current = remote;
                HexCli.LOGGER.info("[HexCLI] remote client endpoint 127.0.0.1:{} (see hexcli/client.json)", ss.getLocalPort());
            } catch (IOException | RuntimeException e) {
                HexCli.LOGGER.error("[HexCLI] could not start the remote client endpoint: {}", e.toString());
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
        HexCliRemote r = current;
        current = null;
        if (r != null) r.shutdown();
    }

    /** 法术里的 print 推给所有客户端（客户端自己发起的 run 会收到重复，自行去重）。 */
    public static void pushPrint(String text) {
        HexCliRemote r;
        synchronized (LOCK) {
            r = current;
        }
        if (r == null) return;
        JsonObject ev = new JsonObject();
        ev.addProperty("type", "event");
        ev.addProperty("event", "print");
        ev.addProperty("text", text);
        r.broadcast(ev);
    }

    private static int portSetting() {
        String prop = System.getProperty("mielib.hexcli.port");
        if (prop != null && !prop.isBlank()) {
            try {
                return Integer.parseInt(prop.trim());
            } catch (NumberFormatException e) {
                HexCli.LOGGER.warn("[HexCLI] ignoring bad -Dmielib.hexcli.port={}", prop);
            }
        }
        try {
            return MielibServerConfig.getConfig().getHexCliPort();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Path clientFile(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve("hexcli").resolve("client.json");
    }

    private void shutdown() {
        running.set(false);
        Runnable un = unsubscribeCasts;
        unsubscribeCasts = null;
        if (un != null) un.run();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        for (Conn c : conns) c.close();
        conns.clear();
        workers.shutdownNow();
        try {
            Files.deleteIfExists(clientFile);
        } catch (IOException e) {
            HexCli.LOGGER.warn("[HexCLI] could not delete {}: {}", clientFile, e.toString());
        }
    }

    // ---- 连接 ----

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket s = socket.accept();
                Conn c = new Conn(s);
                conns.add(c);
                Thread t = new Thread(c::run, "HexCLI-remote-conn");
                t.setDaemon(true);
                t.start();
                Thread w = new Thread(c::writeLoop, "HexCLI-remote-write");
                w.setDaemon(true);
                w.start();
            } catch (IOException e) {
                if (running.get()) HexCli.LOGGER.warn("[HexCLI] remote accept failed: {}", e.toString());
                return;
            }
        }
    }

    private final class Conn {
        private final Socket socket;
        private final Object writeLock = new Object();
        private final Object writeQueue = new Object();
        private final java.util.ArrayDeque<Pending> queue = new java.util.ArrayDeque<>();
        private final AtomicBoolean authed = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        /** {@link #run} 里 try-with-resources 的输出流；广播事件用它（不能传 null）。 */
        private volatile OutputStream outStream;

        Conn(Socket socket) {
            this.socket = socket;
        }

        /** 待写的一条消息。 */
        private record Pending(OutputStream out, byte[] bytes) {
        }

        /** 独立的写线程：socket 阻塞只影响它自己。 */
        private void writeLoop() {
            while (!closed.get()) {
                Pending p;
                synchronized (writeQueue) {
                    while (queue.isEmpty() && !closed.get()) {
                        try {
                            writeQueue.wait(500);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    p = queue.poll();
                }
                if (p != null) writeNow(p.out(), p.bytes());
            }
        }

        void run() {
            try (InputStream in = new BufferedInputStream(socket.getInputStream());
                 OutputStream out = socket.getOutputStream()) {
                outStream = out;
                while (running.get() && !closed.get()) {
                    String line = readLineLimited(in);
                    if (line == null) break;
                    if (line.isBlank()) continue;
                    JsonObject msg;
                    try {
                        JsonElement parsed = JsonParser.parseString(line);
                        if (!parsed.isJsonObject()) throw new JsonParseException("not a JSON object");
                        msg = parsed.getAsJsonObject();
                    } catch (RuntimeException e) {
                        write(out, errorReply(null, "malformed JSON: " + e.getMessage()));
                        continue;
                    }
                    String type = str(msg, "type");
                    if (!authed.get()) {
                        if (!"hello".equals(type)) {
                            write(out, errorReply(id(msg), "expected hello"));
                            close();
                            return;
                        }
                        if (!token.equals(str(msg, "token"))) {
                            JsonObject bad = new JsonObject();
                            bad.addProperty("type", "hello");
                            bad.addProperty("ok", false);
                            bad.addProperty("error", "bad token");
                            write(out, bad);
                            close();
                            return;
                        }
                        authed.set(true);
                        sendHello(out);
                        continue;
                    }
                    dispatch(msg, out);
                }
            } catch (IOException e) {
                // 客户端断开
            } finally {
                close();
                conns.remove(this);
            }
        }

        private void sendHello(OutputStream out) {
            try {
                server.submit(() -> {
                    JsonObject hello = new JsonObject();
                    hello.addProperty("type", "hello");
                    hello.addProperty("ok", true);
                    hello.addProperty("version", PROTOCOL_VERSION);
                    try {
                        HexCliSession s = HexCliSession.get(server);
                        hello.addProperty("profile", s.profileName());
                        hello.addProperty("env", s.effectiveEnvIdString());
                    } catch (RuntimeException e) {
                        hello.addProperty("profile", "(unknown)");
                        hello.addProperty("env", "(unknown)");
                    }
                    write(out, hello);
                    return null;
                });
            } catch (RejectedExecutionException | IllegalStateException e) {
                write(out, errorReply(null, "server is not accepting work: " + e.getMessage()));
            }
        }

        private void dispatch(JsonObject msg, OutputStream out) {
            Long id = id(msg);
            String type = str(msg, "type");
            if (type == null) {
                write(out, errorReply(id, "missing type"));
                return;
            }
            try {
                switch (type) {
                    case "run" -> submit(() -> HexCliOps.run(server, strOr(msg, "code", "")), out, id);
                    case "step.begin" -> submit(() -> HexCliOps.stepBegin(server, strOr(msg, "code", ""), strOr(msg, "label", null)), out, id);
                    case "step.next" -> submit(() -> HexCliOps.stepNext(server), out, id);
                    case "step.abort" -> submit(() -> HexCliOps.stepAbort(server), out, id);
                    case "stack" -> submit(() -> HexCliOps.stack(server), out, id);
                    case "reset" -> submit(() -> HexCliOps.reset(server), out, id);
                    case "env.list" -> submit(() -> HexCliOps.envList(server), out, id);
                    case "env.use" -> {
                        // payload field must NOT be "id" — that is the request correlation number
                        String env = str(msg, "env");
                        if (env == null) env = str(msg, "name"); // tolerate old/alternate clients
                        submit(() -> HexCliOps.envUse(server, env), out, id);
                    }
                    case "profile.list" -> submit(() -> HexCliOps.profileList(server), out, id);
                    case "profile.show" -> submit(() -> HexCliOps.profileShow(server), out, id);
                    case "profile.use" -> {
                        String name = strOr(msg, "name", "");
                        submit(() -> HexCliOps.profileUse(server, name), out, id);
                    }
                    case "media" -> {
                        String amount = strOr(msg, "amount", "infinite");
                        submit(() -> HexCliOps.media(server, amount), out, id);
                    }
                    case "tick" -> {
                        int ticks = intOr(msg, "ticks", 100);
                        submitWait(ticks, -1, out, id);
                    }
                    case "wait" -> {
                        int quiet = intOr(msg, "quiet", 20);
                        int timeout = intOr(msg, "timeout", 600);
                        submitWait(timeout, quiet, out, id);
                    }
                    case "command" -> {
                        String line = strOr(msg, "line", "");
                        submit(() -> HexCliOps.command(server, line), out, id);
                    }
                    case "complete" -> submit(() -> HexCliOps.complete(server), out, id);
                    case "ping" -> {
                        JsonObject data = new JsonObject();
                        data.addProperty("pong", true);
                        HexCliReply reply = HexCliReply.ok(List.of(), data);
                        write(out, replyJson(id, reply));
                    }
                    default -> write(out, errorReply(id, "unknown request type '" + type + "'"));
                }
            } catch (RuntimeException e) {
                write(out, errorReply(id, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        /** 在服务器线程上执行并回复。 */
        private void submit(java.util.function.Supplier<HexCliReply> task, OutputStream out, @Nullable Long id) {
            CompletableFuture<HexCliReply> future;
            try {
                future = server.submit(task);
            } catch (RejectedExecutionException | IllegalStateException e) {
                write(out, errorReply(id, "server is not accepting work: " + e.getMessage()));
                return;
            }
            future.whenComplete((reply, err) -> {
                if (err != null) write(out, errorReply(id, rootMessage(err)));
                else write(out, replyJson(id, reply));
            });
        }

        /** tick / wait：立即注册等待，等它真正结束时才回复（提示行 + 结果行一起发）。 */
        private void submitWait(int timeout, int quiet, OutputStream out, @Nullable Long id) {
            // 每个请求自己的提示行（同一连接可能并发多个 wait）
            java.util.concurrent.atomic.AtomicReference<List<String>> started =
                new java.util.concurrent.atomic.AtomicReference<>(List.of());
            CompletableFuture<HexCliReply> future;
            try {
                future = server.submit(() -> HexCliOps.wait(server, timeout, quiet, done -> {
                    List<String> lines = new ArrayList<>(started.get());
                    lines.addAll(done.lines());
                    write(out, replyJson(id, new HexCliReply(lines, done.data(), done.ok())));
                }));
            } catch (RejectedExecutionException | IllegalStateException e) {
                write(out, errorReply(id, "server is not accepting work: " + e.getMessage()));
                return;
            }
            future.whenComplete((reply, err) -> {
                if (err != null) write(out, errorReply(id, rootMessage(err)));
                else if (reply != null) started.set(reply.lines());
            });
        }

        private void write(OutputStream out, JsonObject msg) {
            if (out == null) out = outStream;
            if (closed.get() || out == null) return;
            byte[] bytes = (GSON.toJson(msg) + "\n").getBytes(StandardCharsets.UTF_8);
            // 写操作走独立线程：广播是从服务器 tick 线程发起的，不能被慢客户端 / 阻塞的 socket 拖住。
            synchronized (writeQueue) {
                if (queue.size() >= MAX_QUEUED) return; // 慢客户端：丢弃新消息，别把服务器内存吃光
                queue.add(new Pending(out, bytes));
                writeQueue.notifyAll();
            }
        }

        private void writeNow(OutputStream out, byte[] bytes) {
            if (closed.get() || out == null) return;
            synchronized (writeLock) {
                try {
                    out.write(bytes);
                    out.flush();
                } catch (IOException e) {
                    close();
                }
            }
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            synchronized (writeQueue) {
                writeQueue.notifyAll();
            }
        }
    }

    private void broadcastCast(CastEvent ev) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "event");
        msg.addProperty("event", "cast");
        msg.add("cast", ev.toJson());
        broadcast(msg);
    }

    private void broadcast(JsonObject msg) {
        if (conns.isEmpty()) return;
        for (Conn c : conns) {
            if (c.authed.get()) c.write(null, msg);
        }
    }

    // ---- JSON 小工具 ----

    private static JsonObject replyJson(@Nullable Long id, HexCliReply reply) {
        JsonObject o = new JsonObject();
        if (id != null) o.addProperty("id", id);
        o.addProperty("type", "reply");
        o.addProperty("ok", reply.ok());
        JsonArray lines = new JsonArray();
        for (String l : reply.lines()) lines.add(l);
        o.add("lines", lines);
        o.add("data", reply.data());
        return o;
    }

    private static JsonObject errorReply(@Nullable Long id, String error) {
        JsonObject o = new JsonObject();
        if (id != null) o.addProperty("id", id);
        o.addProperty("type", "reply");
        o.addProperty("ok", false);
        o.add("lines", new JsonArray());
        JsonObject data = new JsonObject();
        data.addProperty("error", error == null ? "unknown error" : error);
        o.add("data", data);
        return o;
    }

    private static @Nullable Long id(JsonObject msg) {
        JsonElement e = msg.get("id");
        if (e == null || e.isJsonNull()) return null;
        try {
            return e.getAsLong();
        } catch (RuntimeException ex) {
            return null;
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

    private static String strOr(JsonObject msg, String key, String dflt) {
        String s = str(msg, key);
        return s == null ? dflt : s;
    }

    private static int intOr(JsonObject msg, String key, int dflt) {
        JsonElement e = msg.get(key);
        if (e == null || e.isJsonNull()) return dflt;
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            return dflt;
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /** 读一行（\n 结尾），最多 {@link #MAX_LINE} 字节；EOF 返回 null。 */
    private static @Nullable String readLineLimited(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return buf.toString(StandardCharsets.UTF_8);
            if (b == '\r') continue;
            if (buf.size() >= MAX_LINE) throw new IOException("line too long (>" + MAX_LINE + " bytes)");
            buf.write(b);
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
    }
}
