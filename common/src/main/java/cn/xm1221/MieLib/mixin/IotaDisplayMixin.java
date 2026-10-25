package cn.xm1221.MieLib.mixin;

import at.petrak.hexcasting.api.casting.iota.Iota;
import cn.xm1221.MieLib.registry.IotaDisplayHook;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Client display hook. Hex 0.12 moved iota text onto the instance method {@code Iota.display()},
 * which each subtype overrides, so a mixin on the abstract method would not see those overrides.
 * {@code displayWithMaxWidth} is still concrete on {@link Iota} and is what {@code GuiSpellcasting} calls.
 */
@Mixin(Iota.class)
public abstract class IotaDisplayMixin {

    @WrapMethod(method = "displayWithMaxWidth", remap = false)
    private FormattedCharSequence mielib$displayWithMaxWidth(
            int maxWidth, Font font, Operation<FormattedCharSequence> original) {
        Iota self = (Iota) (Object) this;
        if (!IotaDisplayHook.hasWorkersFor(self)) {
            return original.call(maxWidth, font);
        }

        Component base = self.display();
        IotaDisplayHook.IotaDisplayCall result =
                IotaDisplayHook.effect(self, new IotaDisplayHook.IotaDisplayCall(base, null, self.getType()));
        Component display = result.getDisplay();
        if (display == null || display == base) {
            return original.call(maxWidth, font);
        }

        var splitted = font.split(display, maxWidth - font.width("..."));
        if (splitted.isEmpty()) {
            return FormattedCharSequence.EMPTY;
        } else if (splitted.size() == 1) {
            return splitted.get(0);
        } else {
            return FormattedCharSequence.fromPair(
                    splitted.get(0),
                    Component.literal("...").withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
    }
}
