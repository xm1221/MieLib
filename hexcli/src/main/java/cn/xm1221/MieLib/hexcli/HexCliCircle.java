package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.block.circle.BlockAbstractImpetus;
import at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus;
import at.petrak.hexcasting.api.casting.circles.CircleExecutionState;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import at.petrak.hexcasting.api.misc.Result;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.common.blocks.circles.BlockSlate;
import at.petrak.hexcasting.common.lib.HexBlocks;
import cn.xm1221.MieLib.hexcli.api.HexCliEnvContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import org.jetbrains.annotations.Nullable;

/**
 * {@code hexcli:circle}：无施法者时用本体的 {@link CircleCastEnv}。
 * <p>
 * 在锚点处搭一个最小的真实法术环（朝东的红石促动石 + 3 块平放的石板围成 2×2 的环），
 * 用本体的 {@link CircleExecutionState#createNew} 生成执行状态，再按本体的方式构造 CircleCastEnv。
 * 环只搭一次，之后复用（被破坏了就重搭）。法术环不会被“启动”，HexCLI 直接拿这个环境跑命令里的代码。
 */
public final class HexCliCircle {
    private HexCliCircle() {
    }

    public static CastingEnvironment createEnv(HexCliEnvContext ctx) {
        ServerLevel level = ctx.level();
        BlockPos origin = BlockPos.containing(ctx.anchor());
        BlockEntityAbstractImpetus impetus = ensureCircle(level, origin);
        CircleExecutionState state = stateOf(impetus);
        if (state == null) {
            // 可能被改坏了：重搭一次
            build(level, origin);
            impetus = impetusAt(level, origin);
            state = impetus == null ? null : stateOf(impetus);
            if (state == null)
                throw new IllegalArgumentException("could not build the HexCLI test circle at " + origin.toShortString()
                    + " in " + level.dimension().location());
        }
        FrozenPigment pig = ctx.session().profilePigment(); // session() 是 @ApiStatus.Internal，HexCLI 自己的代码可以用
        if (pig != null) impetus.setPigment(pig);
        impetus.clearDisplay();
        return new CircleCastEnv(level, state);
    }

    private static @Nullable CircleExecutionState stateOf(BlockEntityAbstractImpetus impetus) {
        Result<CircleExecutionState, BlockPos> r = CircleExecutionState.createNew(impetus, null);
        return r instanceof Result.Ok<CircleExecutionState, BlockPos> ok ? ok.ok : null;
    }

    private static BlockEntityAbstractImpetus ensureCircle(ServerLevel level, BlockPos origin) {
        BlockEntityAbstractImpetus imp = impetusAt(level, origin);
        if (imp != null) return imp;
        build(level, origin);
        imp = impetusAt(level, origin);
        if (imp == null)
            throw new IllegalArgumentException("could not place the HexCLI test impetus at " + origin.toShortString());
        return imp;
    }

    private static @Nullable BlockEntityAbstractImpetus impetusAt(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof BlockEntityAbstractImpetus i ? i : null;
    }

    /** 促动石朝东；石板平放在地上（法线朝上），东 → 东南 → 南 → 回到促动石（从南往北进入）。 */
    private static void build(ServerLevel level, BlockPos origin) {
        int flags = 2 | 16;
        level.setBlock(origin, HexBlocks.IMPETUS_REDSTONE.defaultBlockState()
            .setValue(BlockAbstractImpetus.FACING, Direction.EAST), flags);
        BlockState slate = HexBlocks.SLATE.defaultBlockState().setValue(BlockSlate.ATTACH_FACE, AttachFace.FLOOR);
        level.setBlock(origin.east(), slate, flags);
        level.setBlock(origin.east().south(), slate, flags);
        level.setBlock(origin.south(), slate, flags);
    }

    /** 取出（并清空）促动石上显示的文字：法术环环境里 print 和事故都显示在这。 */
    public static @Nullable String takeDisplay(CastingEnvironment env) {
        if (!(env instanceof CircleCastEnv c)) return null;
        BlockEntityAbstractImpetus imp = c.getImpetus();
        if (imp == null) return null;
        Component msg = imp.getDisplayMsg();
        imp.clearDisplay();
        return msg == null ? null : msg.getString();
    }
}
