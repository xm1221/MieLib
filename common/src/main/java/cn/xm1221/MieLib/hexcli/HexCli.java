package cn.xm1221.MieLib.hexcli;

import cn.xm1221.MieLib.hexcli.HexCliProfiles.ProfileException;
import cn.xm1221.MieLib.hexcli.api.HexCliAPI;
import cn.xm1221.MieLib.hexcli.remote.HexCliOps;
import cn.xm1221.MieLib.hexcli.remote.HexCliRemote;
import cn.xm1221.MieLib.hexcli.remote.HexCliReply;
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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
            MishapPreload.preload();
            HexCliProfiles.writeExamples(server);
            HexCliSession session = HexCliSession.get(server);
            try {
                for (String l : session.applyProfile("default")) LOGGER.info(l);
            } catch (ProfileException e) {
                LOGGER.error("[HexCLI] could not apply default profile: {}", e.getMessage());
            }
            HexCliRemote.start(server);
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            HexCliRemote.stop();
            HexCliOps.clearWaiters();
            CastObserver.shutdown();
            HexCliSession.drop();
        });
        TickEvent.SERVER_POST.register(HexCliOps::tickWaiters);
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

    private static int reply(CommandContext<CommandSourceStack> ctx, HexCliReply reply) {
        say(ctx.getSource(), reply.lines());
        return reply.ok() ? 1 : 0;
    }

    private static int run(CommandContext<CommandSourceStack> ctx, String code) {
        return reply(ctx, HexCliOps.run(ctx.getSource().getServer(), code));
    }

    private static int stack(CommandContext<CommandSourceStack> ctx) {
        return reply(ctx, HexCliOps.stack(ctx.getSource().getServer()));
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        return reply(ctx, HexCliOps.reset(ctx.getSource().getServer()));
    }

    // ---- profile / caster / world / media ----

    private static int profileList(CommandContext<CommandSourceStack> ctx) {
        return reply(ctx, HexCliOps.profileList(ctx.getSource().getServer()));
    }

    private static int profileShow(CommandContext<CommandSourceStack> ctx) {
        return reply(ctx, HexCliOps.profileShow(ctx.getSource().getServer()));
    }

    private static int profileUse(CommandContext<CommandSourceStack> ctx, String name) {
        return reply(ctx, HexCliOps.profileUse(ctx.getSource().getServer(), name));
    }

    private static int envList(CommandContext<CommandSourceStack> ctx) {
        return reply(ctx, HexCliOps.envList(ctx.getSource().getServer()));
    }

    private static int envUse(CommandContext<CommandSourceStack> ctx, @org.jetbrains.annotations.Nullable String raw) {
        return reply(ctx, HexCliOps.envUse(ctx.getSource().getServer(), raw));
    }

    private static int media(CommandContext<CommandSourceStack> ctx, String amount) {
        return reply(ctx, HexCliOps.media(ctx.getSource().getServer(), amount));
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

    /** 解析原版选择器，要求恰好选中一个 LivingEntity。 */
    static LivingEntity selectOne(MinecraftServer server, ServerLevel level, String selector) {
        EntitySelector sel;
        try {
            sel = new EntitySelectorParser(new StringReader(selector), true).parse();
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

    private static int waitFor(CommandContext<CommandSourceStack> ctx, int timeout, int quiet) {
        CommandSourceStack src = ctx.getSource();
        HexCliReply started = HexCliOps.wait(src.getServer(), timeout, quiet,
            done -> say(src, done.lines()));
        say(src, started.lines());
        return 1;
    }
}
