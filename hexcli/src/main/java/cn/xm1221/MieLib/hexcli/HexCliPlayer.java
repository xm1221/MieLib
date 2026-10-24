package cn.xm1221.MieLib.hexcli;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * HexCLI 的假玩家。和 Fabric 的 FakePlayer 一样不连网络，
 * 但会把发给它的系统消息（hexParse 的“未知符号”等提示、法术的 chat 输出）收集起来，打印到控制台。
 */
public final class HexCliPlayer extends FakePlayer {
    final List<String> messages = new ArrayList<>();

    HexCliPlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

    @Override
    public void sendSystemMessage(Component component) {
        sendSystemMessage(component, false);
    }

    @Override
    public void sendSystemMessage(Component component, boolean overlay) {
        if (!overlay) messages.add(component.getString());
    }

    /** 取出并清空已收集的消息。 */
    List<String> drainMessages() {
        List<String> out = new ArrayList<>(messages);
        messages.clear();
        return out;
    }
}
