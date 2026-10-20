package cn.xm1221.MieLib.mixin;

import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.Iota;
import cn.xm1221.MieLib.registry.CastingVMHook;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CastingVM.class)
public class CastingVMMixin {
    @WrapMethod(method = "executeInner", remap = false)
    private CastResult mielib$executeInner(Iota iota, ServerLevel world, SpellContinuation continuation, Operation<CastResult> original) {
        return CastingVMHook.execute(iota, world, continuation, original,(CastingVM) (Object)this);
    }
}
