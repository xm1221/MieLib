package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.common.lib.HexItems;
import com.mojang.authlib.GameProfile;
import io.yukkuric.hexparse.parsers.ParserMain;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一个服务器一个会话：假玩家 + 跨命令保留的 {@link CastingImage}（栈、渡鸦之思、括号状态）。
 * 每次 run 新建一个 {@link HexCliCastEnv} + {@link CastingVM}（和本体法杖施法一样），只把 image 留下。
 */
public final class HexCliSession {
    public static final GameProfile PROFILE =
        new GameProfile(UUID.fromString("4e3c11ac-4ec1-4ec1-8e11-000000000001"), "HexCLI");

    private static @Nullable HexCliSession current;

    public final MinecraftServer server;
    public final ServerLevel level;
    public final HexCliPlayer player;
    private CastingImage image = new CastingImage();
    /** 本次 run 里法术打印出的文字。 */
    final List<String> printed = new ArrayList<>();

    private HexCliSession(MinecraftServer server) {
        this.server = server;
        this.level = server.overworld();
        this.player = new HexCliPlayer(level, PROFILE);
        BlockPos spawn = level.getSharedSpawnPos();
        player.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);
        player.setGameMode(GameType.SURVIVAL);
        player.setInvulnerable(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(HexItems.STAFF_OAK));
    }

    public static HexCliSession get(MinecraftServer server) {
        if (current == null || current.server != server) current = new HexCliSession(server);
        return current;
    }

    public static @Nullable HexCliSession current() {
        return current;
    }

    static void drop() {
        current = null;
    }

    public CastingImage image() {
        return image;
    }

    public void reset() {
        image = new CastingImage();
    }

    /** hexParse 文本 -> iota 列表。解析失败抛异常，由命令层兜住。 */
    public List<Iota> parse(String code) {
        CompoundTag tag = ParserMain.ParseCode(code, player);
        Iota iota = IotaType.deserialize(tag, level);
        List<Iota> out = new ArrayList<>();
        if (iota instanceof ListIota list) {
            for (Iota i : list.getList()) out.add(i);
        } else {
            out.add(iota);
        }
        return out;
    }

    /** 执行一段 iota，返回这次 run 对应的 root 事件（由 {@link CastObserver} 记录）。 */
    public @Nullable CastEvent run(List<Iota> iotas) {
        printed.clear();
        CastEvent[] root = new CastEvent[1];
        Runnable unsubscribe = CastObserver.subscribe(ev -> {
            if (ev.root && root[0] == null) root[0] = ev;
        });
        try {
            CastingVM vm = new CastingVM(image, new HexCliCastEnv(player));
            vm.queueExecuteAndWrapIotas(iotas, level);
            // 和本体法杖一样：保留栈，清零已用操作数，避免跨命令累积撞上 op 上限
            image = vm.getImage().withOverriddenUsedOps(0);
        } finally {
            unsubscribe.run();
        }
        return root[0];
    }
}
