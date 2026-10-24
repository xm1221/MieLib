package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * HexCLI 的玩家施法环境（{@code hexcli:staff}）：本体法杖施法，媒质 / 施法范围按 profile。
 * <ul>
 *   <li>media：infinite = 不扣；数字 = 会话预算；inventory = 本体行为（真实物品、透支扣血）</li>
 *   <li>ambit：infinite = 不限；default = 本体规则（含哨卫）；数字 = 以施法者为圆心的半径</li>
 *   <li>启迪：HexCLI 假玩家直接用 profile 的 enlightened（Fabric FakePlayer 不记录进度）；
 *       选中的真实玩家走本体逻辑（hexcasting:enlightenment 进度，profile 应用时授予 / 撤销）</li>
 * </ul>
 * 媒质消耗由 {@link CastObserver.Probe} 统一记账，事故也由它收集。
 */
public class HexCliCastEnv extends StaffCastEnv {
    protected final HexCliSession session;

    public HexCliCastEnv(ServerPlayer caster, HexCliSession session) {
        super(caster, InteractionHand.MAIN_HAND);
        this.session = session;
    }

    @Override
    public long extractMediaEnvironment(long cost, boolean simulate) {
        if (session.mediaMode() == HexCliProfile.Mode.INVENTORY) return super.extractMediaEnvironment(cost, simulate);
        return session.takeMedia(cost, simulate);
    }

    @Override
    public boolean isVecInRangeEnvironment(Vec3 vec) {
        Boolean r = session.ambitCheck(caster.position(), vec);
        return r != null ? r : super.isVecInRangeEnvironment(vec);
    }

    @Override
    public boolean isEnlightened() {
        return caster instanceof HexCliPlayer ? session.enlightened() : super.isEnlightened();
    }

    @Override
    public boolean hasEditPermissionsAtEnvironment(BlockPos pos) {
        return true;
    }

    @Override
    protected void sendMishapMsgToPlayer(OperatorSideEffect.DoMishap mishap) {
    }

    @Override
    public void printMessage(Component message) {
        session.print(message);
    }
}
