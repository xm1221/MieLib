package cn.xm1221.MieLib.mixin.hexcli;

import cn.xm1221.MieLib.hexcli.HexCli;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 控制台快捷输入：以 {@code >} 开头的一行直接当作 {@code hexcli run <其余部分>}。
 * 例：{@code > num_1 num_2 add}。只改写控制台输入，不影响玩家聊天和其它命令。
 * <p>
 * 只在 HexCLI 真正启用时改写（{@link HexCli#isActive()}）；默认关闭时原样返回。
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerConsoleMixin {
    @ModifyVariable(method = "handleConsoleInput(Ljava/lang/String;Lnet/minecraft/commands/CommandSourceStack;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String mielib$rewriteRawLine(String msg) {
        if (!HexCli.isActive()) return msg;
        String trimmed = msg.stripLeading();
        if (trimmed.startsWith(">")) {
            return "hexcli run " + trimmed.substring(1).strip();
        }
        return msg;
    }
}
