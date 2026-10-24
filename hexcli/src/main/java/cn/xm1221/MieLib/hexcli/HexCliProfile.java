package cn.xm1221.MieLib.hexcli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HexCLI 的 JSON 配置（{@code <run>/hexcli/profiles/<name>.json}）。所有字段可选，缺省值见各字段。
 * 用 Gson 直接映射；media / ambit 既可以是字符串也可以是数字，所以保留为 {@link JsonElement}，由 {@link #mediaMode()} 等解析。
 */
public final class HexCliProfile {
    public @Nullable String description;
    /** 施法环境 id：auto（按施法者类型自动选）或已注册的环境 id（如 hexcli:staff，命名空间缺省为 hexcli）。 */
    public String env = "auto";
    /** 自由 JSON 对象，原样交给工厂（通过上下文的 envData）；由 Gson 直接映射。 */
    public @Nullable JsonObject envData;
    public Caster caster = new Caster();
    /** "infinite" | "inventory" | 数字（原始媒质单位，紫水晶粉 = 10000）。 */
    public @Nullable JsonElement media;
    /** "infinite" | "default" | 数字（以施法者位置为圆心的半径，格）。 */
    public @Nullable JsonElement ambit;
    public @Nullable Stack pigment;
    public @Nullable Sentinel sentinel;
    /** hexParse 代码，解析结果作为初始栈。 */
    public @Nullable String initialStack;
    /** hexParse 代码，第一个 iota 写进渡鸦之思。 */
    public @Nullable String ravenmind;
    public @Nullable World world;

    public static final class Caster {
        /** player | entity | select | none */
        public String type = "player";
        public String gamemode = "survival";
        public String dimension = "minecraft:overworld";
        public @Nullable double[] pos;
        public @Nullable Stack mainhand;
        public @Nullable Stack offhand;
        public List<Stack> inventory = new ArrayList<>();
        public boolean enlightened = true;
        /** hexParse 大型法术解锁：["all"]、[]（全部上锁）或具体 id；null = 不动。 */
        public @Nullable List<String> learnedGreatPatterns;
        public String entity = "minecraft:zombie";
        public @Nullable String nbt;
        public List<String> tags = new ArrayList<>();
        public @Nullable String selector;
    }

    public static final class Stack {
        public int slot = -1;
        public String id = "minecraft:air";
        public int count = 1;
        public @Nullable String nbt;

        public Stack() {
        }

        public Stack(String id, int count) {
            this.id = id;
            this.count = count;
        }
    }

    public static final class Sentinel {
        public double[] pos = {0, 64, 0};
        public String dimension = "minecraft:overworld";
        public boolean extendsRange = false;
    }

    public static final class World {
        public @Nullable Long time;
        public @Nullable String weather;
        public Map<String, String> gamerules = new LinkedHashMap<>();
        public List<SetBlock> setblocks = new ArrayList<>();
        public List<Structure> structures = new ArrayList<>();
        public List<Summon> summons = new ArrayList<>();
        public List<String> commands = new ArrayList<>();
    }

    public static final class SetBlock {
        public int[] pos = {0, 64, 0};
        public String block = "minecraft:stone";
    }

    public static final class Structure {
        public String id = "";
        public int[] pos = {0, 64, 0};
    }

    public static final class Summon {
        public String entity = "minecraft:pig";
        public @Nullable double[] pos;
        public @Nullable String nbt;
        public List<String> tags = new ArrayList<>();
    }

    // ---- media / ambit ----

    public enum Mode { INFINITE, INVENTORY, DEFAULT, NUMBER }

    public static Mode modeOf(@Nullable JsonElement e, Mode dflt) {
        if (e == null || e.isJsonNull()) return dflt;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return Mode.NUMBER;
        String s = e.getAsString().trim().toLowerCase();
        return switch (s) {
            case "infinite", "inf", "unlimited" -> Mode.INFINITE;
            case "inventory" -> Mode.INVENTORY;
            case "default", "normal" -> Mode.DEFAULT;
            default -> {
                try {
                    Double.parseDouble(s);
                    yield Mode.NUMBER;
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("expected \"infinite\" / \"inventory\" / \"default\" or a number, got \"" + s + "\"");
                }
            }
        };
    }

    public static double numberOf(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : Double.parseDouble(e.getAsString().trim());
    }
}
