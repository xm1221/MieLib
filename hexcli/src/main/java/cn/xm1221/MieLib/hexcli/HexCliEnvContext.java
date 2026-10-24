package cn.xm1221.MieLib.hexcli;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 创建施法环境时交给 {@link HexCliEnvFactory} 的上下文。
 *
 * @param level   施法所在维度
 * @param caster  当前施法者（假玩家 / 任意生物），无施法者时为 null
 * @param anchor  施法者位置；无施法者时是 profile 里的 pos
 * @param profile 当前 profile（原始 JSON 映射）
 * @param session 会话：媒质预算、施法范围、启迪、颜料等运行时设置都从这里取
 */
public record HexCliEnvContext(ServerLevel level, @Nullable LivingEntity caster, Vec3 anchor,
                               HexCliProfile profile, HexCliSession session) {
}
