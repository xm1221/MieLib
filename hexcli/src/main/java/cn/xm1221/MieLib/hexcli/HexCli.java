package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.iota.Iota;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * HexCLI 入口（里程碑 1）。命令只需控制台权限，不需要玩家：
 * <ul>
 *   <li>{@code hexcli run <hexParse 代码>}：在会话 VM 里执行，打印栈 / 渡鸦之思 / 事故 / 媒质</li>
 *   <li>{@code > <代码>}：控制台快捷写法，等同 hexcli run</li>
 *   <li>{@code hexcli stack} / {@code hexcli reset}</li>
 *   <li>{@code hexcli tick <n>}：让时间走 n tick，汇报期间服务器上的其它施法</li>
 *   <li>{@code hexcli wait [quiet] [timeout]}：等到连续 quiet tick 没有任何施法（或超时）</li>
 * </ul>
 */
public final class HexCli implements ModInitializer {
    public static final String MOD_ID = "hexcli";
    public static final Logger LOGGER = LoggerFactory.getLogger("HexCLI");

    private static final List<Waiter> waiters = new ArrayList<>();

    @Override
    public void onInitialize() {
        CastObserver.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, ctx, sel) -> register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(CastObserver::bindServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            CastObserver.bindServer(null);
            HexCliSession.drop();
            waiters.forEach(Waiter::close);
            waiters.clear();
        });
        ServerTickEvents.END_SERVER_TICK.register(HexCli::tickWaiters);
        LOGGER.info("HexCLI loaded. Try: hexcli run 1 2 add   (or: > 1 2 add)");
    }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("hexcli").requires(s -> s.hasPermission(2))
            .then(Commands.literal("run").then(Commands.argument("code", StringArgumentType.greedyString())
                .executes(c -> run(c, StringArgumentType.getString(c, "code")))))
            .then(Commands.literal("stack").executes(HexCli::stack))
            .then(Commands.literal("reset").executes(HexCli::reset))
            .then(Commands.literal("tick").then(Commands.argument("ticks", IntegerArgumentType.integer(1, 12000))
                .executes(c -> waitFor(c, IntegerArgumentType.getInteger(c, "ticks"), -1))))
            .then(Commands.literal("wait")
                .executes(c -> waitFor(c, 600, 20))
                .then(Commands.argument("quiet", IntegerArgumentType.integer(1, 1200))
                    .executes(c -> waitFor(c, 600, IntegerArgumentType.getInteger(c, "quiet")))
                    .then(Commands.argument("timeout", IntegerArgumentType.integer(1, 12000))
                        .executes(c -> waitFor(c, IntegerArgumentType.getInteger(c, "timeout"),
                            IntegerArgumentType.getInteger(c, "quiet")))))));
    }

    private static void say(CommandSourceStack src, List<String> lines) {
        // 控制台源会把 sendSuccess 打进服务器日志；一行一条，方便 grep
        for (String l : lines) src.sendSuccess(() -> Component.literal(l), false);
    }

    private static int run(CommandContext<CommandSourceStack> ctx, String code) {
        CommandSourceStack src = ctx.getSource();
        HexCliSession session = HexCliSession.get(src.getServer());
        List<Iota> iotas;
        session.player.drainMessages();
        try {
            iotas = session.parse(code);
        } catch (Throwable t) {
            say(src, List.of("[HexCLI] parse error: " + t.getClass().getSimpleName() + ": " + t.getMessage()));
            LOGGER.debug("parse error", t);
            return 0;
        }
        List<String> parserMsgs = session.player.drainMessages();
        if (!parserMsgs.isEmpty()) {
            List<String> pm = new ArrayList<>();
            for (String m : parserMsgs) pm.add("[HexCLI] hexParse: " + m);
            say(src, pm);
        }
        if (iotas.isEmpty()) {
            say(src, List.of("[HexCLI] hexParse produced no iotas for: " + code));
            return 0;
        }
        try {
            CastEvent ev = session.run(iotas);
            List<String> out = new ArrayList<>();
            out.add("[HexCLI] > " + code + "  (" + iotas.size() + " iota" + (iotas.size() == 1 ? "" : "s") + ")");
            if (ev != null) out.addAll(ev.render());
            else out.add("[HexCLI] (no root cast event observed)");
            for (String p : session.printed) out.add("  printed: " + p);
            for (String m : session.player.drainMessages()) out.add("  message: " + m);
            say(src, out);
            return ev != null && ev.mishaps.isEmpty() ? 1 : 0;
        } catch (Throwable t) {
            LOGGER.error("HexCLI run crashed", t);
            say(src, List.of("[HexCLI] execution error: " + t.getClass().getSimpleName() + ": " + t.getMessage()));
            return 0;
        }
    }

    private static int stack(CommandContext<CommandSourceStack> ctx) {
        CastingImage img = HexCliSession.get(ctx.getSource().getServer()).image();
        List<String> out = new ArrayList<>();
        out.add("[HexCLI] session state");
        List<String> stack = new ArrayList<>();
        for (Iota i : img.getStack()) stack.add(CastEvent.safe(() -> i.display().getString()));
        CastEvent.renderStack(out, stack);
        String rm = CastEvent.ravenmindOf(img);
        out.add("  ravenmind: " + (rm == null ? "(none)" : rm));
        if (img.getParenCount() > 0) out.add("  open introspection depth: " + img.getParenCount());
        say(ctx.getSource(), out);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        HexCliSession.get(ctx.getSource().getServer()).reset();
        say(ctx.getSource(), List.of("[HexCLI] session reset (stack, ravenmind, parens cleared)"));
        return 1;
    }

    // ---- 让时间流逝：tick / wait ----

    private static final class Waiter {
        final CommandSourceStack src;
        final int startTick, timeout, quiet; // quiet < 0 表示固定 tick 数
        final List<CastEvent> seen = new ArrayList<>();
        final Runnable unsubscribe;

        Waiter(CommandSourceStack src, int startTick, int timeout, int quiet) {
            this.src = src;
            this.startTick = startTick;
            this.timeout = timeout;
            this.quiet = quiet;
            this.unsubscribe = CastObserver.subscribe(ev -> {
                if (!ev.root) seen.add(ev);
            });
        }

        void close() {
            unsubscribe.run();
        }
    }

    private static int waitFor(CommandContext<CommandSourceStack> ctx, int timeout, int quiet) {
        CommandSourceStack src = ctx.getSource();
        waiters.add(new Waiter(src, src.getServer().getTickCount(), timeout, quiet));
        say(src, List.of(quiet < 0
            ? "[HexCLI] letting " + timeout + " ticks pass..."
            : "[HexCLI] waiting until no casts for " + quiet + " ticks (timeout " + timeout + ")..."));
        return 1;
    }

    private static void tickWaiters(MinecraftServer server) {
        if (waiters.isEmpty()) return;
        int now = server.getTickCount();
        Iterator<Waiter> it = waiters.iterator();
        while (it.hasNext()) {
            Waiter w = it.next();
            int elapsed = now - w.startTick;
            int lastActivity = Math.max(CastObserver.lastActivityTick(), w.startTick);
            String reason;
            if (w.quiet >= 0 && now - lastActivity >= w.quiet) reason = "quiet for " + w.quiet + " ticks";
            else if (elapsed >= w.timeout) reason = w.quiet < 0 ? "done" : "TIMEOUT";
            else continue;
            it.remove();
            w.close();
            String ids = w.seen.isEmpty() ? "none"
                : w.seen.stream().map(e -> "#" + e.id).collect(Collectors.joining(" "));
            int mishaps = w.seen.stream().mapToInt(e -> e.mishaps.size()).sum();
            say(w.src, List.of("[HexCLI] " + reason + " after " + elapsed + " ticks; spawned casts: "
                + w.seen.size() + " (" + ids + "), mishaps: " + mishaps));
        }
    }
}
