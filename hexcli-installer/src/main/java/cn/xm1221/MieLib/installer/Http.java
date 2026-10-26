package cn.xm1221.MieLib.installer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;

/** HTTP helpers around {@link java.net.http.HttpClient}. */
final class Http {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    private Http() {
    }

    /** Shared client: follows redirects (GitHub release assets redirect twice). */
    static HttpClient client() {
        return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    }

    static URI uri(String url, String what) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new InstallerException(what + " must be an http(s) URL: " + url);
            }
            if (uri.getHost() == null) {
                throw new InstallerException(what + " has no host: " + url);
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new InstallerException(what + " is not a valid URL: " + url, e);
        }
    }

    /**
     * Downloads {@code url} into {@code target} (overwriting), showing a progress bar.
     *
     * @return {@code false} for a 404/410 (callers try the next fallback URL), {@code true} on success.
     */
    static boolean download(HttpClient client, String url, Path target, String what) {
        HttpRequest request = HttpRequest.newBuilder(uri(url, what))
            .timeout(DOWNLOAD_TIMEOUT)
            .header("User-Agent", Installer.USER_AGENT)
            .GET()
            .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                response.body().close();
                Files.deleteIfExists(target); // don't leave a stale / partial file behind
                if (status == 404 || status == 410) {
                    return false;
                }
                throw new InstallerException("download failed: HTTP " + status + " for " + url);
            }
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            Progress progress = new Progress(target.getFileName().toString(), total);
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(target,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                    progress.advance(n);
                }
            } catch (IOException e) {
                progress.abort();
                Files.deleteIfExists(target);
                throw e;
            }
            progress.finish();
            return true;
        } catch (IOException e) {
            throw new InstallerException("download failed: " + url + " (" + e.getMessage() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InstallerException("interrupted while downloading " + url, e);
        }
    }

    /**
     * Console progress bar: {@code   [=========>          ]  45%  12.3/27.1 MB  3.2 MB/s  name}.
     *
     * <p>On a terminal the line is redrawn in place (about 10x per second) and cleared when done; when
     * output is redirected (CI, logs) a line is printed every 25% instead. {@code HEXCLI_PROGRESS=0}
     * turns it off, {@code HEXCLI_PROGRESS=1} forces the in-place bar.
     */
    static final class Progress {
        private static final int WIDTH = 30;
        private final String name;
        private final long total;
        private final boolean tty;
        private final boolean enabled;
        private final long startNanos = System.nanoTime();
        private long done;
        private long lastDraw;
        private int lastQuarter;
        private int lastLength;

        Progress(String name, long total) {
            this.name = name.endsWith(".part") ? name.substring(0, name.length() - 5) : name;
            this.total = total;
            String mode = System.getenv("HEXCLI_PROGRESS");
            this.enabled = !"0".equals(mode) && !"false".equalsIgnoreCase(mode);
            this.tty = "1".equals(mode) || "true".equalsIgnoreCase(mode) || System.console() != null;
        }

        void advance(int bytes) {
            done += bytes;
            if (!enabled) {
                return;
            }
            if (tty) {
                long now = System.nanoTime();
                if (now - lastDraw >= 100_000_000L) {
                    lastDraw = now;
                    draw();
                }
            } else if (total >= 2L * 1024 * 1024) { // small files: just "done"
                int quarter = (int) (done * 4 / total);
                if (quarter > lastQuarter && quarter < 4) {
                    lastQuarter = quarter;
                    System.out.println("           " + (quarter * 25) + "%  " + mb(done) + "/" + mb(total) + " MB  " + name);
                }
            }
        }

        void finish() {
            if (!enabled) {
                return;
            }
            if (tty) {
                clear();
            }
            double seconds = Math.max((System.nanoTime() - startNanos) / 1e9, 0.001);
            System.out.println("           done " + mb(done) + " MB in " + String.format(Locale.ROOT, "%.1f", seconds)
                + " s (" + mb((long) (done / seconds)) + " MB/s)");
        }

        void abort() {
            if (enabled && tty) {
                clear();
            }
        }

        private void draw() {
            StringBuilder line = new StringBuilder("  [");
            if (total > 0) {
                int filled = (int) Math.min(WIDTH, done * WIDTH / total);
                for (int i = 0; i < WIDTH; i++) {
                    line.append(i < filled ? '=' : i == filled ? '>' : ' ');
                }
                line.append("] ").append(String.format(Locale.ROOT, "%3d%%", (int) Math.min(100, done * 100 / total)))
                    .append("  ").append(mb(done)).append('/').append(mb(total)).append(" MB");
            } else {
                // Unknown size: bouncing marker.
                int pos = (int) ((System.nanoTime() - startNanos) / 100_000_000L % WIDTH);
                for (int i = 0; i < WIDTH; i++) {
                    line.append(i == pos ? '=' : ' ');
                }
                line.append("]  ").append(mb(done)).append(" MB");
            }
            double seconds = Math.max((System.nanoTime() - startNanos) / 1e9, 0.001);
            line.append("  ").append(mb((long) (done / seconds))).append(" MB/s  ").append(name);
            String text = line.toString();
            int pad = Math.max(0, lastLength - text.length());
            System.out.print("\r" + text + " ".repeat(pad));
            System.out.flush();
            lastLength = text.length();
        }

        private void clear() {
            if (lastLength > 0) {
                System.out.print("\r" + " ".repeat(lastLength) + "\r");
                System.out.flush();
                lastLength = 0;
            }
        }

        static String mb(long bytes) {
            return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
        }
    }

    /** Fetches a small text document (used by {@code --manifest <url>}). */
    static String fetchText(HttpClient client, String url, String what) {
        HttpRequest request = HttpRequest.newBuilder(uri(url, what))
            .timeout(Duration.ofMinutes(2))
            .header("User-Agent", Installer.USER_AGENT)
            .GET()
            .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new InstallerException("fetch failed: HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (IOException e) {
            throw new InstallerException("fetch failed: " + url + " (" + e.getMessage() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InstallerException("interrupted while fetching " + url, e);
        }
    }
}
