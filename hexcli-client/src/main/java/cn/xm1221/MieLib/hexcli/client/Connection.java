package cn.xm1221.MieLib.hexcli.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 到服务器 HexCLI 远程端点的连接：socket + 读线程 + 请求 id → CompletableFuture + 事件回调。
 * <p>
 * 协议见 HEXCLI.md：换行分隔 UTF-8 JSON；先 hello/token，之后带 id 的请求 → 带同 id 的 reply，
 * 服务器还会推送 {@code {"type":"event",...}}。
 */
public final class Connection implements Closeable {
    /** 服务器上 hexcli/client.json 的内容。 */
    public record Endpoint(String host, int port, String token, int version) {
    }

    private static final Gson GSON = new Gson();
    private static final int MAX_LINE = 4 * 1024 * 1024;

    private final Socket socket;
    private final OutputStream out;
    private final InputStream in;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final List<Consumer<JsonObject>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final List<Runnable> closeListeners = new CopyOnWriteArrayList<>();
    private final Object writeLock = new Object();
    private final Thread reader;

    private volatile JsonObject hello = new JsonObject();

    private Connection(Socket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.reader = new Thread(this::readLoop, "hexcli-client-reader");
        this.reader.setDaemon(true);
    }

    /** 连接并完成 hello 握手；失败抛 IOException。 */
    public static Connection open(Endpoint ep, long timeoutMillis) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ep.host(), ep.port()), (int) Math.min(Integer.MAX_VALUE, timeoutMillis));
        s.setSoTimeout((int) Math.min(Integer.MAX_VALUE, timeoutMillis));
        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();
        Connection c = new Connection(s, in, out);
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("token", ep.token());
        c.send(hello);
        JsonObject reply;
        try {
            String line = readLine(in);
            if (line == null) throw new IOException("connection closed during handshake");
            reply = JsonParser.parseString(line).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            s.close();
            throw new IOException("bad handshake reply: " + e.getMessage(), e);
        }
        if (!"hello".equals(str(reply, "type"))) {
            s.close();
            throw new IOException("unexpected handshake reply: " + reply);
        }
        if (!bool(reply, "ok", false)) {
            s.close();
            throw new IOException("server refused the connection: " + str(reply, "error"));
        }
        c.hello = reply;
        s.setSoTimeout(0);
        c.reader.start();
        return c;
    }

    public JsonObject hello() {
        return hello;
    }

    public String profile() {
        return str(hello, "profile");
    }

    public String env() {
        return str(hello, "env");
    }

    public boolean isClosed() {
        return closed.get() || socket.isClosed();
    }

    /** 连接断开时回调（自动重连用）。 */
    public void onClose(Runnable listener) {
        closeListeners.add(listener);
        if (isClosed()) listener.run();
    }

    /** 订阅服务器推送的事件（cast / print）；返回取消订阅。 */
    public Runnable onEvent(Consumer<JsonObject> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** 发一个请求，返回带 id 的 future；连接断开时以 IOException 完成。 */
    public CompletableFuture<JsonObject> request(JsonObject req) {
        int id = nextId.getAndIncrement();
        req.addProperty("id", id);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        if (isClosed()) {
            pending.remove(id);
            future.completeExceptionally(new IOException("not connected"));
            return future;
        }
        try {
            send(req);
        } catch (IOException e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    /** 便捷请求：{@code type} + 若干字符串参数。 */
    public CompletableFuture<JsonObject> request(String type, String... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        return request(o);
    }

    private void send(JsonObject msg) throws IOException {
        byte[] bytes = (GSON.toJson(msg) + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            out.write(bytes);
            out.flush();
        }
    }

    private void readLoop() {
        try {
            String line;
            while ((line = readLine(in)) != null) {
                if (line.isBlank()) continue;
                JsonObject msg;
                try {
                    msg = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue;
                }
                String type = str(msg, "type");
                if ("reply".equals(type)) {
                    JsonElement idEl = msg.get("id");
                    if (idEl != null && !idEl.isJsonNull()) {
                        CompletableFuture<JsonObject> f = pending.remove(idEl.getAsInt());
                        if (f != null) f.complete(msg);
                    }
                } else if ("event".equals(type)) {
                    for (Consumer<JsonObject> l : listeners) {
                        try {
                            l.accept(msg);
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // 连接断开
        } finally {
            shutdown(new IOException("connection closed"));
        }
    }

    private void shutdown(IOException why) {
        if (!closed.compareAndSet(false, true)) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        for (CompletableFuture<JsonObject> f : pending.values()) f.completeExceptionally(why);
        pending.clear();
        for (Runnable l : closeListeners) {
            try {
                l.run();
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void close() {
        shutdown(new IOException("closed by client"));
    }

    // ---- JSON 小工具 ----

    public static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull()) return null;
        try {
            return e.getAsString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static boolean bool(JsonObject o, String key, boolean dflt) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull()) return dflt;
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            return dflt;
        }
    }

    public static int integer(JsonObject o, String key, int dflt) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull()) return dflt;
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            return dflt;
        }
    }

    public static JsonArray array(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    public static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return buf.toString(StandardCharsets.UTF_8);
            if (b == '\r') continue;
            if (buf.size() >= MAX_LINE) throw new IOException("line too long");
            buf.write(b);
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
    }
}
