package cn.xm1221.MieLib.mixin;

import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.Iota;
import cn.xm1221.MieLib.registry.CastingVMHook;
import cn.xm1221.MieLib.vm.CastingVMEnvAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(CastingVM.class)
public class CastingVMMixin implements CastingVMEnvAccess {
    @Shadow(remap = false)
    @Final
    @Mutable
    private CastingEnvironment env;

    @WrapMethod(method = "executeInner", remap = false)
    private CastResult mielib$executeInner(Iota iota, ServerLevel world, SpellContinuation continuation, Operation<CastResult> original) {
        return CastingVMHook.execute(iota, world, continuation, original,(CastingVM) (Object)this);
    }

    @Unique
    @Override
    public void mielib$replaceEnv(CastingEnvironment next) {
        if (next == null) {
            throw new IllegalArgumentException("CastingVM.replaceEnv: next environment must not be null");
        }
        this.env = next;
        // Hex 0.12: triggerCreateEvent takes the CastingImage, not the old user-data tag.
        next.triggerCreateEvent(((CastingVM) (Object) this).getImage());
    }
}
