package cn.xm1221.MieLib.hexcli.forge;

import cn.xm1221.MieLib.hexcli.HexCliPlatform;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** {@link HexCliPlatform} 的 Forge 实现（Architectury {@code ExpectPlatform}）。 */
public final class HexCliPlatformImpl {
    private HexCliPlatformImpl() {
    }

    public static ServerPlayer createFakePlayer(ServerLevel level, GameProfile profile) {
        return new ForgeHexCliPlayer(level, profile);
    }
}
