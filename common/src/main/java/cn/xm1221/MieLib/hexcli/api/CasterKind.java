package cn.xm1221.MieLib.hexcli.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 施法者类型（按本体 {@code CastingEnvironment} 能拿到的施法者分）。
 * <ul>
 *   <li>{@link #PLAYER}：施法者是 {@link ServerPlayer}（含 HexCLI 的假玩家 / 选中的真实玩家）</li>
 *   <li>{@link #LIVING}：非玩家的 {@link LivingEntity}（僵尸、羊……）</li>
 *   <li>{@link #NONE}：无施法者（{@code null}）</li>
 * </ul>
 * 注册环境时用 {@link EnvSpec.Builder#casters(CasterKind...)} 声明该环境支持哪些种类；
 * 自动解析（auto）与“施法者是否匹配环境”都基于它判断。
 */
public enum CasterKind {
    PLAYER,
    LIVING,
    NONE;

    /**
     * 由施法者实体推断类型；null → {@link #NONE}，{@link ServerPlayer} → {@link #PLAYER}，其余 {@link LivingEntity} → {@link #LIVING}。
     */
    public static CasterKind of(@Nullable LivingEntity e) {
        if (e == null) return NONE;
        if (e instanceof ServerPlayer) return PLAYER;
        return LIVING;
    }
}
