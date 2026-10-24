package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 施法环境注册表（里程碑 3 的雏形）。工厂只负责“用哪个本体 / 附属环境”，返回的是真正的环境对象，
 * 不包装、不继承；HexCLI 的媒质 / 施法范围由 {@link HexCliComponents} 以扩展组件的形式统一挂上去（附属注册的环境也一样）。
 * 内置两个：
 * <ul>
 *   <li>{@code hexcli:staff}：本体 {@link StaffCastEnv}（法杖施法），施法者必须是 ServerPlayer</li>
 *   <li>{@code hexcli:circle}：本体 {@link at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv}，无施法者；
 *       在锚点自动搭一个最小法术环（见 {@link HexCliCircle}）</li>
 * </ul>
 * {@code auto}：玩家 → staff，无施法者 → circle，其它生物 → 报错（生物施法环境以后放在咩库本体里，不在 CLI）。
 */
public final class HexCliEnvs {
    public static final String AUTO = "auto";
    public static final String STAFF = "hexcli:staff";
    public static final String CIRCLE = "hexcli:circle";

    private static final Map<String, HexCliEnvFactory> FACTORIES = new LinkedHashMap<>();

    static {
        register(STAFF, ctx -> {
            if (!(ctx.caster() instanceof ServerPlayer player))
                throw new IllegalArgumentException("env " + STAFF + " needs a player caster, current caster is "
                    + HexCliSession.describe(ctx.caster()) + " (use `hexcli caster player`)");
            return new StaffCastEnv(player, InteractionHand.MAIN_HAND);
        });
        register(CIRCLE, ctx -> {
            if (ctx.caster() != null)
                throw new IllegalArgumentException("env " + CIRCLE + " is for caster none, current caster is "
                    + HexCliSession.describe(ctx.caster()) + " (use `hexcli caster none`)");
            return HexCliCircle.createEnv(ctx);
        });
    }

    private HexCliEnvs() {
    }

    public static synchronized void register(String id, HexCliEnvFactory factory) {
        if (AUTO.equals(id)) throw new IllegalArgumentException("'auto' is reserved");
        if (FACTORIES.putIfAbsent(id, factory) != null) throw new IllegalArgumentException("duplicate HexCLI env id " + id);
    }

    public static Map<String, HexCliEnvFactory> all() {
        return Collections.unmodifiableMap(FACTORIES);
    }

    /** auto 按施法者解析成具体 id。 */
    public static String resolve(String id, HexCliEnvContext ctx) {
        if (id != null && !id.isBlank() && !AUTO.equals(id)) return id;
        if (ctx.caster() == null) return CIRCLE;
        if (ctx.caster() instanceof ServerPlayer) return STAFF;
        throw new IllegalArgumentException(noEnvFor(ctx.caster()));
    }

    static String noEnvFor(net.minecraft.world.entity.LivingEntity caster) {
        return "no built-in HexCLI env for non-player caster " + HexCliSession.describe(caster)
            + "; an entity casting env is planned in MieLib itself, until then register one via HexCliEnvs.register(id, factory)"
            + " and set \"env\": \"<id>\" in the profile";
    }

    public static HexCliEnvFactory get(String id) {
        HexCliEnvFactory f = FACTORIES.get(id);
        if (f == null) throw new IllegalArgumentException("unknown HexCLI env '" + id + "', known: auto, " + String.join(", ", FACTORIES.keySet()));
        return f;
    }
}
