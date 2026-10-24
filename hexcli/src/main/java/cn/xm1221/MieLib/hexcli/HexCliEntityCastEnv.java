package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.MishapEnvironment;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.common.lib.HexAttributes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 非玩家施法环境（{@code hexcli:entity}）：施法者是任意 {@link LivingEntity}（僵尸、悦灵……），或者没有施法者（null）。
 * <p>
 * 手上物品取自生物的主 / 副手（无施法者则为空），位置取生物位置或 profile 的锚点。
 * 启迪直接用 profile 的 enlightened；媒质只支持 infinite / 预算（inventory 在应用 profile 时已降级为 infinite 并警告）。
 */
public class HexCliEntityCastEnv extends CastingEnvironment {
    protected final @Nullable LivingEntity entity;
    protected final Vec3 anchor;
    protected final HexCliSession session;
    /** 见 {@link #extractMedia}：扣费期间对本体隐藏施法者。 */
    private boolean hideEntity;

    public HexCliEntityCastEnv(ServerLevel level, @Nullable LivingEntity entity, Vec3 anchor, HexCliSession session) {
        super(level);
        this.entity = entity;
        this.anchor = anchor;
        this.session = session;
    }

    public Vec3 position() {
        return entity != null ? entity.position() : anchor;
    }

    @Override
    public @Nullable LivingEntity getCastingEntity() {
        return hideEntity ? null : entity;
    }

    /**
     * 本体 {@link CastingEnvironment#extractMedia} 会读施法者的 {@code hexcasting:media_consumption} 属性，
     * 但这个属性只注册给了玩家，非玩家生物会直接抛 IllegalArgumentException（所有需要媒质的图案都炸）。
     * 生物没有该属性时，扣费期间让 getCastingEntity() 返回 null，跳过倍率（等价于 1.0），其余逻辑（扩展组件、记账）照旧。
     */
    @Override
    public long extractMedia(long cost, boolean simulate) {
        if (entity == null || entity.getAttributes().hasAttribute(HexAttributes.MEDIA_CONSUMPTION_MODIFIER))
            return super.extractMedia(cost, simulate);
        hideEntity = true;
        try {
            return super.extractMedia(cost, simulate);
        } finally {
            hideEntity = false;
        }
    }

    @Override
    public MishapEnvironment getMishapEnvironment() {
        return new EntityMishapEnv(world, entity);
    }

    @Override
    public Vec3 mishapSprayPos() {
        return position();
    }

    @Override
    public boolean isEnlightened() {
        return session.enlightened();
    }

    @Override
    protected long extractMediaEnvironment(long cost, boolean simulate) {
        return session.takeMedia(cost, simulate);
    }

    @Override
    protected boolean isVecInRangeEnvironment(Vec3 vec) {
        Boolean r = session.ambitCheck(position(), vec);
        // 非玩家没有“本体默认施法范围”，default 按法杖的 32 格算
        return r != null ? r : position().distanceToSqr(vec) <= 32.0 * 32.0;
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
    protected List<ItemStack> getUsableStacks(StackDiscoveryMode mode) {
        List<ItemStack> out = new ArrayList<>();
        if (entity == null) return out;
        if (mode == StackDiscoveryMode.QUERY) {
            out.add(entity.getMainHandItem());
            out.add(entity.getOffhandItem());
        } else {
            out.add(entity.getOffhandItem());
            out.add(entity.getMainHandItem());
        }
        out.removeIf(ItemStack::isEmpty);
        return out;
    }

    @Override
    protected List<HeldItemInfo> getPrimaryStacks() {
        if (entity == null) return List.of();
        return List.of(new HeldItemInfo(entity.getOffhandItem(), InteractionHand.OFF_HAND),
            new HeldItemInfo(entity.getMainHandItem(), InteractionHand.MAIN_HAND));
    }

    @Override
    public boolean replaceItem(Predicate<ItemStack> stackOk, ItemStack replaceWith, @Nullable InteractionHand hand) {
        if (entity == null) return false;
        if (hand != null && stackOk.test(entity.getItemInHand(hand))) {
            entity.setItemInHand(hand, replaceWith);
            return true;
        }
        for (InteractionHand h : new InteractionHand[]{InteractionHand.OFF_HAND, InteractionHand.MAIN_HAND}) {
            if (stackOk.test(entity.getItemInHand(h))) {
                entity.setItemInHand(h, replaceWith);
                return true;
            }
        }
        return false;
    }

    @Override
    public FrozenPigment getPigment() {
        return session.pigment();
    }

    @Override
    public @Nullable FrozenPigment setPigment(@Nullable FrozenPigment pigment) {
        FrozenPigment old = session.pigment();
        session.setPigment(pigment);
        return old;
    }

    @Override
    public void produceParticles(ParticleSpray particles, FrozenPigment colorizer) {
        particles.sprayParticles(world, colorizer);
    }

    @Override
    public void printMessage(Component message) {
        session.print(message);
    }

    /** 事故对生物施法者生效（扣血、掉手上物品、失明……），无施法者时全部无操作。 */
    static final class EntityMishapEnv extends MishapEnvironment {
        private final @Nullable LivingEntity entity;

        EntityMishapEnv(ServerLevel world, @Nullable LivingEntity entity) {
            super(world, null);
            this.entity = entity;
        }

        @Override
        public void yeetHeldItemsTowards(Vec3 targetPos) {
            if (entity == null) return;
            Vec3 delta = targetPos.subtract(entity.position()).normalize().scale(0.5);
            for (InteractionHand h : InteractionHand.values()) {
                ItemStack s = entity.getItemInHand(h);
                if (s.isEmpty()) continue;
                entity.setItemInHand(h, ItemStack.EMPTY);
                yeetItem(s, entity.getEyePosition(), delta);
            }
        }

        @Override
        public void dropHeldItems() {
            if (entity == null) return;
            yeetHeldItemsTowards(entity.position().add(entity.getLookAngle()));
        }

        @Override
        public void drown() {
            if (entity == null) return;
            entity.hurt(entity.damageSources().drown(), 2f);
            entity.setAirSupply(0);
        }

        @Override
        public void damage(float healthProportion) {
            if (entity == null) return;
            entity.hurt(entity.damageSources().magic(), entity.getMaxHealth() * healthProportion);
        }

        @Override
        public void removeXp(int amount) {
        }

        @Override
        public void blind(int ticks) {
            if (entity == null) return;
            entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, ticks));
        }
    }
}
