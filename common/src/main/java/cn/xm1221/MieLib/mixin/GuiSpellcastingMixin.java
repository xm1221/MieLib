package cn.xm1221.MieLib.mixin;

import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import cn.xm1221.MieLib.registry.DrawPatternHook;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.phys.Vec2;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Set;

@Mixin(GuiSpellcasting.class)

public class GuiSpellcastingMixin {
    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE", target = "Lat/petrak/hexcasting/client/render/RenderLib;drawPatternFromPoints(Lorg/joml/Matrix4f;Ljava/util/List;Ljava/util/Set;ZIIFFFD)V"),
            remap = false
    )
    private void mielib$wrapDrawPattern(
            Matrix4f mat, List<Vec2> points, Set<Integer> dupIndices,
            boolean drawLast, int tail, int head, float flowIrregular,
            float readabilityOffset, float lastSegmentLen, double seed,
            Operation<Void> original
    ){
       var res= DrawPatternHook.effect(drawLast,tail,head,flowIrregular);
       original.call(mat, points, dupIndices, res.getDrawLast(),res.getTail(),res.getHead(),res.getFlowIrregular() ,readabilityOffset, lastSegmentLen, seed);
    }

}
