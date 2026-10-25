package cn.xm1221.MieLib.hexcli.example;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.MishapEnvironment;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 示例用的“生物施法环境”：直接继承本体 {@link CastingEnvironment}，让任意非玩家 {@link LivingEntity} 当施法者。
 * <p>
 * 这只是示例代码——真正可用的实体施法环境计划做进 MieLib 本身。行为尽量简单：
 * <ul>
 *   <li>媒质：原样返回（生物没有媒质；HexCLI 的媒质组件在 infinite / 数字模式下会先付掉）</li>
 *   <li>范围：以生物眼睛为圆心、半径 {@code envData.radius}（默认 16）</li>
 *   <li>权限：永远可以编辑方块</li>
 *   <li>手：生物的主手 / 副手</li>
 *   <li>颜料：默认颜料；粒子照常发；print 写到日志 {@code [hexcli_example] <label>: <message>}</li>
 * </ul>
 * {@code envData} 是 profile 里 {@code envData} 字段（工厂通过 {@code ctx.envData()} 拿到），这里支持
 * {@code radius}（数字）与 {@code label}（字符串）。
 */
public final class ExampleMobCastEnv extends CastingEnvironment {
    private final LivingEntity mob;
    private final double radius;
    private final String label;
    private @Nullable FrozenPigment pigment;

    public ExampleMobCastEnv(ServerLevel level, LivingEntity mob, JsonObject data) {
        super(level);
        this.mob = mob;
        this.radius = data.has("radius") ? data.get("radius").getAsDouble() : 16.0;
        this.label = data.has("label") ? data.get("label").getAsString() : "mob";
        // 让操作员能确认 envData 确实传到了工厂
        HexCliExample.LOGGER.info("[hexcli_example] ExampleMobCastEnv for {} envData={}", mob.getType(), data);
    }

    @Override
    public @Nullable LivingEntity getCastingEntity() {
        return mob;
    }

    @Override
    public MishapEnvironment getMishapEnvironment() {
        return new MobMishapEnv(world, mob);
    }

    @Override
    public Vec3 mishapSprayPos() {
        return mob.position();
    }

    @Override
    protected long extractMediaEnvironment(long cost, boolean simulate) {
        return cost; // 生物没有媒质，原样返回
    }

    @Override
    protected boolean isVecInRangeEnvironment(Vec3 vec) {
        return vec.distanceToSqr(mob.getEyePosition()) <= radius * radius;
    }

    @Override
    protected boolean hasEditPermissionsAtEnvironment(BlockPos pos) {
        return true;
    }

    @Override
    public InteractionHand getCastingHand() {
        return InteractionHand.MAIN_HAND;
    }

    @Override
    public List<ItemStack> getUsableStacks(StackDiscoveryMode mode) {
        List<ItemStack> out = new ArrayList<>();
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack s = mob.getItemInHand(hand);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    @Override
    public List<HeldItemInfo> getPrimaryStacks() {
        List<HeldItemInfo> out = new ArrayList<>();
        out.add(new HeldItemInfo(mob.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND));
        out.add(new HeldItemInfo(mob.getItemInHand(InteractionHand.OFF_HAND), InteractionHand.OFF_HAND));
        return out;
    }

    @Override
    public boolean replaceItem(Predicate<ItemStack> stackOk, ItemStack replaceWith, @Nullable InteractionHand hand) {
        if (hand != null && stackOk.test(mob.getItemInHand(hand))) {
            mob.setItemInHand(hand, replaceWith);
            return true;
        }
        for (InteractionHand h : InteractionHand.values()) {
            if (stackOk.test(mob.getItemInHand(h))) {
                mob.setItemInHand(h, replaceWith);
                return true;
            }
        }
        return false;
    }

    @Override
    public FrozenPigment getPigment() {
        return pigment != null ? pigment : FrozenPigment.DEFAULT.get();
    }

    @Override
    public @Nullable FrozenPigment setPigment(@Nullable FrozenPigment pigment) {
        FrozenPigment old = this.pigment;
        this.pigment = pigment;
        return old;
    }

    @Override
    public void produceParticles(ParticleSpray particles, FrozenPigment colorizer) {
        particles.sprayParticles(world, colorizer);
    }

    @Override
    public void printMessage(Component message) {
        HexCliExample.LOGGER.info("[hexcli_example] {}: {}", label, message.getString());
    }

    /** 简单的事故环境：都作用在这只生物身上（没有玩家背包 / 经验，相应项做成无害的近似）。 */
    private static final class MobMishapEnv extends MishapEnvironment {
        private final LivingEntity mob;

        MobMishapEnv(ServerLevel world, LivingEntity mob) {
            super(world, null);
            this.mob = mob;
        }

        @Override
        public void yeetHeldItemsTowards(Vec3 targetPos) {
            Vec3 pos = mob.position();
            Vec3 delta = targetPos.subtract(pos).normalize().scale(0.5);
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = mob.getItemInHand(hand);
                if (stack.isEmpty()) continue;
                mob.setItemInHand(hand, ItemStack.EMPTY);
                ItemEntity e = new ItemEntity(world, pos.x, pos.y, pos.z, stack,
                    delta.x + (Math.random() - 0.5) * 0.1,
                    delta.y + (Math.random() - 0.5) * 0.1,
                    delta.z + (Math.random() - 0.5) * 0.1);
                e.setPickUpDelay(40);
                world.addFreshEntity(e);
            }
        }

        @Override
        public void dropHeldItems() {
            yeetHeldItemsTowards(mob.position().add(mob.getLookAngle()));
        }

        @Override
        public void drown() {
            mob.setAirSupply(0);
        }

        @Override
        public void damage(float healthProportion) {
            mob.hurt(mob.damageSources().magic(), mob.getMaxHealth() * healthProportion);
        }

        @Override
        public void removeXp(int amount) {
            // 生物没有经验条：无操作
        }

        @Override
        public void blind(int ticks) {
            // 生物没有客户端效果：无操作
        }
    }
}
