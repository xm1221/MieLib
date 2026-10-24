package cn.xm1221.MieLib.hexcli;

import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 施法环境注册表（里程碑 3 的雏形）。内置两个：
 * <ul>
 *   <li>{@code hexcli:staff}：{@link HexCliCastEnv}，法杖施法，施法者必须是 ServerPlayer</li>
 *   <li>{@code hexcli:entity}：{@link HexCliEntityCastEnv}，任意生物或无施法者</li>
 * </ul>
 * {@code auto}：施法者是玩家用 staff，否则用 entity。
 */
public final class HexCliEnvs {
    public static final String AUTO = "auto";
    public static final String STAFF = "hexcli:staff";
    public static final String ENTITY = "hexcli:entity";

    private static final Map<String, HexCliEnvFactory> FACTORIES = new LinkedHashMap<>();

    static {
        register(STAFF, ctx -> {
            if (!(ctx.caster() instanceof ServerPlayer player))
                throw new IllegalArgumentException("env " + STAFF + " needs a player caster, current caster is "
                    + HexCliSession.describe(ctx.caster()) + " (use `hexcli caster player` or env auto/" + ENTITY + ")");
            return new HexCliCastEnv(player, ctx.session());
        });
        register(ENTITY, ctx -> new HexCliEntityCastEnv(ctx.level(), ctx.caster(), ctx.anchor(), ctx.session()));
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
        if (id == null || id.isBlank() || AUTO.equals(id)) return ctx.caster() instanceof ServerPlayer ? STAFF : ENTITY;
        return id;
    }

    public static HexCliEnvFactory get(String id) {
        HexCliEnvFactory f = FACTORIES.get(id);
        if (f == null) throw new IllegalArgumentException("unknown HexCLI env '" + id + "', known: auto, " + String.join(", ", FACTORIES.keySet()));
        return f;
    }
}
