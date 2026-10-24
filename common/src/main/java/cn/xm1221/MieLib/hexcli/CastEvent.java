package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.HexAPI;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.misc.MediaConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

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
    /** 事件所属维度（渡鸦之思反序列化用）；拿不到时为 null。 */
    private final @Nullable ServerLevel world;

    int steps = 0;
    long mediaUsed = 0;
    ResolvedPatternType lastResolution = null;
    public final List<String> mishaps = new ArrayList<>();
    List<String> stack = List.of();
    List<JsonObject> stackJson = List.of();
    String ravenmind = null;
    @Nullable JsonObject ravenmindJson = null;
    long opsConsumed = 0;

    private CastEvent(int id, boolean root, String envClass, String caster, String position, int startTick,
                      @Nullable ServerLevel world) {
        this.id = id;
        this.root = root;
        this.envClass = envClass;
        this.caster = caster;
        this.position = position;
        this.startTick = startTick;
        this.world = world;
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
        ServerLevel world;
        try {
            world = env.getWorld();
        } catch (Throwable t) {
            world = null;
        }
        return new CastEvent(id, HexCliSession.isRootEnv(env), env.getClass().getName(), caster, pos, tick, world);
    }

    void finish(CastingImage image) {
        List<String> s = new ArrayList<>();
        List<JsonObject> sj = new ArrayList<>();
        for (Iota iota : image.getStack()) {
            s.add(HexCliIotas.text(iota));
            sj.add(HexCliIotas.toJson(iota));
        }
        stack = s;
        stackJson = sj;
        ravenmind = ravenmindOf(image, world);
        ravenmindJson = ravenmindJsonOf(image);
        opsConsumed = image.getOpsConsumed();
    }

    public static String ravenmindOf(CastingImage image) {
        return ravenmindOf(image, null);
    }

    /** 渡鸦之思文本；给了维度就反序列化成 iota 走 {@link HexCliIotas#text}（实体显示已修正）。 */
    public static String ravenmindOf(CastingImage image, @Nullable ServerLevel world) {
        CompoundTag ud = image.getUserData();
        if (!ud.contains(HexAPI.RAVENMIND_USERDATA, Tag.TAG_COMPOUND)) return null;
        CompoundTag tag = ud.getCompound(HexAPI.RAVENMIND_USERDATA);
        if (world != null) {
            try {
                Iota iota = IotaType.deserialize(tag, world);
                if (iota != null) return HexCliIotas.text(iota);
            } catch (Throwable ignored) {
                // 退回 tag 显示
            }
        }
        return safe(() -> IotaType.getDisplay(tag).getString());
    }

    /** 渡鸦之思的结构化表示（实体 / 列表已修正）；没有时为 null。 */
    private @Nullable JsonObject ravenmindJsonOf(CastingImage image) {
        CompoundTag ud = image.getUserData();
        if (!ud.contains(HexAPI.RAVENMIND_USERDATA, Tag.TAG_COMPOUND)) return null;
        CompoundTag tag = ud.getCompound(HexAPI.RAVENMIND_USERDATA);
        if (world != null) {
            try {
                Iota iota = IotaType.deserialize(tag, world);
                if (iota != null) return HexCliIotas.toJson(iota);
            } catch (Throwable ignored) {
                // 退回 tag 显示
            }
        }
        try {
            JsonObject out = new JsonObject();
            IotaType<?> type = IotaType.getTypeFromTag(tag);
            out.addProperty("type", type == null ? "unknown" : String.valueOf(
                at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.getIotaTypeRegistry().getKey(type)));
            out.addProperty("text", IotaType.getDisplay(tag).getString());
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void renderStack(List<String> out, List<String> stack) {
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

    /** 远程客户端用的结构化表示（字段见 HEXCLI.md 的协议说明）。 */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("root", root);
        o.addProperty("env", envClass);
        o.addProperty("caster", caster);
        o.addProperty("position", position);
        o.addProperty("tick", startTick);
        o.addProperty("resolution", lastResolution == null ? null : lastResolution.toString());
        o.addProperty("steps", steps);
        o.addProperty("ops", opsConsumed);
        JsonArray st = new JsonArray();
        for (JsonObject iota : stackJson) st.add(iota);
        o.add("stack", st);
        if (ravenmindJson != null) o.add("ravenmind", ravenmindJson);
        else o.add("ravenmind", JsonNull.INSTANCE);
        JsonArray ms = new JsonArray();
        for (String m : mishaps) ms.add(m);
        o.add("mishaps", ms);
        o.addProperty("mediaUsed", mediaUsed);
        o.addProperty("mediaDust", (double) mediaUsed / MediaConstants.DUST_UNIT);
        return o;
    }

    static String safe(Supplier<?> f) {
        try {
            return String.valueOf(f.get());
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
    }
}
