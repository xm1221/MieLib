package cn.xm1221.MieLib.hexcli.neoforge;

import cn.xm1221.MieLib.hexcli.HexCliFakePlayer;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * NeoForge 侧的 HexCLI 假玩家。HexCLI 本身在 1.21 分支还没有编进模组
 * （见 HEXCLI.md），这个类留给后续把 HexCLI 接回来时用。
 */
public final class NeoForgeHexCliPlayer extends FakePlayer implements HexCliFakePlayer {
    private final List<String> messages = new ArrayList<>();

    public NeoForgeHexCliPlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

    @Override
    public void sendSystemMessage(Component component) {
        messages.add(component.getString());
    }

    @Override
    public List<String> drainMessages() {
        List<String> out = new ArrayList<>(messages);
        messages.clear();
        return out;
    }
}
