package cn.xm1221.MieLib.hexcli;

import com.mojang.authlib.GameProfile;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * HexCLI 需要的加载器相关操作（Architectury {@link ExpectPlatform}）。
 * <p>
 * 实现在各平台模块的 {@code cn.xm1221.MieLib.hexcli.<fabric|forge>.HexCliPlatformImpl}：
 * Fabric 用 Fabric API 的 FakePlayer，Forge 用 Forge 的 FakePlayer。两者都实现 {@link HexCliFakePlayer}。
 */
public final class HexCliPlatform {
    private HexCliPlatform() {
    }

    /** 创建一个不连网络的假玩家（同时实现 {@link HexCliFakePlayer}）。 */
    @ExpectPlatform
    public static ServerPlayer createFakePlayer(ServerLevel level, GameProfile profile) {
        throw new AssertionError();
    }
}
