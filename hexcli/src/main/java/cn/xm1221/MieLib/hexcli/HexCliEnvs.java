package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import at.petrak.hexcasting.api.casting.eval.env.PackagedItemCastEnv;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.common.items.magic.ItemPackagedHex;
import cn.xm1221.MieLib.hexcli.api.CasterKind;
import cn.xm1221.MieLib.hexcli.api.EnvSpec;
import cn.xm1221.MieLib.hexcli.api.HexCliAPI;
import cn.xm1221.MieLib.hexcli.api.HexCliEnvContext;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 施法环境注册表（内部实现）。键是 {@link ResourceLocation}，值是 {@link EnvSpec}；{@link HexCliAPI} 只做公开转发。
 * 工厂只负责“用哪个本体 / 附属环境”，返回的是真正的环境对象（不包装、不继承）；
 * HexCLI 的媒质 / 施法范围由 {@link HexCliComponents} 以扩展组件的形式统一挂上去（附属注册的环境也一样）。
 * <p>
 * 内置三个（静态初始化块，按此顺序）：
 * <ul>
 *   <li>{@code hexcli:staff}：本体 {@link StaffCastEnv}（法杖施法），施法者必须是 ServerPlayer</li>
 *   <li>{@code hexcli:circle}：本体 {@link CircleCastEnv}，无施法者；在锚点自动搭一个最小法术环（见 {@link HexCliCircle}）</li>
 *   <li>{@code hexcli:packaged}：本体 {@link PackagedItemCastEnv}（手里的 cypher/trinket/artifact），施法者必须是 ServerPlayer</li>
 * </ul>
 * {@code auto}：玩家 → hexcli:staff，无施法者 → hexcli:circle，非玩家生物 → 在“注册表中所有声明支持 LIVING 的环境”里挑：
 * 恰好一个就用它；没有 → 报错；不止一个 → 报错让用户显式选（不按加载顺序猜）。
 * <p>
 * 施法者是否匹配环境由 {@link EnvSpec#accepts} 在调用工厂之前统一检查，工厂内部可以假定施法者种类匹配。
 */
public final class HexCliEnvs {
    public static final ResourceLocation AUTO = HexCliAPI.AUTO;
    public static final ResourceLocation STAFF = HexCliAPI.STAFF;
    public static final ResourceLocation CIRCLE = HexCliAPI.CIRCLE;
    public static final ResourceLocation PACKAGED = HexCliAPI.PACKAGED;

    private static final Map<ResourceLocation, EnvSpec> SPECS = new LinkedHashMap<>();

    static {
        registerEnv(STAFF, EnvSpec.builder(ctx -> new StaffCastEnv(requirePlayer(ctx, STAFF), InteractionHand.MAIN_HAND))
            .casters(CasterKind.PLAYER)
            .description("Hex Casting StaffCastEnv (staff casting)")
            .source(HexCliAPI.MOD_ID)
            .build());

        registerEnv(CIRCLE, EnvSpec.builder(HexCliCircle::createEnv)
            .casters(CasterKind.NONE)
            .description("Hex Casting CircleCastEnv on an auto-built impetus circle")
            .source(HexCliAPI.MOD_ID)
            .build());

        registerEnv(PACKAGED, EnvSpec.builder(HexCliEnvs::createPackaged)
            .casters(CasterKind.PLAYER)
            .description("Hex Casting PackagedItemCastEnv (cypher/trinket/artifact in hand)")
            .source(HexCliAPI.MOD_ID)
            .build());
    }

    private HexCliEnvs() {
    }

    /** 内置 hexcli:packaged 工厂：读 envData 里的 hand（main/off，默认 main），校验手里是打包类物品。 */
    static at.petrak.hexcasting.api.casting.eval.CastingEnvironment createPackaged(HexCliEnvContext ctx) {
        JsonObject data = ctx.envData();
        String handStr = data.has("hand") ? data.get("hand").getAsString() : "main";
        InteractionHand hand;
        if ("off".equalsIgnoreCase(handStr)) hand = InteractionHand.OFF_HAND;
        else if ("main".equalsIgnoreCase(handStr)) hand = InteractionHand.MAIN_HAND;
        else throw new IllegalArgumentException("env " + PACKAGED + " envData.hand must be \"main\" or \"off\", got \""
            + handStr + "\"");
        ServerPlayer player = requirePlayer(ctx, PACKAGED);
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof ItemPackagedHex))
            throw new IllegalArgumentException("env " + PACKAGED + " needs a cypher/trinket/artifact in the " + handStr
                + " hand, got " + BuiltInRegistries.ITEM.getKey(stack.getItem())
                + "; set caster.mainhand in the profile");
        return new PackagedItemCastEnv(player, hand);
    }

    /** 工厂内部兜底：正常情况下 {@link EnvSpec#accepts} 已经保证了施法者类型，这里只防御直接调用。 */
    private static ServerPlayer requirePlayer(HexCliEnvContext ctx, ResourceLocation id) {
        if (ctx.caster() instanceof ServerPlayer player) return player;
        throw new IllegalArgumentException("env " + id + " needs a player caster, current caster is "
            + HexCliSession.describe(ctx.caster()) + " (use `hexcli caster player`)");
    }

    /** 注册环境（内部；公开走 {@link HexCliAPI#registerEnv}）。线程安全。 */
    public static synchronized void registerEnv(ResourceLocation id, EnvSpec spec) {
        if (AUTO.equals(id))
            throw new IllegalArgumentException("env id " + id + " is reserved (use \"auto\" to resolve dynamically)");
        EnvSpec prev = SPECS.putIfAbsent(id, spec);
        if (prev != null) {
            String src = prev.source() != null ? prev.source() : id.getNamespace();
            throw new IllegalArgumentException("duplicate HexCLI env id " + id + " (already registered by " + src + ")");
        }
    }

    /** 所有已注册环境，按注册顺序（不可修改的快照；注册后调用方拿到的旧快照不会变）。 */
    public static synchronized Map<ResourceLocation, EnvSpec> envs() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(SPECS));
    }

    public static synchronized @Nullable EnvSpec env(ResourceLocation id) {
        return SPECS.get(id);
    }

    // ---- 解析 / 校验 ----

    /** 字符串 -> ResourceLocation：空 / "auto" -> null（表示自动）；无命名空间的裸路径用 hexcli 命名空间。 */
    public static @Nullable ResourceLocation parseId(@Nullable String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        if (AUTO.toString().equals(s) || "auto".equals(s)) return null;
        ResourceLocation rl = ResourceLocation.tryParse(s);
        if (rl == null) throw new IllegalArgumentException("invalid HexCLI env id '" + s + "'");
        // 裸路径（"staff"）默认 hexcli 命名空间，而不是 ResourceLocation 的 minecraft
        if (!s.contains(":")) rl = new ResourceLocation(HexCliAPI.MOD_ID, s);
        return rl;
    }

    /** 列出所有已知 id（含 auto），用于报错提示。 */
    static synchronized String knownList() {
        List<String> names = SPECS.keySet().stream().map(ResourceLocation::toString).collect(Collectors.toList());
        names.add(0, "auto");
        return String.join(", ", names);
    }

    /**
     * 解析出最终要用的环境 id（不含 auto）。
     * 显式 id 不存在 → 报错；auto → 按施法者类型选（PLAYER→staff，NONE→circle，LIVING→在声明支持 LIVING 的环境里挑）。
     */
    public static synchronized ResourceLocation resolve(@Nullable ResourceLocation id, @Nullable LivingEntity caster) {
        if (id == null) {
            CasterKind kind = CasterKind.of(caster);
            if (kind == CasterKind.NONE) return CIRCLE;
            if (kind == CasterKind.PLAYER) return STAFF;
            List<ResourceLocation> living = SPECS.entrySet().stream()
                .filter(e -> e.getValue().casters().contains(CasterKind.LIVING))
                .map(Map.Entry::getKey).collect(Collectors.toList());
            if (living.isEmpty())
                throw new IllegalArgumentException(noEnvForLiving(caster));
            if (living.size() == 1) return living.get(0);
            throw new IllegalArgumentException("several HexCLI envs accept non-player casters: "
                + living.stream().map(ResourceLocation::toString).collect(Collectors.joining(", "))
                + "; pick one with \"env\" in the profile or `hexcli env use <id>`");
        }
        if (!SPECS.containsKey(id))
            throw new IllegalArgumentException("unknown HexCLI env '" + id + "', known: " + knownList());
        return id;
    }

    static String noEnvForLiving(LivingEntity caster) {
        return "no HexCLI env accepts non-player caster " + HexCliSession.describe(caster)
            + "; an entity casting env is planned in MieLib itself; addons can register one with "
            + "HexCliAPI.registerEnv(id, EnvSpec...casters(CasterKind.LIVING))"
            + " and select it with \"env\": \"<id>\" or `hexcli env use <id>`";
    }

    /**
     * 校验环境 id 是否已知（解析表里的显式 id 时提前报错）。
     * 传入 null（auto）直接通过；裸路径 / 带命名空间都按注册表查。
     */
    public static synchronized void requireKnown(@Nullable ResourceLocation id) {
        if (id != null && !SPECS.containsKey(id))
            throw new IllegalArgumentException("unknown HexCLI env '" + id + "', known: " + knownList());
    }

    /** 在 HexCliSession.createEnv 里真正创建环境：先校验施法者匹配，再调工厂（兜空 / 兜异常）。 */
    static at.petrak.hexcasting.api.casting.eval.CastingEnvironment create(ResourceLocation id, HexCliEnvContext ctx) {
        EnvSpec spec = env(id); // 取快照后不再持锁：工厂是外部代码，可能很慢 / 反过来注册环境
        if (spec == null) throw new IllegalArgumentException("unknown HexCLI env '" + id + "', known: " + knownList());
        if (!spec.accepts(ctx.caster())) {
            throw new IllegalArgumentException("env " + id + " requires caster " + spec.castersText()
                + ", current caster is " + HexCliSession.describe(ctx.caster()) + " (" + CasterKind.of(ctx.caster()) + ")"
                + casterHint());
        }
        try {
            at.petrak.hexcasting.api.casting.eval.CastingEnvironment env = spec.factory().create(ctx);
            if (env == null) throw new IllegalArgumentException("env " + id + " factory returned null");
            return env;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            HexCli.LOGGER.error("[HexCLI] env {} factory crashed", id, e);
            throw new IllegalArgumentException("env " + id + " factory crashed: " + e);
        }
    }

    private static String casterHint() {
        return "; try `hexcli caster player`, `hexcli caster none`, or `hexcli env use auto`";
    }
}
