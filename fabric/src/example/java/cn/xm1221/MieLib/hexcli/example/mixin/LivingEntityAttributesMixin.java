package cn.xm1221.MieLib.hexcli.example.mixin;

import at.petrak.hexcasting.common.lib.HexAttributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 示例专用：本体只给玩家注册了 {@code hexcasting:media_consumption} 属性，而 {@code CastingEnvironment.extractMedia}
 * 对任何施法者都会读这个属性，非玩家生物当施法者时会抛 “Can't find attribute”。
 * 这里在所有生物的基础属性里补上它（默认值 1.0，不改变费用）。真正的修复计划放在 MieLib 本体里（生物施法环境那项工作）；
 * 在 MieLib 里给生物加这个属性是后续单独的任务，本示例只是绕开它。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityAttributesMixin {
    @Inject(method = "createLivingAttributes", at = @At("RETURN"))
    private static void hexcliExample$addMediaConsumption(CallbackInfoReturnable<AttributeSupplier.Builder> cir) {
        cir.getReturnValue().add(HexAttributes.MEDIA_CONSUMPTION_MODIFIER);
    }
}
