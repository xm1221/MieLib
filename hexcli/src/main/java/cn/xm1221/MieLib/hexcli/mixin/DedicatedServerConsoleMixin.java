package cn.xm1221.MieLib.hexcli.mixin;

import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 控制台快捷输入：以 {@code >} 开头的一行直接当作 {@code hexcli run <其余部分>}。
 * 例：{@code > 1 2 add}。只改写控制台输入，不影响玩家聊天和其它命令。
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerConsoleMixin {
    @ModifyVariable(method = "handleConsoleInput", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String hexcli$rewriteRawLine(String msg) {
        String trimmed = msg.stripLeading();
        if (trimmed.startsWith(">")) {
            return "hexcli run " + trimmed.substring(1).strip();
        }
        return msg;
    }
}
