package cn.xm1221.MieLib.genhexbook;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

/**
 * {@code /genHexBook} — render Hex Casting Patchouli book (thehexbook)
 * to a hexdoc-style static site using the live action registry.
 * See docs/genhexbook.md.
 */
public final class GenHexBook {
    public static final Logger LOGGER = LoggerFactory.getLogger("GenHexBook");

    private GenHexBook() {
    }

    /** Called from Mielib.init(). Cheap when unused. */
    public static void init() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> register(dispatcher, registry));
        LifecycleEvent.SERVER_STOPPING.register(server -> HexBookHttpServer.stopQuiet());
        LOGGER.info("[GenHexBook] command /genHexBook registered");
    }

    private static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext build) {
        var root = Commands.literal("genHexBook").requires(s -> s.hasPermission(2));

        root.executes(c -> run(c, null, "both", false, null));

        root.then(Commands.literal("out")
            .then(Commands.argument("dir", StringArgumentType.string())
                .executes(c -> run(c, StringArgumentType.getString(c, "dir"), "both", false, null))));

        root.then(Commands.literal("lang")
            .then(Commands.argument("lang", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"en_us", "zh_cn", "both"}, b))
                .executes(c -> run(c, null, StringArgumentType.getString(c, "lang"), false, null))));

        root.then(Commands.literal("md")
            .executes(c -> run(c, null, "both", true, null)));

        root.then(Commands.literal("serve")
            .executes(c -> run(c, null, "both", false, 8765))
            .then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
                .executes(c -> run(c, null, "both", false, IntegerArgumentType.getInteger(c, "port")))));

        // Combined: /genHexBook export <dir> <lang> <md:yes|no> [servePort]
        root.then(Commands.literal("export")
            .then(Commands.argument("dir", StringArgumentType.string())
                .then(Commands.argument("lang", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"en_us", "zh_cn", "both"}, b))
                    .then(Commands.argument("md", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"yes", "no"}, b))
                        .executes(c -> run(c,
                            StringArgumentType.getString(c, "dir"),
                            StringArgumentType.getString(c, "lang"),
                            "yes".equalsIgnoreCase(StringArgumentType.getString(c, "md")),
                            null))
                        .then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
                            .executes(c -> run(c,
                                StringArgumentType.getString(c, "dir"),
                                StringArgumentType.getString(c, "lang"),
                                "yes".equalsIgnoreCase(StringArgumentType.getString(c, "md")),
                                IntegerArgumentType.getInteger(c, "port"))))))));

        root.then(Commands.literal("stop").executes(c -> {
            boolean stopped = HexBookHttpServer.stopQuiet();
            c.getSource().sendSuccess(() -> Component.literal(stopped
                ? "[GenHexBook] HTTP server stopped"
                : "[GenHexBook] no HTTP server was running"), false);
            return stopped ? 1 : 0;
        }));

        root.then(Commands.literal("deploy-hint").executes(c -> {
            for (String line : DeployHint.lines()) {
                c.getSource().sendSuccess(() -> Component.literal(line), false);
            }
            return 1;
        }));

        d.register(root);
    }

    private static int run(CommandContext<CommandSourceStack> c, String outRel, String lang,
                           boolean md, Integer servePort) throws CommandSyntaxException {
        MinecraftServer server = c.getSource().getServer();
        Path gameDir = server.getServerDirectory().toPath().toAbsolutePath().normalize();
        Path outDir = (outRel == null || outRel.isBlank())
            ? gameDir.resolve("hexbook")
            : (Path.of(outRel).isAbsolute() ? Path.of(outRel) : gameDir.resolve(outRel));
        lang = lang == null ? "both" : lang.toLowerCase(Locale.ROOT);

        String langFinal = lang;
        c.getSource().sendSuccess(() -> Component.literal(
            "[GenHexBook] generating → " + outDir + " (lang=" + langFinal + ", md=" + md + ")"), true);
        try {
            GenHexBookGenerator.Result result = GenHexBookGenerator.generate(server, outDir, lang, md);
            c.getSource().sendSuccess(() -> Component.literal(
                "[GenHexBook] done: " + result.categories() + " categories, "
                    + result.entries() + " entries, " + result.patterns() + " patterns → " + outDir), true);
            if (servePort != null) {
                String url = HexBookHttpServer.start(outDir, servePort);
                c.getSource().sendSuccess(() -> Component.literal("[GenHexBook] serving " + url), true);
            }
            return 1;
        } catch (Exception e) {
            LOGGER.error("[GenHexBook] failed", e);
            c.getSource().sendFailure(Component.literal("[GenHexBook] failed: " + e.getMessage()));
            return 0;
        }
    }
}
