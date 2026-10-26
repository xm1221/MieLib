package cn.xm1221.MieLib.installer;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HexCLI one-click installer.
 *
 * <pre>
 *   java -jar hexcli-installer.jar install [--dir &lt;dir&gt;] [--accept-eula] [--manifest &lt;file|url&gt;]
 *                                          [--client-jar &lt;path&gt;] [--mod-override name=path]...
 *   java -jar hexcli-installer.jar verify  [--dir &lt;dir&gt;] [--manifest &lt;file|url&gt;] [--mod-override name=path]...
 * </pre>
 *
 * <p>The installer never bundles Minecraft or mod jars: everything is downloaded and verified
 * against the embedded manifest, and the Fabric server launcher fetches the vanilla server from
 * Mojang on first start.
 */
public final class Installer {
    static final String USER_AGENT = "xm1221-MieLib-hexcli-installer/1.0 (+https://github.com/xm1221/MieLib)";

    private static final String USAGE = """
        HexCLI installer <version>

        Usage:
          java -jar hexcli-installer.jar install [options]
          java -jar hexcli-installer.jar verify  [options]

        Commands:
          install   download and verify the Fabric server launcher and mods, then write the
                    eula / server.properties and the start / connect scripts
          verify    re-check every downloaded file against the manifest hashes

        Options:
          --dir <dir>            server directory (default: ./server)
          --accept-eula          accept https://aka.ms/MinecraftEULA (required for install;
                                 scripts ask first, or set HEXCLI_ACCEPT_EULA=1)
          --manifest <file|url>  use a different manifest (default: the one embedded in this jar)
          --client-jar <path>    hexcli-client fat jar to copy into <dir> for connect.sh / connect.bat
          --mod-override name=path
                                 use a local jar for one mod instead of downloading it; the file is
                                 still checked against the manifest hashes
                                 (env HEXCLI_MIELIB_JAR is shorthand for --mod-override mielib=<path>)
          --java <path>          record this Java executable in <dir>/java-path.txt for the
                                 generated scripts (install.sh does this automatically)
          -h, --help             show this help
        """;

    private Installer() {
    }

    public static void main(String[] argv) {
        int status;
        try {
            status = run(argv);
        } catch (InstallerException e) {
            System.err.println("error: " + e.getMessage());
            status = 1;
        } catch (RuntimeException e) {
            System.err.println("error: unexpected failure: " + e);
            e.printStackTrace(System.err);
            status = 1;
        }
        System.exit(status);
    }

    static int run(String[] argv) {
        Args args = Args.parse(argv);
        if (args.help) {
            System.out.println(USAGE.replace("<version>", manifestInstallerVersionOrUnknown()));
            return 0;
        }
        if (args.command == null) {
            System.err.println("error: missing command (install or verify)");
            System.err.println();
            System.err.println(USAGE);
            return 2;
        }

        HttpClient client = Http.client();
        String manifestText = args.manifest != null
            ? ModDownloader.readManifestSource(client, args.manifest)
            : ModDownloader.embeddedManifest();
        Manifest manifest = Manifest.parse(manifestText, "manifest");

        Path serverDir = args.dir.toAbsolutePath().normalize();
        // The extracted archive root holds runtime/ and (usually) hexcli-client.jar.
        Path archiveRoot = serverDir.getParent() != null ? serverDir.getParent() : serverDir;

        return switch (args.command) {
            case "install" -> install(args, manifest, client, serverDir, archiveRoot);
            case "verify" -> verify(args, manifest, serverDir);
            default -> throw new InstallerException("unknown command \"" + args.command + "\" (install or verify)");
        };
    }

    private static int install(Args args, Manifest manifest, HttpClient client, Path serverDir, Path archiveRoot) {
        if (!args.acceptEula) {
            System.err.println("error: the Minecraft EULA has not been accepted.");
            System.err.println("       Read https://aka.ms/MinecraftEULA and re-run with --accept-eula");
            System.err.println("       (or set HEXCLI_ACCEPT_EULA=1).");
            return 3;
        }

        System.out.println("HexCLI installer " + manifest.installerVersion
            + " (Minecraft " + manifest.minecraft + ", Fabric loader " + manifest.fabricLoader + ")");
        System.out.println("server directory: " + serverDir);
        Fs.createDirectories(serverDir);
        Fs.createDirectories(serverDir.resolve("mods"));

        // 1. Fabric server launcher. Nothing Mojang is bundled; the launcher downloads the
        //    vanilla server itself on first start.
        Path launcher = serverDir.resolve(manifest.serverLauncher.fileName());
        if (isUsableFile(launcher)) {
            System.out.println("  ok       " + launcher.getFileName() + " (already present)");
        } else {
            System.out.println("  fetch    " + launcher.getFileName() + " <- " + manifest.serverLauncher.url());
            if (!Http.download(client, manifest.serverLauncher.url(), launcher, "serverLauncher")) {
                throw new InstallerException("the Fabric server launcher is not available at "
                    + manifest.serverLauncher.url() + " (HTTP 404/410); check fabricLoader / fabricInstaller in the manifest");
            }
        }

        // 2. Mods.
        Map<String, Path> overrides = ModDownloader.parseOverrides(args.modOverrides);
        ModDownloader.Stats stats = new ModDownloader(client, serverDir).install(manifest.mods, overrides);
        System.out.println("mods: " + stats.downloaded + " downloaded, " + stats.skipped + " already present, "
            + stats.overridden + " local");

        // 3. The client fat jar, so connect.sh / connect.bat work from the server directory.
        Path clientJar = ModDownloader.findClientJar(args.clientJar, serverDir, archiveRoot);
        ModDownloader.installClientJar(serverDir, clientJar);

        // 4. EULA, server.properties, scripts. Existing user files are kept.
        ServerFiles files = new ServerFiles(manifest.installerVersion);
        boolean eula = files.writeEula(serverDir);
        boolean properties = files.writeServerProperties(serverDir);
        files.writeJavaPath(serverDir, args.javaPath);
        files.writeScripts(serverDir);
        System.out.println("  " + (eula ? "wrote  " : "kept   ") + "eula.txt");
        System.out.println("  " + (properties ? "wrote  " : "kept   ") + "server.properties");
        System.out.println("  wrote  start-server / connect / start-and-connect scripts (.sh and .bat)");

        System.out.println();
        System.out.println("Done. Start the server with:");
        System.out.println("  Linux/macOS: ./start-and-connect.sh");
        System.out.println("  Windows:     start-and-connect.bat");
        System.out.println("(or start-server.sh / start-server.bat and then connect.sh / connect.bat)");
        return 0;
    }

    private static int verify(Args args, Manifest manifest, Path serverDir) {
        System.out.println("verifying " + serverDir + " against manifest " + manifest.installerVersion
            + " (Minecraft " + manifest.minecraft + ", Fabric loader " + manifest.fabricLoader + ")");
        boolean ok = true;

        Path launcher = serverDir.resolve(manifest.serverLauncher.fileName());
        if (isUsableFile(launcher)) {
            System.out.println("  ok       " + launcher.getFileName());
        } else {
            System.out.println("  MISSING  " + launcher.getFileName());
            ok = false;
        }

        Map<String, Path> overrides = ModDownloader.parseOverrides(args.modOverrides);
        ModDownloader downloader = new ModDownloader(Http.client(), serverDir);
        if (!downloader.verify(manifest.mods)) {
            ok = false;
        }
        // Local overrides are verified too (they are what the user asked to be used).
        if (!overrides.isEmpty()) {
            System.out.println("local overrides:");
            for (Manifest.Mod mod : manifest.mods) {
                Path override = overrides.get(mod.name());
                if (override == null) {
                    continue;
                }
                try {
                    if (!Files.isRegularFile(override)) {
                        System.out.println("  MISSING  " + override + " (" + mod.name() + ")");
                        ok = false;
                    } else {
                        System.out.println("  ok       " + override + " (" + mod.name() + ")");
                    }
                } catch (RuntimeException e) {
                    System.out.println("  BAD      " + override + " (" + e.getMessage() + ")");
                    ok = false;
                }
            }
        }

        System.out.println(ok ? "verify: OK" : "verify: FAILED (re-run install to repair)");
        return ok ? 0 : 1;
    }

    /** {@code true} for an existing, non-empty regular file (a failed earlier download leaves an empty jar). */
    private static boolean isUsableFile(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static String manifestInstallerVersionOrUnknown() {
        try {
            return Manifest.parse(ModDownloader.embeddedManifest(), "manifest").installerVersion;
        } catch (RuntimeException e) {
            return "(unknown)";
        }
    }

    /** Parsed command line. */
    static final class Args {
        String command;
        Path dir = Path.of("server");
        boolean acceptEula;
        String manifest;
        Path clientJar;
        String javaPath;
        final List<String> modOverrides = new ArrayList<>();
        boolean help;

        static Args parse(String[] argv) {
            Args args = new Args();
            // Scripts pass the Java they installed so the generated scripts work even without
            // a private runtime in <root>/runtime.
            args.javaPath = System.getProperty("hexcli.java");
            String eulaEnv = System.getenv("HEXCLI_ACCEPT_EULA");
            args.acceptEula = "1".equals(eulaEnv) || "true".equalsIgnoreCase(eulaEnv);

            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                switch (arg) {
                    case "install", "verify" -> {
                        if (args.command != null) {
                            throw new InstallerException("give one command, not \"" + args.command + "\" and \"" + arg + "\"");
                        }
                        args.command = arg;
                    }
                    case "--dir" -> args.dir = Path.of(value(argv, ++i, "--dir"));
                    case "--accept-eula", "--yes", "-y" -> args.acceptEula = true;
                    case "--manifest" -> args.manifest = value(argv, ++i, "--manifest");
                    case "--client-jar" -> args.clientJar = Path.of(value(argv, ++i, "--client-jar"));
                    case "--mod-override" -> args.modOverrides.add(value(argv, ++i, "--mod-override"));
                    case "--java" -> args.javaPath = value(argv, ++i, "--java");
                    case "-h", "--help", "help" -> args.help = true;
                    default -> throw new InstallerException("unknown argument \"" + arg + "\" (try --help)");
                }
            }
            return args;
        }

        private static String value(String[] argv, int index, String option) {
            if (index >= argv.length) {
                throw new InstallerException(option + " needs a value");
            }
            return argv[index];
        }
    }
}
