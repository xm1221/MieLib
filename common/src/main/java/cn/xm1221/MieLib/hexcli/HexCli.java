package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import cn.xm1221.MieLib.hexcli.api.EnvSpec;
import cn.xm1221.MieLib.hexcli.api.HexCliAPI;
import cn.xm1221.MieLib.hexcli.HexCliProfiles.ProfileException;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec2;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.platform.Platform;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HexCLI 入口（MieLib 的一部分，默认关闭）。命令只需控制台权限，不需要玩家：
 * <ul>
 *   <li>{@code hexcli run <hexParse 代码>}：在会话 VM 里执行，打印栈 / 渡鸦之思 / 事故 / 媒质</li>
 *   <li>{@code > <代码>}：控制台快捷写法，等同 hexcli run</li>
 *   <li>{@code hexcli stack} / {@code hexcli reset}</li>
 *   <li>{@code hexcli tick <n>}：让时间走 n tick，汇报期间服务器上的其它施法</li>
 *   <li>{@code hexcli wait [quiet] [timeout]}：等到连续 quiet tick 没有任何施法（或超时）</li>
 * </ul>
 * 开关见 {@link HexCliConfig}：{@code -Dmielib.hexcli=true} 或 MieLib 服务端配置 {@code enableHexCli}。
 */
public final class HexCli {
    public static final Logger LOGGER = LoggerFactory.getLogger("HexCLI");

    /** 是否真正启用（{@link #init()} 判定一次；关闭或 hexParse 缺失时保持 false）。 */
    private static boolean active = false;

    private static final List<Waiter> waiters = new ArrayList<>();

    private HexCli() {
    }

    /** 由 {@code Mielib.init()} 调用；默认什么都不注册（见 {@link HexCliConfig}）。 */
    public static void init() {
        if (!HexCliConfig.isEnabled()) {
            LOGGER.info("[HexCLI] disabled (enable with -Dmielib.hexcli=true or enableHexCli=true in the MieLib server config)");
            return;
        }
        if (!HexCliConfig.isHexParseLoaded()) {
            LOGGER.error("[HexCLI] enabled but hexParse is not installed; HexCLI stays off");
            return;
        }
        CastObserver.init();
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> register(dispatcher, registry));
        LifecycleEvent.SERVER_STARTED.register(server -> {
            CastObserver.bindServer(server);
            HexCliProfiles.writeExamples(server);
            HexCliSession session = HexCliSession.get(server);
            try {
                for (String l : session.applyProfile("default")) LOGGER.info(l);
            } catch (ProfileException e) {
                LOGGER.error("[HexCLI] could not apply default profile: {}", e.getMessage());
            }
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            CastObserver.bindServer(null);
            HexCliSession.drop();
            waiters.forEach(Waiter::close);
            waiters.clear();
        });
        TickEvent.SERVER_POST.register(HexCli::tickWaiters);
        active = true;
        LOGGER.info("HexCLI loaded. Try: hexcli run 1 2 add   (or: > 1 2 add)");
    }

    /** HexCLI 是否已启用并注册了命令 / 事件（控制台 {@code >} 改写等据此判断）。 */
    public static boolean isActive() {
        return active;
    }

    private static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext build) {
        d.register(Commands.literal("hexcli").requires(s -> s.hasPermission(2))
            .then(Commands.literal("profile")
                .then(Commands.literal("list").executes(HexCli::profileList))
                .then(Commands.literal("show").executes(HexCli::profileShow))
                .then(Commands.literal("use").then(Commands.argument("name", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(HexCliProfiles.list(c.getSource().getServer()), b))
                    .executes(c -> profileUse(c, StringArgumentType.getString(c, "name"))))))
            .then(Commands.literal("env")
                .then(Commands.literal("list").executes(HexCli::envList))
                .then(Commands.literal("use")
                    .then(Commands.literal("auto").executes(c -> envUse(c, null)))
                    .then(Commands.argument("id", StringArgumentType.greedyString()) // word() 不收 ':'
                        .suggests((c, b) -> {
                            List<String> ids = new ArrayList<>();
                            ids.add("auto");
                            for (ResourceLocation rl : HexCliAPI.envs().keySet()) ids.add(rl.toString());
                            return SharedSuggestionProvider.suggest(ids, b);
                        })
                        .executes(c -> envUse(c, StringArgumentType.getString(c, "id"))))))
            .then(Commands.literal("caster")
                .then(Commands.literal("player").executes(c -> casterSet(c, "player", null)))
                .then(Commands.literal("none").executes(c -> casterSet(c, "none", null)))
                .then(Commands.literal("select").then(Commands.argument("target", EntityArgument.entity())
                    .executes(c -> casterSet(c, "select", EntityArgument.getEntity(c, "target")))))
                .then(Commands.literal("summon").then(Commands.argument("entity", ResourceArgument.resource(build, Registries.ENTITY_TYPE))
                    .executes(c -> casterSummon(c, null))
                    .then(Commands.argument("nbt", CompoundTagArgument.compoundTag())
                        .executes(c -> casterSummon(c, CompoundTagArgument.getCompoundTag(c, "nbt")))))))
            .then(Commands.literal("world").then(Commands.literal("summon")
                .then(Commands.argument("entity", ResourceArgument.resource(build, Registries.ENTITY_TYPE))
                    .then(Commands.argument("tag", StringArgumentType.word())
                        .executes(c -> worldSummon(c, null))
                        .then(Commands.argument("nbt", CompoundTagArgument.compoundTag())
                            .executes(c -> worldSummon(c, CompoundTagArgument.getCompoundTag(c, "nbt"))))))))
            .then(Commands.literal("media").then(Commands.argument("amount", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("infinite", "10000", "100000"), b))
                .executes(c -> media(c, StringArgumentType.getString(c, "amount")))))
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
        session.drainMessages();
        try {
            iotas = session.parse(code);
        } catch (Throwable t) {
            say(src, List.of("[HexCLI] parse error: " + t.getClass().getSimpleName() + ": " + t.getMessage()));
            LOGGER.debug("parse error", t);
            return 0;
        }
        List<String> parserMsgs = session.drainMessages();
        if (!parserMsgs.isEmpty()) {
            List<String> pm = new ArrayList<>();
            for (String m : parserMsgs) pm.add("[HexCLI] hexParse: " + m);
            say(src, pm);
        }
        if (iotas.isEmpty()) {
            say(src, List.of("[HexCLI] hexParse produced no iotas for: " + code));
            return 0;
        }
        CastingEnvironment env;
        try {
            env = session.createEnv();
        } catch (IllegalArgumentException e) {
            say(src, List.of("[HexCLI] cannot cast: " + e.getMessage()));
            return 0;
        }
        try {
            CastEvent ev = session.run(iotas, env);
            List<String> out = new ArrayList<>();
            out.add("[HexCLI] > " + code + "  (" + iotas.size() + " iota" + (iotas.size() == 1 ? "" : "s") + ")");
            if (ev != null) out.addAll(ev.render());
            else out.add("[HexCLI] (no root cast event observed)");
            for (String p : session.printed) out.add("  printed: " + p);
            for (String m : session.drainMessages()) out.add("  message: " + m);
            String disp = HexCliCircle.takeDisplay(env);
            if (disp != null) out.add("  impetus: " + disp);
            if (session.mediaMode() == HexCliProfile.Mode.NUMBER) out.add("  media left: " + session.mediaText());
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
        HexCliSession s = HexCliSession.get(ctx.getSource().getServer());
        say(ctx.getSource(), List.of("[HexCLI] session reset to profile '" + s.profileName() + "' initial image (stack "
            + s.image().getStack().size() + ", ravenmind " + (CastEvent.ravenmindOf(s.image()) == null ? "none" : "set") + ")"));
        return 1;
    }

    // ---- profile / caster / world / media ----

    private static int profileList(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String active = HexCliSession.get(server).profileName();
        List<String> out = new ArrayList<>();
        List<String> names = HexCliProfiles.list(server);
        out.add("[HexCLI] profiles in " + HexCliProfiles.dir(server) + " (" + names.size() + ")");
        for (String n : names) {
            String d = HexCliProfiles.descriptionOf(server, n);
            out.add((n.equals(active) ? "  * " : "    ") + n + (d == null ? "" : " - " + d));
        }
        say(ctx.getSource(), out);
        return names.size();
    }

    private static int profileUse(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            say(ctx.getSource(), HexCliSession.get(ctx.getSource().getServer()).applyProfile(name));
            return 1;
        } catch (ProfileException e) {
            say(ctx.getSource(), List.of("[HexCLI] profile error: " + e.getMessage()));
            return 0;
        } catch (RuntimeException e) {
            LOGGER.error("profile apply crashed", e);
            say(ctx.getSource(), List.of("[HexCLI] profile error: " + e));
            return 0;
        }
    }

    /** env list 的来源显示：spec.source 或 id 命名空间，能找到 mod 容器时带上 mod 名（走 Architectury Platform）。 */
    static String sourceLabel(ResourceLocation id, EnvSpec spec) {
        String src = spec.source() != null ? spec.source() : id.getNamespace();
        String name = Platform.getOptionalMod(src).map(m -> m.getName()).orElse(null);
        return name != null && !name.equals(src) ? src + " (" + name + ")" : src;
    }

    private static int envList(CommandContext<CommandSourceStack> ctx) {
        HexCliSession s = HexCliSession.get(ctx.getSource().getServer());
        List<String> out = new ArrayList<>();
        out.add("[HexCLI] registered envs (auto: player->" + HexCliAPI.STAFF + ", none->" + HexCliAPI.CIRCLE + ", "
            + "living->the only env accepting living):");
        ResourceLocation effective;
        try {
            effective = s.resolveEnv();
        } catch (RuntimeException e) {
            effective = null;
        }
        for (Map.Entry<ResourceLocation, EnvSpec> e : HexCliAPI.envs().entrySet()) {
            ResourceLocation id = e.getKey();
            EnvSpec spec = e.getValue();
            out.add((id.equals(effective) ? "  * " : "    ") + id
                + "  casters=" + spec.castersText()
                + "  source=" + sourceLabel(id, spec)
                + "  - " + spec.description());
        }
        String cur = s.effectiveIsAuto() ? "auto" : s.effectiveEnvIdString();
        out.add("  current: " + cur + (effective == null ? " (not resolved now)" : " -> " + effective));
        say(ctx.getSource(), out);
        return HexCliAPI.envs().size();
    }

    private static int envUse(CommandContext<CommandSourceStack> ctx, @org.jetbrains.annotations.Nullable String raw) {
        HexCliSession s = HexCliSession.get(ctx.getSource().getServer());
        ResourceLocation id;
        try {
            id = HexCliEnvs.parseId(raw); // null = auto
            HexCliEnvs.requireKnown(id);
        } catch (IllegalArgumentException e) {
            say(ctx.getSource(), List.of("[HexCLI] env error: " + e.getMessage()));
            return 0;
        }
        s.setEnvOverride(id);
        String resolved;
        try {
            resolved = HexCliEnvs.resolve(id, s.caster()).toString();
        } catch (RuntimeException e) {
            resolved = "error: " + e.getMessage();
        }
        say(ctx.getSource(), List.of("[HexCLI] env = " + s.effectiveEnvIdString() + " (resolved: " + resolved + ")"));
        return 1;
    }

    private static int profileShow(CommandContext<CommandSourceStack> ctx) {
        HexCliSession s = HexCliSession.get(ctx.getSource().getServer());
        List<String> out = new ArrayList<>();
        String env;
        try {
            env = HexCliEnvs.resolve(s.effectiveEnvId(), s.caster()) + (s.effectiveIsAuto() ? " (auto)" : "");
        } catch (RuntimeException e) {
            env = "? (" + e.getMessage() + ")";
        }
        out.add("[HexCLI] active profile '" + s.profileName() + "'" + (s.profile().description == null ? "" : ": " + s.profile().description));
        out.add("  caster: " + HexCliSession.describe(s.caster()) + " at " + HexCliSession.fmt(s.anchor()) + " in " + s.level().dimension().location());
        out.add("  env: " + env + "  enlightened: " + s.enlightened() + "  media: " + s.mediaText() + "  ambit: " + s.ambitText());
        out.add("  stack size: " + s.image().getStack().size() + "  registered envs: auto, "
            + HexCliAPI.envs().keySet().stream().map(ResourceLocation::toString).collect(Collectors.joining(", ")));
        say(ctx.getSource(), out);
        return 1;
    }

    private static int casterSet(CommandContext<CommandSourceStack> ctx, String mode, @org.jetbrains.annotations.Nullable Entity target) {
        HexCliSession s = HexCliSession.get(ctx.getSource().getServer());
        switch (mode) {
            case "player" -> s.setCasterPlayer();
            case "none" -> s.setCasterNone();
            default -> {
                if (!(target instanceof LivingEntity le)) {
                    say(ctx.getSource(), List.of("[HexCLI] caster error: " + HexCliSession.describe(target)
                        + " is not a living entity; casters must be LivingEntity"));
                    return 0;
                }
                s.setCaster(le);
            }
        }
        String env;
        try {
            env = s.resolveEnv().toString() + (s.effectiveIsAuto() ? " (auto)" : "");
        } catch (RuntimeException e) {
            env = "? (" + e.getMessage() + ")";
        }
        say(ctx.getSource(), List.of("[HexCLI] caster = " + HexCliSession.describe(s.caster()) + " at " + HexCliSession.fmt(s.anchor())
            + " (env " + env + ")"));
        return 1;
    }

    private static Entity summonAt(CommandContext<CommandSourceStack> ctx, @org.jetbrains.annotations.Nullable CompoundTag nbt, List<String> tags)
        throws CommandSyntaxException {
        HexCliSession s = HexCliSession.get(ctx.getSource().getServer());
        Holder.Reference<EntityType<?>> type = ResourceArgument.getSummonableEntityType(ctx, "entity");
        return HexCliProfiles.summon(s.level(), type.key().location().toString(), s.anchor(), nbt == null ? null : nbt.toString(), tags);
    }

    private static int casterSummon(CommandContext<CommandSourceStack> ctx, @org.jetbrains.annotations.Nullable CompoundTag nbt)
        throws CommandSyntaxException {
        try {
            Entity e = summonAt(ctx, nbt, List.of(HexCliProfiles.CASTER_TAG));
            if (!(e instanceof LivingEntity)) {
                e.discard();
                say(ctx.getSource(), List.of("[HexCLI] caster error: " + HexCliSession.describe(e) + " is not a living entity"));
                return 0;
            }
            return casterSet(ctx, "select", e);
        } catch (ProfileException e) {
            say(ctx.getSource(), List.of("[HexCLI] summon error: " + e.getMessage()));
            return 0;
        }
    }

    private static int worldSummon(CommandContext<CommandSourceStack> ctx, @org.jetbrains.annotations.Nullable CompoundTag nbt)
        throws CommandSyntaxException {
        try {
            Entity e = summonAt(ctx, nbt, List.of(StringArgumentType.getString(ctx, "tag")));
            say(ctx.getSource(), List.of("[HexCLI] summoned " + HexCliSession.describe(e) + " at " + HexCliSession.fmt(e.position())
                + " tags=" + e.getTags()));
            return 1;
        } catch (ProfileException e) {
            say(ctx.getSource(), List.of("[HexCLI] summon error: " + e.getMessage()));
            return 0;
        }
    }

    private static int media(CommandContext<CommandSourceStack> ctx, String amount) {
        HexCliSession s = HexCliSession.get(ctx.getSource().getServer());
        if (amount.equalsIgnoreCase("infinite")) s.setMedia(null);
        else {
            try {
                long v = Long.parseLong(amount);
                if (v < 0) throw new NumberFormatException();
                s.setMedia(v);
            } catch (NumberFormatException e) {
                say(ctx.getSource(), List.of("[HexCLI] media must be 'infinite' or a non-negative integer (dust = 10000), got '" + amount + "'"));
                return 0;
            }
        }
        say(ctx.getSource(), List.of("[HexCLI] media = " + s.mediaText()));
        return 1;
    }

    /** 解析原版选择器，要求恰好选中一个 LivingEntity。 */
    static LivingEntity selectOne(MinecraftServer server, ServerLevel level, String selector) {
        EntitySelector sel;
        try {
            sel = new EntitySelectorParser(new StringReader(selector)).parse();
        } catch (CommandSyntaxException e) {
            throw new ProfileException("bad selector '" + selector + "': " + e.getMessage());
        }
        CommandSourceStack src = server.createCommandSourceStack().withLevel(level).withPermission(4)
            .withPosition(net.minecraft.world.phys.Vec3.atBottomCenterOf(level.getSharedSpawnPos())).withRotation(Vec2.ZERO);
        List<? extends Entity> found;
        try {
            found = sel.findEntities(src);
        } catch (CommandSyntaxException e) {
            throw new ProfileException("selector '" + selector + "' failed: " + e.getMessage());
        }
        if (found.size() != 1)
            throw new ProfileException("selector '" + selector + "' matched " + found.size() + " entities, need exactly 1");
        if (!(found.get(0) instanceof LivingEntity le))
            throw new ProfileException("selector '" + selector + "' matched " + HexCliSession.describe(found.get(0)) + ", which is not a LivingEntity");
        return le;
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
