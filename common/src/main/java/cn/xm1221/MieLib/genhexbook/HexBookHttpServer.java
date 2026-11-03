package cn.xm1221.MieLib.genhexbook;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/** Tiny static file server bound to 127.0.0.1 only. */
public final class HexBookHttpServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("GenHexBook");
    private static HttpServer server;

    private HexBookHttpServer() {}

    public static synchronized String start(Path root, int port) throws IOException {
        stopQuiet();
        Path base = root.toAbsolutePath().normalize();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        s.createContext("/", ex -> serve(ex, base));
        s.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "genhexbook-http");
            t.setDaemon(true);
            return t;
        }));
        s.start();
        server = s;
        String url = "http://127.0.0.1:" + port + "/";
        LOGGER.info("[GenHexBook] HTTP {}", url);
        return url;
    }

    public static synchronized boolean stopQuiet() {
        if (server == null) return false;
        try { server.stop(0); } catch (Exception ignored) {}
        server = null;
        return true;
    }

    private static void serve(HttpExchange ex, Path base) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod()) && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            String raw = ex.getRequestURI().getPath();
            if (raw == null || raw.isEmpty() || "/".equals(raw)) raw = "/index.html";
            String rel = raw.startsWith("/") ? raw.substring(1) : raw;
            Path target = base.resolve(rel).normalize();
            if (!target.startsWith(base) || !Files.isRegularFile(target)) {
                byte[] msg = "404".getBytes();
                ex.sendResponseHeaders(404, msg.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(msg); }
                return;
            }
            String ctype = contentType(target.getFileName().toString());
            ex.getResponseHeaders().add("Content-Type", ctype);
            byte[] data = Files.readAllBytes(target);
            ex.sendResponseHeaders(200, data.length);
            if (!"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                try (OutputStream os = ex.getResponseBody()) { os.write(data); }
            }
        } finally {
            ex.close();
        }
    }

    private static String contentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".md")) return "text/markdown; charset=utf-8";
        if (n.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }
}
