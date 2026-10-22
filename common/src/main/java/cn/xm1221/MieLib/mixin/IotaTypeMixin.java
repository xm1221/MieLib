package cn.xm1221.MieLib.mixin;

import at.petrak.hexcasting.api.casting.iota.IotaType;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;

@Mixin({IotaType.class})

public class IotaTypeMixin {
    @WrapMethod(method = "display")
    public static Component display(Tag par1, Operation<Component> original) {
        return original.call(par1);
    }
}
