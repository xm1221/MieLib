package cn.xm1221.MieLib.hexcli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** profile 文件读写 + 世界布置（原版命令 / 生成实体）+ 物品 / 实体等小工具。 */
public final class HexCliProfiles {
    public static final String SPAWNED_TAG = "hexcli_spawned";
    public static final String CASTER_TAG = "hexcli_caster";
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private HexCliProfiles() {
    }

    /** 配置出错：消息直接打印给用户。 */
    public static final class ProfileException extends RuntimeException {
        public ProfileException(String msg) {
            super(msg);
        }
    }

    public static Path dir(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve("hexcli").resolve("profiles");
    }

    public static List<String> list(MinecraftServer server) {
        Path d = dir(server);
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> s = Files.list(d)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                .map(n -> n.substring(0, n.length() - 5)).sorted().toList();
        } catch (IOException e) {
            throw new ProfileException("cannot list " + d + ": " + e.getMessage());
        }
    }

    public static HexCliProfile load(MinecraftServer server, String name) {
        if (!name.matches("[A-Za-z0-9_.\\-]+")) throw new ProfileException("bad profile name '" + name + "'");
        Path f = dir(server).resolve(name + ".json");
        if (!Files.isRegularFile(f))
            throw new ProfileException("unknown profile '" + name + "' (no " + f + "); known: " + String.join(", ", list(server)));
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            HexCliProfile p = GSON.fromJson(r, HexCliProfile.class);
            if (p == null) p = new HexCliProfile();
            if (p.caster == null) p.caster = new HexCliProfile.Caster();
            return p;
        } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
            throw new ProfileException("bad JSON in " + f + ": " + rootMessage(e));
        } catch (IOException e) {
            throw new ProfileException("cannot read " + f + ": " + e.getMessage());
        }
    }

    public static @Nullable String descriptionOf(MinecraftServer server, String name) {
        try {
            return load(server, name).description;
        } catch (ProfileException e) {
            return "(broken: " + e.getMessage() + ")";
        }
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage();
    }

    // ---- 示例 profile ----

    public static void writeExamples(MinecraftServer server) {
        Path d = dir(server);
        try {
            Files.createDirectories(d);
            for (Map.Entry<String, HexCliProfile> e : examples().entrySet()) {
                Path f = d.resolve(e.getKey() + ".json");
                if (!Files.exists(f)) Files.writeString(f, GSON.toJson(e.getValue()) + "\n", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            HexCli.LOGGER.error("[HexCLI] cannot write example profiles to {}: {}", d, e.getMessage());
        }
    }

    private static Map<String, HexCliProfile> examples() {
        Map<String, HexCliProfile> m = new LinkedHashMap<>();

        HexCliProfile def = new HexCliProfile();
        def.description = "fake player with an oak staff, enlightened, all great patterns unlocked, infinite media/ambit";
        def.caster.learnedGreatPatterns = List.of("all");
        def.media = new JsonPrimitive("infinite");
        def.ambit = new JsonPrimitive("infinite");
        m.put("default", def);

        HexCliProfile un = new HexCliProfile();
        un.description = "same fake player but NOT enlightened: great spells parse (unlocked) but mishap when cast";
        un.caster.enlightened = false;
        un.caster.learnedGreatPatterns = List.of("all");
        un.media = new JsonPrimitive("infinite");
        un.ambit = new JsonPrimitive("infinite");
        m.put("unenlightened", un);

        HexCliProfile z = new HexCliProfile();
        z.description = "a NoAI zombie holding a stick casts (night, pig target spawned next to it); 10 dust of media budget; ambit 16 blocks";
        z.caster.type = "entity";
        z.caster.entity = "minecraft:zombie";
        z.caster.nbt = "{NoAI:1b,PersistenceRequired:1b,Silent:1b}";
        z.caster.mainhand = new HexCliProfile.Stack("minecraft:stick", 1);
        z.caster.tags = List.of("hexcli_zombie");
        z.caster.enlightened = false;
        z.media = new JsonPrimitive(100000);
        z.ambit = new JsonPrimitive(16);
        HexCliProfile.World zw = new HexCliProfile.World();
        zw.time = 18000L; // 夜里，免得僵尸被太阳点着
        zw.weather = "clear";
        zw.gamerules.put("doDaylightCycle", "false");
        zw.gamerules.put("doMobSpawning", "false");
        HexCliProfile.Summon pig = new HexCliProfile.Summon();
        pig.entity = "minecraft:pig";
        pig.nbt = "{NoAI:1b}";
        pig.tags = List.of("target");
        zw.summons.add(pig);
        z.world = zw;
        m.put("zombie", z);

        HexCliProfile none = new HexCliProfile();
        none.description = "no caster at all (like a headless circle/wisp-less cast), anchored at world spawn";
        none.caster.type = "none";
        none.caster.enlightened = true;
        none.media = new JsonPrimitive("infinite");
        none.ambit = new JsonPrimitive(32);
        m.put("nocaster", none);

        HexCliProfile st = new HexCliProfile();
        st.description = "fake player starting with stack [1, 2] and ravenmind 42; 1 dust budget";
        st.caster.learnedGreatPatterns = List.of("all");
        st.initialStack = "num_1 num_2";
        st.ravenmind = "num_42";
        st.media = new JsonPrimitive(10000);
        st.ambit = new JsonPrimitive("default");
        HexCliProfile.Stack pg = new HexCliProfile.Stack("hexcasting:dye_colorizer_purple", 1);
        st.pigment = pg;
        m.put("stacked", st);
        return m;
    }

    // ---- 小工具 ----

    public static ResourceLocation id(String s, String what) {
        ResourceLocation rl = ResourceLocation.tryParse(s);
        if (rl == null) throw new ProfileException("bad " + what + " id '" + s + "'");
        return rl;
    }

    public static CompoundTag snbt(@Nullable String s, String what) {
        if (s == null || s.isBlank()) return new CompoundTag();
        try {
            return TagParser.parseTag(s);
        } catch (Exception e) {
            throw new ProfileException("bad SNBT for " + what + " '" + s + "': " + e.getMessage());
        }
    }

    public static ItemStack stack(@Nullable HexCliProfile.Stack s, String what) {
        if (s == null || s.id == null) return ItemStack.EMPTY;
        ResourceLocation rl = id(s.id, "item");
        Item item = BuiltInRegistries.ITEM.getOptional(rl)
            .orElseThrow(() -> new ProfileException("unknown item '" + s.id + "' in " + what));
        ItemStack st = new ItemStack(item, Math.max(1, s.count));
        if (s.nbt != null && !s.nbt.isBlank()) st.setTag(snbt(s.nbt, what));
        return st;
    }

    public static Vec3 vec(@Nullable double[] p, Vec3 dflt, String what) {
        if (p == null) return dflt;
        if (p.length != 3) throw new ProfileException(what + " must be [x, y, z]");
        return new Vec3(p[0], p[1], p[2]);
    }

    /** 和原版 /summon 一样生成实体；加上 hexcli_spawned 和额外标签。 */
    public static Entity summon(ServerLevel level, String entityId, Vec3 pos, @Nullable String nbt, List<String> tags) {
        ResourceLocation rl = id(entityId, "entity");
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(rl)
            .orElseThrow(() -> new ProfileException("unknown entity type '" + entityId + "'"));
        if (!type.canSummon()) throw new ProfileException("entity type '" + entityId + "' cannot be summoned");
        CompoundTag tag = snbt(nbt, "entity " + entityId).copy();
        tag.putString("id", rl.toString());
        Entity e = EntityType.loadEntityRecursive(tag, level, ent -> {
            ent.moveTo(pos.x, pos.y, pos.z, ent.getYRot(), ent.getXRot());
            return ent;
        });
        if (e == null) throw new ProfileException("failed to create entity '" + entityId + "' with nbt " + tag);
        if (e instanceof Mob mob && (nbt == null || nbt.isBlank()))
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()), MobSpawnType.COMMAND, null, null);
        e.addTag(SPAWNED_TAG);
        for (String t : tags) e.addTag(t);
        if (!level.tryAddFreshEntityWithPassengers(e))
            throw new ProfileException("could not add entity '" + entityId + "' (duplicate UUID?)");
        return e;
    }

    /** 清掉之前 HexCLI 生成的所有实体（所有维度）。 */
    public static int killSpawned(MinecraftServer server) {
        int n = 0;
        for (ServerLevel l : server.getAllLevels()) {
            List<Entity> victims = new ArrayList<>();
            for (Entity e : l.getAllEntities()) if (e.getTags().contains(SPAWNED_TAG)) victims.add(e);
            for (Entity e : victims) {
                e.discard();
                n++;
            }
        }
        return n;
    }

    // ---- 原版命令 ----

    /** 以控制台权限 4 在给定维度执行命令；失败时返回收集到的输出，成功返回 null。 */
    public static @Nullable String command(MinecraftServer server, ServerLevel level, String cmd) {
        List<String> msgs = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component c) {
                msgs.add(c.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return false;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }
            // sendSuccess 在 acceptsSuccess()=false 时不会到这里，所以 msgs 里只有失败消息

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack src = new CommandSourceStack(sink, Vec3.atLowerCornerOf(level.getSharedSpawnPos()), Vec2.ZERO,
            level, 4, "HexCLI", Component.literal("HexCLI"), server, null);
        String c = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        // 返回值没法判断成败（weather 返回 -1、gamerule false 返回 0），只看有没有失败消息
        server.getCommands().performPrefixedCommand(src, c);
        return msgs.isEmpty() ? null : String.join(" | ", msgs);
    }

    /** 应用 world 段；返回给用户看的结果行。 */
    public static List<String> applyWorld(MinecraftServer server, ServerLevel level, Vec3 anchor, @Nullable HexCliProfile.World w) {
        List<String> out = new ArrayList<>();
        if (w == null) return out;
        List<String> cmds = new ArrayList<>();
        if (w.time != null) cmds.add("time set " + w.time);
        if (w.weather != null) {
            String wt = w.weather.toLowerCase(Locale.ROOT);
            if (!List.of("clear", "rain", "thunder").contains(wt))
                throw new ProfileException("world.weather must be clear/rain/thunder, got '" + w.weather + "'");
            cmds.add("weather " + wt);
        }
        w.gamerules.forEach((k, v) -> cmds.add("gamerule " + k + " " + v));
        for (HexCliProfile.SetBlock sb : w.setblocks)
            cmds.add("setblock " + sb.pos[0] + " " + sb.pos[1] + " " + sb.pos[2] + " " + sb.block);
        for (HexCliProfile.Structure s : w.structures)
            cmds.add("place template " + s.id + " " + s.pos[0] + " " + s.pos[1] + " " + s.pos[2]);
        cmds.addAll(w.commands);
        int ok = 0;
        for (String c : cmds) {
            String err = command(server, level, c);
            if (err == null) ok++;
            else out.add("[HexCLI] world: `" + c + "` failed: " + err);
        }
        int summoned = 0;
        for (HexCliProfile.Summon s : w.summons) {
            try {
                summon(level, s.entity, vec(s.pos, anchor, "world.summons pos"), s.nbt, s.tags);
                summoned++;
            } catch (ProfileException e) {
                out.add("[HexCLI] world: summon " + s.entity + " failed: " + e.getMessage());
            }
        }
        out.add(0, "[HexCLI] world setup: " + ok + "/" + cmds.size() + " commands ok, " + summoned + "/" + w.summons.size() + " summoned");
        return out;
    }
}
