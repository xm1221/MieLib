package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * HexCLI 对环境做的全部改动：只通过本体的扩展组件（{@link CastingEnvironment#addExtension}）挂两个组件，
 * 环境本身永远是本体 / 附属的原对象（类型、instanceof 判断、附属自己挂的扩展都不受影响）。
 * <ul>
 *   <li>{@link Media}（ExtractMedia.Pre）：infinite = 全部付清（返回 0）；数字 = 从会话预算里扣，剩下的交给环境自己付
 *       （玩家 = 背包里的媒质物品，法术环 = 促动石里的媒质）；inventory = 原样返回，完全由环境自己付。</li>
 *   <li>{@link Ambit}（IsVecInRange）：infinite = true；default = 环境自己的判断；数字 = 以施法者（无施法者时为锚点）为圆心的半径。</li>
 * </ul>
 * 只挂在 HexCLI 注册表创建的 root 环境上（{@link HexCliSession#createEnv()}）；咒灵、法术环、延时施法等派生出来的环境
 * 不是 HexCLI 创建的，只会被 {@link CastObserver} 观察，媒质 / 范围保持它们自己的规则。
 */
public final class HexCliComponents {
    private HexCliComponents() {
    }

    public static final CastingEnvironmentComponent.Key<Media> MEDIA_KEY = new CastingEnvironmentComponent.Key<>() {
    };
    public static final CastingEnvironmentComponent.Key<Ambit> AMBIT_KEY = new CastingEnvironmentComponent.Key<>() {
    };

    /** 给环境挂上媒质 / 范围组件（已挂过则跳过）。 */
    public static void attach(CastingEnvironment env, HexCliSession session, Vec3 anchor) {
        if (env.getExtension(MEDIA_KEY) == null) env.addExtension(new Media(session));
        if (env.getExtension(AMBIT_KEY) == null) env.addExtension(new Ambit(env, session, anchor));
    }

    public record Media(HexCliSession session) implements CastingEnvironmentComponent.ExtractMedia.Pre {
        @Override
        public Key<?> getKey() {
            return MEDIA_KEY;
        }

        @Override
        public long onExtractMedia(long cost, boolean simulate) {
            return switch (session.mediaMode()) {
                case INFINITE -> 0;
                case NUMBER -> session.takeMedia(cost, simulate);
                default -> cost; // inventory：环境自己付
            };
        }
    }

    public record Ambit(CastingEnvironment env, HexCliSession session, Vec3 anchor)
        implements CastingEnvironmentComponent.IsVecInRange {
        @Override
        public Key<?> getKey() {
            return AMBIT_KEY;
        }

        @Override
        public boolean onIsVecInRange(Vec3 vec, boolean current) {
            LivingEntity e = env.getCastingEntity();
            Boolean r = session.ambitCheck(e != null ? e.position() : anchor, vec);
            return r != null ? r : current;
        }
    }
}
