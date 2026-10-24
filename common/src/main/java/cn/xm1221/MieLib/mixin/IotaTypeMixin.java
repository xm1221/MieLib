package cn.xm1221.MieLib.mixin;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.common.lib.HexRegistries;
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes;
import cn.xm1221.MieLib.registry.IotaDisplayHook;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Objects;

@Mixin({IotaType.class})

public abstract class IotaTypeMixin {




    @WrapMethod(method = "display")
    public Component MieLib$display(Tag par1, Operation<Component> original) {
        CompoundTag tag = new CompoundTag();
        tag.putString(HexIotaTypes.KEY_TYPE, Objects.requireNonNull(HexIotaTypes.REGISTRY.getKey((IotaType<?>) (Object) this)).toString());
        tag.put(HexIotaTypes.KEY_DATA,par1);
        var res = IotaDisplayHook.effect(tag);
        if(res!=null) return res.display();
        return original.call(par1);
    }

    @WrapMethod(method = "getColor")
    public static int MieLib$getColor(CompoundTag tag, Operation<Integer> original) {
        var res = IotaDisplayHook.effect(tag);
        var color = res.color();
       if(color!=null) return color;
       return original.call(tag);
    }
}
