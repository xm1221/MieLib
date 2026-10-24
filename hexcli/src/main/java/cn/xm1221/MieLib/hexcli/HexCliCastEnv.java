package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * HexCLI 的测试施法环境（里程碑 1）：法杖施法 + 无限媒质 + 不限施法范围。
 * <p>
 * 媒质消耗由 {@link CastObserver.Probe} 的 ExtractMedia.Pre 统一记账（所有环境同一套口径），
 * 这里只负责“不扣”：返回 0 表示费用已付清。后续里程碑改成由 profile 配置。
 */
public class HexCliCastEnv extends StaffCastEnv {
    public HexCliCastEnv(ServerPlayer caster) {
        super(caster, InteractionHand.MAIN_HAND);
    }

    @Override
    public long extractMediaEnvironment(long cost, boolean simulate) {
        return 0;
    }

    @Override
    public boolean isVecInRangeEnvironment(Vec3 vec) {
        return true;
    }

    @Override
    public boolean hasEditPermissionsAtEnvironment(BlockPos pos) {
        return true;
    }

    /** 事故由 CastObserver 从 CastResult 副作用里统一收集，不再发给假玩家的聊天栏。 */
    @Override
    protected void sendMishapMsgToPlayer(OperatorSideEffect.DoMishap mishap) {
    }

    /** 法术里的输出类图案（如 Reveal）走这里：直接打到控制台。 */
    @Override
    public void printMessage(Component message) {
        HexCli.LOGGER.info("[HexCLI] print: {}", message.getString());
        HexCliSession s = HexCliSession.current();
        if (s != null) s.printed.add(message.getString());
    }
}
