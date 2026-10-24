package cn.xm1221.MieLib.hexcli.api;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import org.jetbrains.annotations.NotNull;

/**
 * 施法环境工厂：挂到某个环境 id 上，每次 run 由 HexCLI 调用，为这次施法创建真正的环境对象。
 * <p>
 * 注意：工厂本身不负责“施法者是否符合要求”——这一检查在调用工厂之前由
 * {@link EnvSpec#accepts} 统一完成（{@code HexCliEnvs} 在解析后、调用前校验）。所以工厂可以假定
 * {@code ctx.caster()} 满足 {@link EnvSpec} 声明的 {@link CasterKind}（用 {@code instanceof} 模式匹配即可）。
 *
 * @throws IllegalArgumentException 上下文不满足要求（比如需要玩家却不是玩家），消息会原样打印到控制台
 */
@FunctionalInterface
public interface HexCliEnvFactory {
    /**
     * 为这次施法创建环境。
     *
     * @param ctx 只读上下文（维度 / 施法者 / 锚点 / 环境 id / envData / 会话）
     * @return 真正的环境对象（本体或附属的原对象，不要包装）；返回 null 会被 HexCLI 视为错误
     * @throws IllegalArgumentException 上下文不满足要求时，消息原样打印
     * @throws RuntimeException         其它异常会被 HexCLI 捕获并包装成 {@code env <id> factory crashed: ...}
     */
    @NotNull
    CastingEnvironment create(HexCliEnvContext ctx);
}
