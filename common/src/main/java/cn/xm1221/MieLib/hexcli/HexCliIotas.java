package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.casting.iota.EntityIota;
import at.petrak.hexcasting.api.casting.iota.GarbageIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * iota 的文本 / JSON 表示。
 * <p>
 * {@link EntityIota#display()} 里带 Inline 的占位组件，控制台会打成 {@code Zombie: .}；
 * 这里对实体 iota 用 {@code <实体 id> "<名字>" @ x, y, z #<uuid 前 8 位>} 代替，列表递归。
 * 控制台输出和远程客户端共用同一套文本，保证两边看到的一样。
 */
public final class HexCliIotas {
    private HexCliIotas() {
    }

    /** 人类可读的一行文本（实体 / 列表已修正）。 */
    public static String text(Iota iota) {
        if (iota instanceof EntityIota ei) return entityText(ei.getEntity());
        // 本体的垃圾 iota 显示是乱码（obfuscated）文本，终端里看着像真数据
        if (iota instanceof GarbageIota) return "<garbage>";
        if (iota instanceof ListIota li) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Iota child : li.getList()) {
                if (!first) sb.append(", ");
                sb.append(text(child));
                first = false;
            }
            return sb.append(']').toString();
        }
        return safe(() -> iota.display().getString());
    }

    public static String entityText(Entity e) {
        String type = safe(() -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        String name = safe(() -> e.getName().getString());
        Vec3 p = e.position();
        String uuid = safe(() -> e.getUUID().toString());
        if (uuid.length() > 8) uuid = uuid.substring(0, 8);
        return String.format(Locale.ROOT, "%s \"%s\" @ %.1f, %.1f, %.1f #%s", type, name, p.x, p.y, p.z, uuid);
    }

    /** iota 类型的 id（注册名），拿不到时返回 "unknown"。 */
    public static String typeId(Iota iota) {
        return safe(() -> {
            ResourceLocation rl = IXplatAbstractions.INSTANCE.getIotaTypeRegistry().getKey(iota.getType());
            return rl == null ? "unknown" : rl.toString();
        });
    }

    /** 类型颜色 {@code #rrggbb}（IotaType#color，按 tag 计算）。 */
    public static String color(Iota iota) {
        int rgb = safeInt(() -> iota.getType().color(), 0xFFFFFF);
        return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF);
    }

    /**
     * 结构化表示：{@code {"type", "text", "rich", "color", "children"?}}；
     * 实体 iota 另外带 {@code "entity": {"type","name","pos":[x,y,z],"uuid"}}。
     * {@code rich} 是 display() 的带样式分段（实体用修正后的纯文本代替）。
     */
    public static JsonObject toJson(Iota iota) {
        JsonObject out = new JsonObject();
        out.addProperty("type", typeId(iota));
        out.addProperty("text", text(iota));
        out.addProperty("color", color(iota));

        Component display;
        if (iota instanceof EntityIota || iota instanceof GarbageIota) {
            // 同本体：实体 AQUA；垃圾用暗灰（本体是乱码样式）
            display = Component.literal(text(iota)).withStyle(iota instanceof EntityIota
                ? net.minecraft.ChatFormatting.AQUA : net.minecraft.ChatFormatting.DARK_GRAY);
        } else {
            display = safeComponent(iota::display);
        }
        out.add("rich", rich(display));

        if (iota instanceof EntityIota ei) {
            Entity e = ei.getEntity();
            JsonObject ent = new JsonObject();
            ent.addProperty("type", safe(() -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()));
            ent.addProperty("name", safe(() -> e.getName().getString()));
            JsonArray pos = new JsonArray();
            Vec3 p = e.position();
            pos.add(p.x);
            pos.add(p.y);
            pos.add(p.z);
            ent.add("pos", pos);
            ent.addProperty("uuid", safe(() -> e.getUUID().toString()));
            out.add("entity", ent);
        }
        if (iota instanceof ListIota li) {
            JsonArray children = new JsonArray();
            for (Iota child : li.getList()) children.add(toJson(child));
            out.add("children", children);
        }
        return out;
    }

    /** 把 Component 拆成带样式的分段。 */
    public static JsonArray rich(Component component) {
        JsonArray arr = new JsonArray();
        component.visit((style, s) -> {
            if (!s.isEmpty()) {
                JsonObject seg = new JsonObject();
                seg.addProperty("text", s);
                TextColor c = style.getColor();
                if (c != null) seg.addProperty("color", String.format(Locale.ROOT, "#%06x", c.getValue() & 0xFFFFFF));
                if (style.isBold()) seg.addProperty("bold", true);
                if (style.isItalic()) seg.addProperty("italic", true);
                if (style.isUnderlined()) seg.addProperty("underlined", true);
                if (style.isObfuscated()) seg.addProperty("obfuscated", true);
                arr.add(seg);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return arr;
    }

    private static Component safeComponent(Supplier<Component> f) {
        try {
            Component c = f.get();
            return c == null ? Component.empty() : c;
        } catch (Throwable t) {
            return Component.literal("<" + t.getClass().getSimpleName() + ">");
        }
    }

    private static String safe(Supplier<?> f) {
        try {
            return String.valueOf(f.get());
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
    }

    private static int safeInt(Supplier<Integer> f, int dflt) {
        try {
            Integer v = f.get();
            return v == null ? dflt : v;
        } catch (Throwable t) {
            return dflt;
        }
    }
}
