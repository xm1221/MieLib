package cn.xm1221.MieLib.installer;

import java.io.IOException;
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
     * Downloads {@code url} into {@code target} (overwriting).
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
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(
                target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
            int status = response.statusCode();
            if (status == 404 || status == 410) {
                Files.deleteIfExists(target); // don't leave the error page behind
                return false;
            }
            if (status < 200 || status >= 300) {
                Files.deleteIfExists(target);
                throw new InstallerException("download failed: HTTP " + status + " for " + url);
            }
            return true;
        } catch (IOException e) {
            throw new InstallerException("download failed: " + url + " (" + e.getMessage() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InstallerException("interrupted while downloading " + url, e);
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
