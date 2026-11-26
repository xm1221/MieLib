package cn.xm1221.MieLib.mixin;

import at.petrak.hexcasting.api.casting.eval.ExecutionClientView;
import at.petrak.hexcasting.common.msgs.MsgNewSpellPatternS2C;
import cn.xm1221.MieLib.registry.SpellcastingClientEvents;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 统一分派 {@link MsgNewSpellPatternS2C} 的 PRE / POST 客户端事件，避免附属重复 mixin {@code handle}。
 */
@Mixin(value = MsgNewSpellPatternS2C.class, remap = false)
public abstract class MsgNewSpellPatternS2CMixin {

    @Inject(method = "handle", at = @At("HEAD"), remap = false)
    private static void mielib$beforeHandle(MsgNewSpellPatternS2C self, CallbackInfo ci) {
        if (!SpellcastingClientEvents.hasServerUpdatePre()) return;
        ExecutionClientView info = self.info();
        int index = self.index();
        Minecraft.getInstance().execute(() ->
                SpellcastingClientEvents.fireServerUpdatePre(info, index, Minecraft.getInstance().screen)
        );
    }

    @Inject(method = "handle", at = @At("TAIL"), remap = false)
    private static void mielib$afterHandle(MsgNewSpellPatternS2C self, CallbackInfo ci) {
        if (!SpellcastingClientEvents.hasServerUpdatePost()) return;
        ExecutionClientView info = self.info();
        int index = self.index();
        Minecraft.getInstance().execute(() ->
                SpellcastingClientEvents.fireServerUpdatePost(info, index, Minecraft.getInstance().screen)
        );
    }
}
