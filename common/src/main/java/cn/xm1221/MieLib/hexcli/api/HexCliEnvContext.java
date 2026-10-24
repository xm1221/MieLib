package cn.xm1221.MieLib.hexcli.api;

import cn.xm1221.MieLib.hexcli.HexCliProfile;
import cn.xm1221.MieLib.hexcli.HexCliSession;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * 创建施法环境时交给 {@link HexCliEnvFactory} 的上下文。
 * <p>
 * 这是公开的只读视图：工厂据此构造本体的环境对象（{@code StaffCastEnv} / {@code CircleCastEnv} / 附属自己的环境），
 * HexCLI 不包装、不继承任何环境。
 *
 * @param level   施法所在维度
 * @param caster  当前施法者（假玩家 / 任意生物 / 选中的玩家），无施法者时为 null
 * @param anchor  施法者位置；无施法者时是 profile 里的 pos
 * @param envId   本次解析出来的环境 id（如 {@code mielib:staff}，不会是 {@code auto}）
 * @param envData profile 里 {@code envData} 字段的深拷贝，永远非 null（profile 没配则是空对象）；工厂自行读取业务字段
 * @param session 会话：媒质预算、施法范围、启迪、颜料等运行时设置都从这里取（内部 API）
 * @param profile 当前 profile（原始 JSON 映射，内部 API）
 */
public record HexCliEnvContext(
    ServerLevel level,
    @Nullable LivingEntity caster,
    Vec3 anchor,
    ResourceLocation envId,
    JsonObject envData,
    @ApiStatus.Internal HexCliSession session,
    @ApiStatus.Internal HexCliProfile profile
) {
    public HexCliEnvContext {
        // 防御性：即使调用方传了 null / 共享对象，工厂拿到的也是非 null 的独立副本。
        envData = envData == null ? new JsonObject() : envData.deepCopy();
    }
}
