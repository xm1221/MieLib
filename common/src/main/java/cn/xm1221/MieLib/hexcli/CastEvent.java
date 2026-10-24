package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.HexAPI;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.misc.MediaConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 一次 VM 运行（一轮 queueExecuteAndWrapIotas，结束于 PostCast）的观察记录。 */
public final class CastEvent {
    public final int id;
    /** HexCLI 命令自己发起的（环境是 HexCLI 会话本次 run 创建的那个）为 root，其它都是 spawned。 */
    public final boolean root;
    public final String envClass;
    public final String caster;
    public final String position;
    public final int startTick;

    int steps = 0;
    long mediaUsed = 0;
    ResolvedPatternType lastResolution = null;
    public final List<String> mishaps = new ArrayList<>();
    List<String> stack = List.of();
    String ravenmind = null;
    long opsConsumed = 0;

    private CastEvent(int id, boolean root, String envClass, String caster, String position, int startTick) {
        this.id = id;
        this.root = root;
        this.envClass = envClass;
        this.caster = caster;
        this.position = position;
        this.startTick = startTick;
    }

    static CastEvent begin(CastingEnvironment env, int id, int tick) {
        LivingEntity entity;
        try {
            entity = env.getCastingEntity();
        } catch (Throwable t) {
            entity = null;
        }
        String caster = "none";
        if (entity != null) {
            final LivingEntity e = entity;
            caster = safe(() -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())
                + " \"" + safe(() -> e.getName().getString()) + "\" "
                + e.getUUID().toString().substring(0, 8);
        }
        String pos = safe(() -> {
            Vec3 v = env.mishapSprayPos();
            return String.format("%.1f, %.1f, %.1f", v.x, v.y, v.z);
        });
        return new CastEvent(id, HexCliSession.isRootEnv(env), env.getClass().getName(), caster, pos, tick);
    }

    void finish(CastingImage image) {
        List<String> s = new ArrayList<>();
        for (Iota iota : image.getStack()) s.add(safe(() -> iota.display().getString()));
        stack = s;
        ravenmind = ravenmindOf(image);
        opsConsumed = image.getOpsConsumed();
    }

    static String ravenmindOf(CastingImage image) {
        var ud = image.getUserData();
        if (!ud.contains(HexAPI.RAVENMIND_USERDATA, Tag.TAG_COMPOUND)) return null;
        return safe(() -> IotaType.getDisplay(ud.getCompound(HexAPI.RAVENMIND_USERDATA)).getString());
    }

    static void renderStack(List<String> out, List<String> stack) {
        if (stack.isEmpty()) {
            out.add("  stack: (empty)");
        } else {
            out.add("  stack (bottom -> top, " + stack.size() + "):");
            for (int i = 0; i < stack.size(); i++) out.add("    [" + i + "] " + stack.get(i));
        }
    }

    /** 控制台可读的多行输出。 */
    public List<String> render() {
        List<String> out = new ArrayList<>();
        out.add("[HexCLI] cast #" + id + " (" + (root ? "root" : "spawned") + ") env=" + envClass
            + " caster=" + caster + " at=" + position);
        out.add("  resolution: " + (lastResolution == null ? "(no patterns)" : lastResolution)
            + "   steps: " + steps + "   ops: " + opsConsumed);
        renderStack(out, stack);
        out.add("  ravenmind: " + (ravenmind == null ? "(none)" : ravenmind));
        if (mishaps.isEmpty()) {
            out.add("  mishaps: (none)");
        } else {
            out.add("  mishaps (" + mishaps.size() + "):");
            for (String m : mishaps) out.add("    ! " + m);
        }
        out.add(String.format("  media used: %d (%.2f dust)", mediaUsed, (double) mediaUsed / MediaConstants.DUST_UNIT));
        return out;
    }

    static String safe(Supplier<?> f) {
        try {
            return String.valueOf(f.get());
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
    }
}
