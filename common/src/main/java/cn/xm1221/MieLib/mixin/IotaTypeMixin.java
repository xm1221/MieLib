package cn.xm1221.MieLib.mixin;

import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes;
import cn.xm1221.MieLib.registry.IotaDisplayHook;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Client-only hooks for {@link IotaType}'s display helpers.
 *
 * <p>Only the static entry points are wrapped. {@code display(Tag)} is an abstract instance method and
 * must not be mixed into; addons observe it through the static methods instead.
 */
@Mixin(IotaType.class)
public abstract class IotaTypeMixin {

    @WrapMethod(method = "getDisplay", remap = false)
    private static Component mielib$getDisplay(CompoundTag tag, Operation<Component> original) {
        Component orig = original.call(tag);
        if (!IotaDisplayHook.hasWorkersFor(tag)) return orig;

        IotaType<?> type = IotaType.getTypeFromTag(tag);
        Tag data = tag.get(HexIotaTypes.KEY_DATA);
        if (type == null || data == null) return orig;

        IotaDisplayHook.IotaDisplayCall result =
                IotaDisplayHook.effect(tag, new IotaDisplayHook.IotaDisplayCall(orig, null, type));
        Component display = result.getDisplay();
        return display != null ? display : orig;
    }

    @WrapMethod(method = "getDisplayWithMaxWidth", remap = false)
    private static FormattedCharSequence mielib$getDisplayWithMaxWidth(
            CompoundTag tag, int maxWidth, Font font, Operation<FormattedCharSequence> original) {
        if (!IotaDisplayHook.hasWorkersFor(tag)) return original.call(tag, maxWidth, font);

        IotaType<?> type = IotaType.getTypeFromTag(tag);
        Tag data = tag.get(HexIotaTypes.KEY_DATA);
        if (type == null || data == null) return original.call(tag, maxWidth, font);

        // Call the type directly instead of IotaType.getDisplay: that static method is wrapped above,
        // so going through it would run the hook chain twice.
        Component base;
        try {
            base = type.display(data);
        } catch (Exception e) {
            return original.call(tag, maxWidth, font);
        }

        IotaDisplayHook.IotaDisplayCall result =
                IotaDisplayHook.effect(tag, new IotaDisplayHook.IotaDisplayCall(base, null, type));
        Component display = result.getDisplay();
        if (display == null) return original.call(tag, maxWidth, font);

        // Reproduce Hex's truncation for the rewritten display.
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

    @WrapMethod(method = "getColor", remap = false)
    private static int mielib$getColor(CompoundTag tag, Operation<Integer> original) {
        int orig = original.call(tag);
        if (!IotaDisplayHook.hasWorkersFor(tag)) return orig;

        IotaType<?> type = IotaType.getTypeFromTag(tag);
        Tag data = tag.get(HexIotaTypes.KEY_DATA);
        if (type == null || data == null) return orig;

        IotaDisplayHook.IotaDisplayCall result =
                IotaDisplayHook.effect(tag, new IotaDisplayHook.IotaDisplayCall(null, orig, type));
        Integer color = result.getColor();
        return color != null ? color : orig;
    }
}
