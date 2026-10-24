package cn.xm1221.MieLib.hexcli.example;

import cn.xm1221.MieLib.hexcli.api.CasterKind;
import cn.xm1221.MieLib.hexcli.api.EnvSpec;
import cn.xm1221.MieLib.hexcli.api.HexCliAPI;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * 真正调用 HexCLI API 的类。<b>只有 MieLib 存在时才会被加载</b>（见 {@link HexCliExample}），
 * 所以可以放心 import {@code cn.xm1221.MieLib.hexcli.api.*}。注册只写静态表，HexCLI 关闭时也可以调用。
 */
final class HexCliExampleCompat {
    private HexCliExampleCompat() {
    }

    static void register() {
        ResourceLocation id = new ResourceLocation("hexcli_example", "mob");
        HexCliAPI.registerEnv(id, EnvSpec.builder(ctx ->
                new ExampleMobCastEnv(ctx.level(), (LivingEntity) ctx.caster(), ctx.envData()))
            .casters(CasterKind.LIVING)
            .description("example: any non-player mob as caster")
            .build());
        HexCliExample.LOGGER.info("[hexcli_example] registered env {}", id);

        // 演示重复注册会被拒绝（同一 id 再注册一次 → IllegalArgumentException）。
        try {
            HexCliAPI.registerEnv(id, EnvSpec.builder(ctx -> {
                    throw new IllegalStateException("never called");
                })
                .casters(CasterKind.LIVING)
                .description("duplicate")
                .build());
        } catch (IllegalArgumentException e) {
            HexCliExample.LOGGER.info("[hexcli_example] duplicate registration rejected as expected: {}", e.getMessage());
        }
    }
}
