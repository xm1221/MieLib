package cn.xm1221.MieLib.mixin;

import cn.xm1221.MieLib.Mielib;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

// scuffed workaround for https://github.com/architectury/architectury-loom/issues/189
@Mixin({
    net.minecraft.data.Main.class,
    net.minecraft.server.Main.class,
})
public abstract class MixinDatagenMain {
    @WrapMethod(method = "main", remap = false)
    private static void mielib$systemExitAfterDatagenFinishes(String[] strings, Operation<Void> original) {
        try {
            original.call((Object) strings);
        } catch (Throwable throwable) {
            Mielib.LOGGER.error("Datagen failed!", throwable);
            System.exit(1);
        }
        Mielib.LOGGER.info("Datagen succeeded, terminating.");
        System.exit(0);
    }
}
