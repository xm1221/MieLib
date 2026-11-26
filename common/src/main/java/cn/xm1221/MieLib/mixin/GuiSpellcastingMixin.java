package cn.xm1221.MieLib.mixin;

import at.petrak.hexcasting.api.casting.eval.ExecutionClientView;
import at.petrak.hexcasting.api.casting.eval.ResolvedPattern;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.math.HexCoord;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import at.petrak.hexcasting.common.msgs.IMessage;
import at.petrak.hexcasting.common.msgs.MsgNewSpellPatternC2S;
import at.petrak.hexcasting.xplat.IClientXplatAbstractions;
import cn.xm1221.MieLib.client.SpellcastingGuiAccess;
import cn.xm1221.MieLib.registry.DrawPatternHook;
import cn.xm1221.MieLib.registry.SpellcastingClientEvents;
import cn.xm1221.MieLib.worker.DrawPatternWorker;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec2;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@Mixin(GuiSpellcasting.class)
public abstract class GuiSpellcastingMixin implements SpellcastingGuiAccess {

    @Shadow(remap = false)
    @Final
    private InteractionHand handOpenedWith;

    @Shadow(remap = false)
    private List<ResolvedPattern> patterns;

    @Shadow(remap = false)
    private List<? extends CompoundTag> cachedStack;

    @Shadow(remap = false)
    private CompoundTag cachedRavenmind;

    @Shadow(remap = false)
    @Final
    private Set<HexCoord> usedSpots;

    @Shadow(remap = false)
    public abstract void calculateIotaDisplays();

    @Unique
    @Nullable
    private static final Field MIELIB$DRAW_STATE_FIELD = mielib$findDrawStateField();

    @Unique
    @Nullable
    private static final Class<?> MIELIB$DRAWING_CLASS = mielib$findClass("at.petrak.hexcasting.client.gui.GuiSpellcasting$PatternDrawState$Drawing");

    @Unique
    @Nullable
    private static final Object MIELIB$BETWEEN_INSTANCE = mielib$findBetweenInstance();

    @Unique
    @Nullable
    private static final Method MIELIB$WIP_PATTERN_METHOD = mielib$findWipPatternMethod();

    @Unique
    @Nullable
    private Float mielib$hexSizeOverride = null;

    @Unique
    @Nullable
    private Vec2 mielib$coordsOffsetOverride = null;

    @Unique
    private boolean mielib$tickEnabled = true;

    @Unique
    private boolean mielib$sendPatternPackets = true;

    @Unique
    @Nullable
    private Consumer<HexPattern> mielib$onDrawPattern = null;

    @Unique
    private boolean mielib$keepOpenOnStackClear = false;

    @Unique
    @Nullable
    private static Field mielib$findDrawStateField() {
        try {
            Field f = GuiSpellcasting.class.getDeclaredField("drawState");
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    @Nullable
    private static Class<?> mielib$findClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    @Nullable
    private static Object mielib$findBetweenInstance() {
        try {
            Class<?> c = Class.forName("at.petrak.hexcasting.client.gui.GuiSpellcasting$PatternDrawState$BetweenPatterns");
            Field f = c.getField("INSTANCE");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    @Nullable
    private static Method mielib$findWipPatternMethod() {
        if (MIELIB$DRAWING_CLASS == null) return null;
        try {
            Method m = MIELIB$DRAWING_CLASS.getMethod("getWipPattern");
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    @Nullable
    private Object mielib$rawDrawState() {
        if (MIELIB$DRAW_STATE_FIELD == null) return null;
        try {
            return MIELIB$DRAW_STATE_FIELD.get(this);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @WrapOperation(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lat/petrak/hexcasting/client/render/RenderLib;drawPatternFromPoints(Lorg/joml/Matrix4f;Ljava/util/List;Ljava/util/Set;ZIIFFFD)V",
                    remap = false
            )
    )
    private void mielib$wrapDrawPattern(
            Matrix4f mat, List<Vec2> points, Set<Integer> dupIndices,
            boolean drawLast, int tail, int head, float flowIrregular,
            float readabilityOffset, float lastSegmentLen, double seed,
            Operation<Void> original
    ) {
        if (DrawPatternHook.INSTANCE.isEmpty()) {
            original.call(mat, points, dupIndices, drawLast, tail, head, flowIrregular, readabilityOffset, lastSegmentLen, seed);
            return;
        }
        int idx = !Double.isNaN(seed) ? (int) Math.round(seed) : -1;
        boolean completed = drawLast
                && !Double.isNaN(seed)
                && Math.abs(seed - idx) < 1e-6
                && idx >= 0
                && idx < this.patterns.size();
        ResolvedPattern resolved = completed ? this.patterns.get(idx) : null;
        var ctx = new DrawPatternWorker.DrawPatternContext(
                (GuiSpellcasting) (Object) this,
                completed ? idx : -1,
                resolved,
                !completed,
                seed
        );
        var res = DrawPatternHook.effect(drawLast, tail, head, flowIrregular, ctx);
        original.call(mat, points, dupIndices, res.getDrawLast(), res.getTail(), res.getHead(), res.getFlowIrregular(), readabilityOffset, lastSegmentLen, seed);
    }

    @WrapWithCondition(
            method = "drawEnd",
            at = @At(
                    value = "INVOKE",
                    target = "Lat/petrak/hexcasting/xplat/IClientXplatAbstractions;sendPacketToServer(Lat/petrak/hexcasting/common/msgs/IMessage;)V",
                    remap = false
            ),
            remap = false
    )
    private boolean mielib$onSendPatternPacket(IClientXplatAbstractions inst, IMessage msg) {
        if (msg instanceof MsgNewSpellPatternC2S packet) {
            if (this.mielib$onDrawPattern != null) {
                this.mielib$onDrawPattern.accept(packet.pattern());
            }
            return this.mielib$sendPatternPackets;
        }
        return true;
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void mielib$maybeCancelTick(CallbackInfo ci) {
        if (!this.mielib$tickEnabled) {
            ci.cancel();
        }
    }

    @ModifyReturnValue(method = "hexSize", at = @At("RETURN"), remap = false)
    private float mielib$overrideHexSize(float original) {
        return this.mielib$hexSizeOverride != null && !Float.isNaN(this.mielib$hexSizeOverride)
                ? this.mielib$hexSizeOverride
                : original;
    }

    @ModifyReturnValue(method = "coordsOffset", at = @At("RETURN"), remap = false)
    private Vec2 mielib$overrideCoordsOffset(Vec2 original) {
        return this.mielib$coordsOffsetOverride != null ? this.mielib$coordsOffsetOverride : original;
    }

    @Inject(method = "recvServerUpdate", at = @At("HEAD"), cancellable = true, remap = false)
    private void mielib$handleKeepOpenOnStackClear(ExecutionClientView info, int index, CallbackInfo ci) {
        if (this.mielib$keepOpenOnStackClear && info.isStackClear()) {
            this.clearStackDisplay();
            ci.cancel();
        }
    }

    @Inject(method = "recvServerUpdate", at = @At("TAIL"), remap = false)
    private void mielib$afterRecvServerUpdate(ExecutionClientView info, int index, CallbackInfo ci) {
        SpellcastingClientEvents.fireGuiServerUpdate((GuiSpellcasting) (Object) this, info, index);
    }

    @Override
    @NotNull
    public InteractionHand getHandOpenedWith() {
        return this.handOpenedWith;
    }

    @Override
    @NotNull
    public List<ResolvedPattern> getPatterns() {
        return Collections.unmodifiableList(this.patterns);
    }

    @Override
    @NotNull
    public List<CompoundTag> getCachedStack() {
        return this.cachedStack == null ? List.of() : List.copyOf(this.cachedStack);
    }

    @Override
    public boolean isStrokeActive() {
        Object st = mielib$rawDrawState();
        if (st == null) return false;
        if (MIELIB$BETWEEN_INSTANCE != null) {
            return st != MIELIB$BETWEEN_INSTANCE;
        }
        return !"BetweenPatterns".equals(st.getClass().getSimpleName());
    }

    @Override
    public boolean isDrawing() {
        Object st = mielib$rawDrawState();
        if (st == null) return false;
        if (MIELIB$DRAWING_CLASS != null) {
            return MIELIB$DRAWING_CLASS.isInstance(st);
        }
        return "Drawing".equals(st.getClass().getSimpleName());
    }

    @Override
    @Nullable
    public HexPattern getWipPattern() {
        Object st = mielib$rawDrawState();
        if (st == null || MIELIB$WIP_PATTERN_METHOD == null) return null;
        if (MIELIB$DRAWING_CLASS != null && !MIELIB$DRAWING_CLASS.isInstance(st)) return null;
        try {
            return (HexPattern) MIELIB$WIP_PATTERN_METHOD.invoke(st);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void setHexSizeOverride(@Nullable Float hexSize) {
        this.mielib$hexSizeOverride = hexSize;
    }

    @Override
    public void setCoordsOffsetOverride(@Nullable Vec2 offset) {
        this.mielib$coordsOffsetOverride = offset;
    }

    @Override
    public void setViewport(@Nullable Float hexSize, @Nullable Vec2 offset) {
        this.mielib$hexSizeOverride = hexSize;
        this.mielib$coordsOffsetOverride = offset;
    }

    @Override
    public void setTickEnabled(boolean enabled) {
        this.mielib$tickEnabled = enabled;
    }

    @Override
    public void setSendPatternPackets(boolean send) {
        this.mielib$sendPatternPackets = send;
    }

    @Override
    public void setOnDrawPattern(@Nullable Consumer<HexPattern> callback) {
        this.mielib$onDrawPattern = callback;
    }

    @Override
    public void setKeepOpenOnStackClear(boolean keepOpen) {
        this.mielib$keepOpenOnStackClear = keepOpen;
    }

    @Override
    public void clearPatterns() {
        this.patterns.clear();
        this.usedSpots.clear();
    }

    @Override
    public void setPatternType(int index, @NotNull ResolvedPatternType type) {
        if (index >= 0 && index < this.patterns.size()) {
            this.patterns.get(index).setType(type);
        }
    }

    @Override
    public void setLastPatternType(@NotNull ResolvedPatternType type) {
        if (!this.patterns.isEmpty()) {
            this.patterns.get(this.patterns.size() - 1).setType(type);
        }
    }

    @Override
    public void clearStackDisplay() {
        this.cachedStack = Collections.emptyList();
        this.cachedRavenmind = null;
        this.calculateIotaDisplays();
    }

    @Override
    public void recalculateIotaDisplays() {
        this.calculateIotaDisplays();
    }

    @Override
    public boolean cancelStroke() {
        if (isStrokeActive() && MIELIB$DRAW_STATE_FIELD != null && MIELIB$BETWEEN_INSTANCE != null) {
            try {
                MIELIB$DRAW_STATE_FIELD.set(this, MIELIB$BETWEEN_INSTANCE);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }
}
