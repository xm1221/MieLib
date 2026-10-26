package cn.xm1221.MieLib.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Digest helpers. Only JDK crypto is used; no third-party dependencies. */
final class Hashes {
    private Hashes() {
    }

    static String sha512(Path file) throws IOException {
        return digest("SHA-512", file);
    }

    static String sha1(Path file) throws IOException {
        return digest("SHA-1", file);
    }

    private static String digest(String algorithm, Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            // Every Java 17 runtime is required to provide SHA-1 and SHA-512.
            throw new IllegalStateException(algorithm + " is not available", e);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                md.update(buffer, 0, read);
            }
        }
        return hex(md.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
