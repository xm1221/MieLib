package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;

/**
 * 施法环境工厂。里程碑 3 的注册 API 会把它公开给附属：附属注册自己的环境 id，profile 里用 {@code "env": "<id>"} 选用。
 */
@FunctionalInterface
public interface HexCliEnvFactory {
    /**
     * 为这次施法创建环境。
     *
     * @throws IllegalArgumentException 上下文不满足要求（比如需要玩家但施法者不是玩家），消息会原样打印到控制台
     */
    CastingEnvironment create(HexCliEnvContext ctx);
}
