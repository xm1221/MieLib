/*
 * Builds the HexCLI installer distribution archives. JDK-only (single-file source launch):
 *
 *   java hexcli-installer/tools/PackageDist.java \
 *       --version 1.0.0 --output <dir> --installer <hexcli-installer.jar> \
 *       --client <hexcli-client.jar> --dist hexcli-installer/dist
 *
 * Writes:
 *   <output>/hexcli-<version>-linux.tar.gz
 *   <output>/hexcli-<version>-macos.tar.gz
 *   <output>/hexcli-<version>-windows.zip
 *   <output>/SHA256SUMS
 *
 * The archives are flat (no top-level directory) and contain only the installer jar, the client
 * fat jar, the bootstrap scripts and README.txt. Tar entries use fixed timestamps and modes
 * (0755 for *.sh, 0644 for everything else) so the output is reproducible.
 *
 * This is a build-time tool; it is not shipped in the archives.
 */

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PackageDist {
    /** Fixed timestamp for reproducible archives (2024-01-01T00:00:00Z). */
    private static final long FIXED_TIME_SECONDS = 1704067200L;
    /** The DOS epoch, the earliest timestamp a zip entry can store. */
    private static final long ZIP_TIME_MILLIS = 315532800000L; // 1980-01-01T00:00:00Z

    private static final List<String> UNIX_FILES = List.of("README.txt", "install.sh");
    private static final List<String> WINDOWS_FILES = List.of("README.txt", "install.ps1", "install.bat");

    public static void main(String[] argv) throws IOException {
        Map<String, String> args = parseArgs(argv);
        String version = required(args, "--version");
        Path output = Path.of(required(args, "--output"));
        Path installer = Path.of(required(args, "--installer"));
        Path client = Path.of(required(args, "--client"));
        Path dist = Path.of(required(args, "--dist"));

        for (Path required : List.of(installer, client)) {
            if (!Files.isRegularFile(required)) {
                throw new IllegalArgumentException("missing input file: " + required);
            }
        }
        Files.createDirectories(output);

        // Staging directories mirror what ends up in each archive.
        Path staging = output.resolve(".staging");
        Path linux = stage(staging.resolve("linux"), installer, client, dist, UNIX_FILES);
        Path macos = stage(staging.resolve("macos"), installer, client, dist, UNIX_FILES);
        Path windows = stage(staging.resolve("windows"), installer, client, dist, WINDOWS_FILES);

        Path linuxTar = output.resolve("hexcli-" + version + "-linux.tar.gz");
        Path macosTar = output.resolve("hexcli-" + version + "-macos.tar.gz");
        Path windowsZip = output.resolve("hexcli-" + version + "-windows.zip");
        writeTarGz(linux, linuxTar);
        writeTarGz(macos, macosTar);
        writeZip(windows, windowsZip);

        // Remove the staging tree, then write SHA256SUMS over the archives.
        deleteRecursively(staging);
        List<Path> archives = new ArrayList<>(List.of(linuxTar, macosTar, windowsZip));
        archives.sort(Comparator.comparing(p -> p.getFileName().toString()));
        StringBuilder sums = new StringBuilder();
        for (Path archive : archives) {
            sums.append(sha256(archive)).append("  ").append(archive.getFileName()).append('\n');
            System.out.println("packaged " + archive);
        }
        Files.writeString(output.resolve("SHA256SUMS"), sums.toString(), StandardCharsets.UTF_8);
        System.out.println("wrote " + output.resolve("SHA256SUMS"));
    }

    // ---- staging ----

    private static Path stage(Path dir, Path installer, Path client, Path dist,
                              List<String> files) throws IOException {
        deleteRecursively(dir);
        Files.createDirectories(dir);
        Files.copy(installer, dir.resolve("hexcli-installer.jar"));
        Files.copy(client, dir.resolve("hexcli-client.jar"));
        for (String name : files) {
            Path source = dist.resolve(name);
            if (!Files.isRegularFile(source)) {
                throw new IllegalArgumentException("missing dist file: " + source);
            }
            Files.copy(source, dir.resolve(name));
        }
        return dir;
    }

    // ---- tar.gz (ustar, POSIX) ----

    private static void writeTarGz(Path dir, Path target) throws IOException {
        List<Path> files = listFiles(dir);
        try (OutputStream fileOut = Files.newOutputStream(target);
             GZIPOutputStream gz = new GZIPOutputStream(fileOut, 1 << 16)) {
            for (Path file : files) {
                String name = dir.relativize(file).toString().replace('\\', '/');
                byte[] data = Files.readAllBytes(file);
                int mode = name.endsWith(".sh") ? 0755 : 0644;
                gz.write(tarHeader(name, mode, data.length));
                gz.write(data);
                int pad = (512 - data.length % 512) % 512;
                if (pad > 0) {
                    gz.write(new byte[pad]);
                }
            }
            // End of archive: two zero blocks.
            gz.write(new byte[1024]);
        }
    }

    private static byte[] tarHeader(String name, int mode, long size) {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 100) {
            throw new IllegalArgumentException("tar entry name too long: " + name);
        }
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        putOctal(header, 100, 8, mode);          // mode
        putOctal(header, 108, 8, 0);             // uid
        putOctal(header, 116, 8, 0);             // gid
        putOctal(header, 124, 12, size);         // size
        putOctal(header, 136, 12, FIXED_TIME_SECONDS); // mtime
        header[156] = '0';                       // typeflag: regular file
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
        header[263] = '0';
        header[264] = '0';                       // version "00"
        System.arraycopy("root".getBytes(StandardCharsets.US_ASCII), 0, header, 265, 4);   // uname
        System.arraycopy("root".getBytes(StandardCharsets.US_ASCII), 0, header, 297, 4);   // gname
        // Checksum: sum of all header bytes with the checksum field treated as spaces.
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        int sum = 0;
        for (byte b : header) {
            sum += b & 0xFF;
        }
        // 6 octal digits, NUL, space.
        putOctal(header, 148, 7, sum);
        header[154] = 0;
        header[155] = ' ';
        return header;
    }

    /** Writes {@code value} as zero-padded octal into {@code length} bytes, NUL-terminated. */
    private static void putOctal(byte[] header, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        if (octal.length() > length - 1) {
            throw new IllegalArgumentException("value " + value + " does not fit in " + length + " octal bytes");
        }
        int pad = length - 1 - octal.length();
        for (int i = 0; i < pad; i++) {
            header[offset + i] = '0';
        }
        byte[] digits = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(digits, 0, header, offset + pad, digits.length);
        header[offset + length - 1] = 0;
    }

    // ---- zip ----

    private static void writeZip(Path dir, Path target) throws IOException {
        List<Path> files = listFiles(dir);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Path file : files) {
                String name = dir.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(ZIP_TIME_MILLIS);
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    // ---- helpers ----

    private static List<Path> listFiles(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(p -> dir.relativize(p).toString()))
                .toList();
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    md.update(buffer, 0, read);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static Map<String, String> parseArgs(String[] argv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + arg);
            }
            if (i + 1 >= argv.length) {
                throw new IllegalArgumentException(arg + " needs a value");
            }
            out.put(arg, argv[++i]);
        }
        return out;
    }

    private static String required(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required argument " + key);
        }
        return value;
    }

    private PackageDist() {
    }
}
