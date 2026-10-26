package cn.xm1221.MieLib.hexcli.remote;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import cn.xm1221.MieLib.hexcli.CastEvent;
import cn.xm1221.MieLib.hexcli.CastObserver;
import cn.xm1221.MieLib.hexcli.HexCliCircle;
import cn.xm1221.MieLib.hexcli.HexCliEnvs;
import cn.xm1221.MieLib.hexcli.HexCliIotas;
import cn.xm1221.MieLib.hexcli.HexCliProfile;
import cn.xm1221.MieLib.hexcli.HexCliProfiles;
import cn.xm1221.MieLib.hexcli.HexCliProfiles.ProfileException;
import cn.xm1221.MieLib.hexcli.HexCliSession;
import cn.xm1221.MieLib.hexcli.api.EnvSpec;
import cn.xm1221.MieLib.hexcli.api.HexCliAPI;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.platform.Platform;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * HexCLI 的实际操作：命令处理器和远程客户端服务器共用这一层。
 * <p>
 * 所有方法都要求在服务器线程上调用（远程服务器通过 {@code server.submit} 保证这一点），
 * 返回 {@link HexCliReply}（文本行 + 结构化数据 + 成败）。
 */
public final class HexCliOps {
    private static final Logger LOGGER = LoggerFactory.getLogger("HexCLI");

    private static final List<Waiter> waiters = new ArrayList<>();

    private HexCliOps() {
    }

    // ---- 运行代码 ----

    public static HexCliReply run(MinecraftServer server, String code) {
        HexCliSession session = HexCliSession.get(server);
        JsonObject data = newRunData(session);
        Parsed p = parse(session, code, data);
        if (p.fail() != null) return p.fail();
        return castIotas(session, code, p.iotas(), data);
    }

    private static JsonObject newRunData(HexCliSession session) {
        JsonObject data = new JsonObject();
        data.add("cast", JsonNull.INSTANCE);
        data.add("prints", new JsonArray());
        data.add("messages", new JsonArray());
        data.add("parserMessages", new JsonArray());
        data.add("impetus", JsonNull.INSTANCE);
        JsonObject media = new JsonObject();
        media.addProperty("mode", session.mediaMode().name().toLowerCase(Locale.ROOT));
        media.addProperty("text", session.mediaText());
        data.add("media", media);
        data.add("error", JsonNull.INSTANCE);

        return data;
    }

    private record Parsed(List<Iota> iotas, @Nullable HexCliReply fail) {
    }

    private static Parsed parse(HexCliSession session, String code, JsonObject data) {
        session.drainMessages();
        List<Iota> iotas;
        try {
            iotas = session.parse(code);
        } catch (Throwable t) {
            String msg = "parse error: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            LOGGER.debug("parse error", t);
            data.addProperty("error", msg);
            return new Parsed(List.of(), HexCliReply.fail(List.of("[HexCLI] " + msg), data));
        }
        List<String> parserMsgs = session.drainMessages();
        JsonArray parserJson = new JsonArray();
        if (!parserMsgs.isEmpty()) {
            List<String> pm = new ArrayList<>();
            for (String m : parserMsgs) {
                pm.add("[HexCLI] hexParse: " + m);
                parserJson.add(m);
            }
            data.add("parserMessages", parserJson);
        }
        if (iotas.isEmpty()) {
            String msg = "hexParse produced no iotas for: " + code;
            data.addProperty("error", msg);
            return new Parsed(List.of(), HexCliReply.fail(List.of("[HexCLI] " + msg), data));
        }
        return new Parsed(iotas, null);
    }

    // ---- 单步执行（run --step / cast --step） ----

    /** 当前单步会话（只在服务器线程上访问；同一时间一个，新的 begin 覆盖旧的）。 */
    private static @Nullable StepState stepState;

    private static final class StepState {
        final String code;
        final List<Iota> iotas;
        int next = 0;

        StepState(String code, List<Iota> iotas) {
            this.code = code;
            this.iotas = iotas;
        }
    }

    private static JsonObject stepInfo(HexCliSession session, StepState st, boolean done) {
        JsonObject o = new JsonObject();
        o.addProperty("total", st.iotas.size());
        o.addProperty("index", st.next); // 下一个要执行的（0 起）
        o.addProperty("done", done);
        if (!done && st.next < st.iotas.size()) {
            Iota nx = st.iotas.get(st.next);
            JsonObject pv = HexCliIotas.toJson(nx, session.level());
            pv.addProperty("text", previewText(session, nx));
            pv.remove("rich");      // 图案的 display() 是 Inline 占位，用预览文本代替
            pv.remove("children");
            o.add("preview", pv);
        }
        return o;
    }

    /** 预览文本：图案 iota 显示成 hexParse 名字 / 特殊处理器名字（本体 display() 是 Inline 占位）。 */
    private static String previewText(HexCliSession session, Iota iota) {
        if (iota instanceof at.petrak.hexcasting.api.casting.iota.PatternIota pi) {
            try {
                var m = at.petrak.hexcasting.common.casting.PatternRegistryManifest.matchPattern(
                    pi.getPattern(), session.createEnv());
                if (m instanceof at.petrak.hexcasting.api.casting.PatternShapeMatch.Normal n) return n.key.location().getPath();
                if (m instanceof at.petrak.hexcasting.api.casting.PatternShapeMatch.PerWorld w) return w.key.location().getPath();
                if (m instanceof at.petrak.hexcasting.api.casting.PatternShapeMatch.Special sp) return sp.handler.getName().getString();
            } catch (Throwable ignored) {
            }
            return "pattern " + pi.getPattern().getOrientation() + " " + pi.getPattern().getSignature().toAnglesString();
        }
        if (iota instanceof at.petrak.hexcasting.api.casting.iota.ListIota li) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Iota c : li.getList()) {
                if (!first) sb.append(", ");
                sb.append(previewText(session, c));
                first = false;
            }
            return sb.append("]").toString();
        }
        return HexCliIotas.text(iota, session.level());
    }

    /** 起步提示里的来源：客户端给的标签（如文件名），否则把代码压成一行并截断。 */
    private static String stepLabel(String code, String label) {
        if (label != null && !label.isBlank()) return label;
        String one = code.strip().replaceAll("\\s+", " ");
        return one.length() > 60 ? one.substring(0, 59) + "…" : one;
    }

    public static HexCliReply stepBegin(MinecraftServer server, String code, String label) {
        HexCliSession session = HexCliSession.get(server);
        JsonObject data = newRunData(session);
        Parsed p = parse(session, code, data);
        if (p.fail() != null) return p.fail();
        StepState st = new StepState(code, new ArrayList<>(p.iotas()));
        stepState = st;
        JsonObject info = stepInfo(session, st, false);
        JsonObject d = new JsonObject();
        d.add("step", info);
        d.add("parserMessages", data.get("parserMessages"));
        return new HexCliReply(List.of("[HexCLI] stepping " + st.iotas.size() + " top-level iota(s) of " + stepLabel(code, label)), d, true);
    }

    public static HexCliReply stepNext(MinecraftServer server) {
        HexCliSession session = HexCliSession.get(server);
        StepState st = stepState;
        if (st == null || st.next >= st.iotas.size()) {
            stepState = null;
            JsonObject d = new JsonObject();
            d.addProperty("error", "no step session (use run --step <code>)");
            return HexCliReply.fail(List.of("[HexCLI] no step session"), d);
        }
        int i = st.next;
        Iota iota = st.iotas.get(i);
        String label = "[" + (i + 1) + "/" + st.iotas.size() + "] " + previewText(session, iota);
        HexCliReply r = castIotas(session, label, List.of(iota), newRunData(session));
        st.next++;
        JsonObject data = r.data();
        // 单步时每次只执行一个 iota，事故里的 "step 1" 改成整段代码里的序号
        JsonObject cast = data.has("cast") && data.get("cast").isJsonObject() ? data.getAsJsonObject("cast") : null;
        if (cast != null && cast.has("mishaps")) {
            JsonArray fixed = new JsonArray();
            for (var m : cast.getAsJsonArray("mishaps")) fixed.add(m.getAsString().replaceFirst("^step \\d+: ", "step " + (i + 1) + ": "));
            cast.add("mishaps", fixed);
        }
        boolean stop = !r.ok() || st.next >= st.iotas.size();
        if (stop) stepState = null;
        JsonObject info = stepInfo(session, st, stop);
        info.addProperty("executed", i);
        if (!r.ok()) info.addProperty("stopped", "mishap");
        data.add("step", info);
        return new HexCliReply(r.lines(), data, r.ok());
    }

    public static HexCliReply stepAbort(MinecraftServer server) {
        StepState st = stepState;
        stepState = null;
        JsonObject d = new JsonObject();
        String msg = st == null ? "no step session" : "step session aborted at " + st.next + "/" + st.iotas.size() + " (stack kept)";
        d.addProperty("message", msg);
        return new HexCliReply(List.of("[HexCLI] " + msg), d, true);
    }

    /** 在会话的持久 image 上执行一组 iota（普通 run 和单步共用）。 */
    private static HexCliReply castIotas(HexCliSession session, String code, List<Iota> iotas, JsonObject data) {
        CastingEnvironment env;
        try {
            env = session.createEnv();
        } catch (IllegalArgumentException e) {
            String msg = "cannot cast: " + e.getMessage();
            data.addProperty("error", msg);
            return HexCliReply.fail(List.of("[HexCLI] " + msg), data);
        }
        try {
            CastEvent ev = session.run(iotas, env);
            List<String> out = new ArrayList<>();
            out.add("[HexCLI] > " + code + "  (" + iotas.size() + " iota" + (iotas.size() == 1 ? "" : "s") + ")");
            if (ev != null) {
                out.addAll(ev.render());
                data.add("cast", ev.toJson());
            } else {
                out.add("[HexCLI] (no root cast event observed)");
            }
            JsonArray prints = new JsonArray();
            for (String p : session.printed) {
                out.add("  printed: " + p);
                prints.add(p);
            }
            data.add("prints", prints);
            JsonArray messages = new JsonArray();
            for (String m : session.drainMessages()) {
                // 环境会把事故也发给施法者（假玩家）的聊天；cast 里已有，不再重复
                if (ev != null && ev.mishapsRaw.contains(m)) continue;
                out.add("  message: " + m);
                messages.add(m);
            }
            data.add("messages", messages);
            String disp = HexCliCircle.takeDisplay(env);
            if (disp != null) {
                out.add("  impetus: " + disp);
                data.addProperty("impetus", disp);
            }
            if (session.mediaMode() == HexCliProfile.Mode.NUMBER) out.add("  media left: " + session.mediaText());
            JsonObject mediaNow = new JsonObject();
            mediaNow.addProperty("mode", session.mediaMode().name().toLowerCase(Locale.ROOT));
            mediaNow.addProperty("text", session.mediaText());
            data.add("media", mediaNow);
            boolean ok = ev != null && ev.mishaps.isEmpty();
            return new HexCliReply(out, data, ok);
        } catch (Throwable t) {
            LOGGER.error("HexCLI run crashed", t);
            String msg = formatCrash(t);
            data.addProperty("error", msg);
            return HexCliReply.fail(List.of("[HexCLI] " + msg), data);
        }
    }

    /** 把施法崩溃收成客户端能读的一行。 */
    private static String formatCrash(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String detail = r.getClass().getSimpleName();
        if (r.getMessage() != null && !r.getMessage().isBlank()) detail += ": " + r.getMessage();
        if (r instanceof AbstractMethodError) {
            detail += " (Hex addon Kotlin default-method ABI mismatch; update Hex Casting / the addon)";
        } else if (r instanceof NoClassDefFoundError || r instanceof ExceptionInInitializerError
                || detail.contains("Could not initialize class")) {
            String msg = r.getMessage() == null ? "" : r.getMessage();
            if (msg.contains("Mishap") || detail.contains("Mishap")) {
                detail += " (base Mishap or a subclass failed <clinit> — usually the first "
                    + "StackOverflowError from HexOP Delay's Gambit delay-0 recursion hit class init. "
                    + "Restart the server; HexCLI preloads Mishap on start so this should not recur)";
            }
        } else if (r instanceof StackOverflowError) {
            detail += " (eval recursion too deep — e.g. HexOP mind_env/schedule with delay 0 calling itself)";
        }
        return "execution error: " + detail;
    }

    // ---- 会话状态 ----

    public static HexCliReply stack(MinecraftServer server) {
        CastingImage img = HexCliSession.get(server).image();
        List<String> out = new ArrayList<>();
        out.add("[HexCLI] session state");
        List<String> stack = new ArrayList<>();
        JsonArray stackJson = new JsonArray();
        ServerLevel sessionLevel = HexCliSession.get(server).level();
        for (Iota i : img.getStack()) {
            stack.add(HexCliIotas.text(i, sessionLevel));
            stackJson.add(HexCliIotas.toJson(i, sessionLevel));
        }
        CastEvent.renderStack(out, stack);
        String rm = CastEvent.ravenmindOf(img, HexCliSession.get(server).level());
        out.add("  ravenmind: " + (rm == null ? "(none)" : rm));
        JsonObject data = new JsonObject();
        data.add("stack", stackJson);
        data.addProperty("ravenmind", rm);
        if (img.getParenCount() > 0) {
            out.add("  open introspection depth: " + img.getParenCount());
            data.addProperty("parens", img.getParenCount());
        }
        return HexCliReply.ok(out, data);
    }

    public static HexCliReply reset(MinecraftServer server) {
        HexCliSession s = HexCliSession.get(server);
        s.reset();
        stepState = null;
        HexCliReply reply = HexCliReply.ok(List.of("[HexCLI] session reset to profile '" + s.profileName() + "' initial image (stack "
            + s.image().getStack().size() + ", ravenmind " + (CastEvent.ravenmindOf(s.image()) == null ? "none" : "set") + ")"));
        JsonObject data = new JsonObject();
        data.addProperty("profile", s.profileName());
        data.addProperty("stackSize", s.image().getStack().size());
        data.addProperty("ravenmind", CastEvent.ravenmindOf(s.image()) != null);
        return HexCliReply.ok(reply.lines(), data);
    }

    // ---- profile / env ----

    public static HexCliReply profileList(MinecraftServer server) {
        String active = HexCliSession.get(server).profileName();
        List<String> out = new ArrayList<>();
        List<String> names = HexCliProfiles.list(server);
        out.add("[HexCLI] profiles in " + HexCliProfiles.dir(server) + " (" + names.size() + ")");
        JsonArray arr = new JsonArray();
        for (String n : names) {
            String d = HexCliProfiles.descriptionOf(server, n);
            out.add((n.equals(active) ? "  * " : "    ") + n + (d == null ? "" : " - " + d));
            JsonObject p = new JsonObject();
            p.addProperty("name", n);
            p.addProperty("description", d);
            p.addProperty("active", n.equals(active));
            arr.add(p);
        }
        JsonObject data = new JsonObject();
        data.add("profiles", arr);
        data.addProperty("dir", HexCliProfiles.dir(server).toString());
        data.addProperty("active", active);
        return HexCliReply.ok(out, data);
    }

    public static HexCliReply profileShow(MinecraftServer server) {
        HexCliSession s = HexCliSession.get(server);
        List<String> out = new ArrayList<>();
        String env;
        try {
            env = HexCliEnvs.resolve(s.effectiveEnvId(), s.caster()) + (s.effectiveIsAuto() ? " (auto)" : "");
        } catch (RuntimeException e) {
            env = "? (" + e.getMessage() + ")";
        }
        out.add("[HexCLI] active profile '" + s.profileName() + "'" + (s.profile().description == null ? "" : ": " + s.profile().description));
        out.add("  caster: " + HexCliSession.describe(s.caster()) + " at " + HexCliSession.fmt(s.anchor()) + " in " + s.level().dimension().location());
        out.add("  env: " + env + "  enlightened: " + s.enlightened() + "  media: " + s.mediaText() + "  ambit: " + s.ambitText());
        out.add("  stack size: " + s.image().getStack().size() + "  registered envs: auto, "
            + HexCliAPI.envs().keySet().stream().map(ResourceLocation::toString).collect(Collectors.joining(", ")));
        JsonObject data = new JsonObject();
        data.addProperty("profile", s.profileName());
        data.addProperty("description", s.profile().description);
        data.addProperty("caster", HexCliSession.describe(s.caster()));
        data.addProperty("position", HexCliSession.fmt(s.anchor()));
        data.addProperty("dimension", s.level().dimension().location().toString());
        data.addProperty("env", env);
        data.addProperty("enlightened", s.enlightened());
        data.addProperty("media", s.mediaText());
        data.addProperty("ambit", s.ambitText());
        data.addProperty("stackSize", s.image().getStack().size());
        return HexCliReply.ok(out, data);
    }

    public static HexCliReply profileUse(MinecraftServer server, String name) {
        try {
            List<String> lines = HexCliSession.get(server).applyProfile(name);
            JsonObject data = new JsonObject();
            data.addProperty("profile", HexCliSession.get(server).profileName());
            return HexCliReply.ok(lines, data);
        } catch (ProfileException e) {
            return HexCliReply.fail(List.of("[HexCLI] profile error: " + e.getMessage()));
        } catch (RuntimeException e) {
            LOGGER.error("profile apply crashed", e);
            return HexCliReply.fail(List.of("[HexCLI] profile error: " + e));
        }
    }

    /** env list 的来源显示：spec.source 或 id 命名空间，能找到 mod 容器时带上 mod 名（走 Architectury Platform）。 */
    static String sourceLabel(ResourceLocation id, EnvSpec spec) {
        String src = spec.source() != null ? spec.source() : id.getNamespace();
        String name = Platform.getOptionalMod(src).map(m -> m.getName()).orElse(null);
        return name != null && !name.equals(src) ? src + " (" + name + ")" : src;
    }

    public static HexCliReply envList(MinecraftServer server) {
        HexCliSession s = HexCliSession.get(server);
        List<String> out = new ArrayList<>();
        out.add("[HexCLI] registered envs (auto: player->" + HexCliAPI.STAFF + ", none->" + HexCliAPI.UNOWNED_CIRCLE + ", "
            + "living->the only env accepting living):");
        ResourceLocation effective;
        try {
            effective = s.resolveEnv();
        } catch (RuntimeException e) {
            effective = null;
        }
        JsonArray arr = new JsonArray();
        for (Map.Entry<ResourceLocation, EnvSpec> e : HexCliAPI.envs().entrySet()) {
            ResourceLocation id = e.getKey();
            EnvSpec spec = e.getValue();
            out.add((id.equals(effective) ? "  * " : "    ") + id
                + "  casters=" + spec.castersText()
                + "  source=" + sourceLabel(id, spec)
                + "  - " + spec.description());
            JsonObject o = new JsonObject();
            o.addProperty("id", id.toString());
            o.addProperty("casters", spec.castersText());
            o.addProperty("source", sourceLabel(id, spec));
            o.addProperty("description", spec.description());
            o.addProperty("current", id.equals(effective));
            arr.add(o);
        }
        String cur = s.effectiveIsAuto() ? "auto" : s.effectiveEnvIdString();
        out.add("  current: " + cur + (effective == null ? " (not resolved now)" : " -> " + effective));
        JsonObject data = new JsonObject();
        data.add("envs", arr);
        data.addProperty("current", cur);
        data.addProperty("resolved", effective == null ? null : effective.toString());
        return HexCliReply.ok(out, data);
    }

    public static HexCliReply envUse(MinecraftServer server, @Nullable String raw) {
        HexCliSession s = HexCliSession.get(server);
        ResourceLocation id;
        try {
            id = HexCliEnvs.parseId(raw); // null = auto
            HexCliEnvs.requireKnown(id);
        } catch (IllegalArgumentException e) {
            return HexCliReply.fail(List.of("[HexCLI] env error: " + e.getMessage()));
        }
        s.setEnvOverride(id);
        String resolved;
        try {
            resolved = HexCliEnvs.resolve(id, s.caster()).toString();
        } catch (RuntimeException e) {
            resolved = "error: " + e.getMessage();
        }
        JsonObject data = new JsonObject();
        data.addProperty("env", s.effectiveEnvIdString());
        data.addProperty("resolved", resolved);
        return HexCliReply.ok(List.of("[HexCLI] env = " + s.effectiveEnvIdString() + " (resolved: " + resolved + ")"), data);
    }

    public static HexCliReply media(MinecraftServer server, String amount) {
        HexCliSession s = HexCliSession.get(server);
        if (amount.equalsIgnoreCase("infinite")) {
            s.setMedia(null);
        } else {
            try {
                long v = Long.parseLong(amount);
                if (v < 0) throw new NumberFormatException();
                s.setMedia(v);
            } catch (NumberFormatException e) {
                return HexCliReply.fail(List.of("[HexCLI] media must be 'infinite' or a non-negative integer (dust = 10000), got '" + amount + "'"));
            }
        }
        JsonObject data = new JsonObject();
        data.addProperty("mode", s.mediaMode().name().toLowerCase(Locale.ROOT));
        data.addProperty("text", s.mediaText());
        return HexCliReply.ok(List.of("[HexCLI] media = " + s.mediaText()), data);
    }

    // ---- 通过命令树执行（caster / world 等子命令） ----

    /** 以控制台权限执行 {@code hexcli <line>}，收集命令的成功 / 失败消息。 */
    public static HexCliReply command(MinecraftServer server, String line) {
        String l = line == null ? "" : line.strip();
        if (l.startsWith("/")) l = l.substring(1);
        if (l.startsWith("hexcli ")) l = l.substring("hexcli ".length());
        else if (l.equals("hexcli")) l = "";
        List<String> msgs = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component c) {
                msgs.add(c.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        ServerLevel level = server.overworld();
        CommandSourceStack src = new CommandSourceStack(sink, Vec3.atLowerCornerOf(level.getSharedSpawnPos()), Vec2.ZERO,
            level, 4, "HexCLI", Component.literal("HexCLI"), server, null);
        // 1.21 的 performPrefixedCommand 返回 void，命令自己的成功 / 失败消息已进 msgs，
        // 这里固定记 1（已执行）而不是 Brigadier 的返回值。
        int result = 1;
        try {
            server.getCommands().performPrefixedCommand(src, "hexcli " + l);
        } catch (Throwable t) {
            return HexCliReply.fail(List.of("[HexCLI] command error: " + t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
        JsonObject data = new JsonObject();
        data.addProperty("result", result);
        return new HexCliReply(msgs, data, true);
    }

    // ---- 补全 ----

    /** 图案（action registry 的 path 与完整 id）与客户端 meta 命令。 */
    public static HexCliReply complete(MinecraftServer server) {
        TreeSet<String> patterns = new TreeSet<>();
        TreeSet<String> fullIds = new TreeSet<>();
        try {
            var registry = IXplatAbstractions.INSTANCE.getActionRegistry();
            for (ResourceLocation rl : registry.keySet()) {
                patterns.add(rl.getPath());
                fullIds.add(rl.toString());
            }
        } catch (Throwable t) {
            LOGGER.warn("HexCLI: cannot enumerate patterns: {}", t.toString());
        }
        JsonObject data = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String p : patterns) arr.add(p);
        data.add("patterns", arr);
        JsonArray full = new JsonArray();
        for (String p : fullIds) full.add(p);
        data.add("patternIds", full);
        JsonArray cmds = new JsonArray();
        for (String c : List.of("run", "cast", "stack", "reset", "env", "env list", "env use", "profile",
            "profile list", "profile show", "profile use", "media", "tick", "wait", "cmd", "help", "quit")) {
            cmds.add(c);
        }
        data.add("commands", cmds);
        JsonArray envs = new JsonArray();
        envs.add("auto");
        for (ResourceLocation rl : HexCliAPI.envs().keySet()) envs.add(rl.toString());
        data.add("envs", envs);
        JsonArray profiles = new JsonArray();
        for (String n : HexCliProfiles.list(server)) profiles.add(n);
        data.add("profiles", profiles);
        return HexCliReply.ok(List.of(), data);
    }

    // ---- 让时间流逝：tick / wait ----

    /** 一个等待中的 tick / wait；结束时调用回调（在服务器线程上）。 */
    private static final class Waiter {
        final int startTick, timeout, quiet; // quiet < 0 表示固定 tick 数
        final Consumer<HexCliReply> done;
        final List<CastEvent> seen = new ArrayList<>();
        final Runnable unsubscribe;

        Waiter(int startTick, int timeout, int quiet, Consumer<HexCliReply> done) {
            this.startTick = startTick;
            this.timeout = timeout;
            this.quiet = quiet;
            this.done = done;
            this.unsubscribe = CastObserver.subscribe(ev -> {
                if (!ev.root) seen.add(ev);
            });
        }

        void close() {
            unsubscribe.run();
        }
    }

    /**
     * 启动一次等待：立即返回提示行，结束时（在服务器 tick 里）用最终结果调用 {@code completion}。
     * {@code quiet < 0} 表示固定跑 {@code timeout} tick。
     */
    public static HexCliReply wait(MinecraftServer server, int timeout, int quiet, Consumer<HexCliReply> completion) {
        waiters.add(new Waiter(server.getTickCount(), timeout, quiet, completion));
        return HexCliReply.ok(List.of(quiet < 0
            ? "[HexCLI] letting " + timeout + " ticks pass..."
            : "[HexCLI] waiting until no casts for " + quiet + " ticks (timeout " + timeout + ")..."));
    }

    /** 服务器停止时丢弃所有等待。 */
    public static void clearWaiters() {
        for (Waiter w : waiters) w.close();
        waiters.clear();
    }

    /** 每 tick 推进等待；由 {@link TickEvent#SERVER_POST} 调用。 */
    public static void tickWaiters(MinecraftServer server) {
        if (waiters.isEmpty()) return;
        int now = server.getTickCount();
        Iterator<Waiter> it = waiters.iterator();
        while (it.hasNext()) {
            Waiter w = it.next();
            int elapsed = now - w.startTick;
            int lastActivity = Math.max(CastObserver.lastActivityTick(), w.startTick);
            String reason;
            if (w.quiet >= 0 && now - lastActivity >= w.quiet) reason = "quiet for " + w.quiet + " ticks";
            else if (elapsed >= w.timeout) reason = w.quiet < 0 ? "done" : "TIMEOUT";
            else continue;
            it.remove();
            w.close();
            List<String> ids = w.seen.stream().map(e -> "#" + e.id).collect(Collectors.toList());
            int mishaps = w.seen.stream().mapToInt(e -> e.mishaps.size()).sum();
            String line = "[HexCLI] " + reason + " after " + elapsed + " ticks; spawned casts: "
                + w.seen.size() + " (" + (ids.isEmpty() ? "none" : String.join(" ", ids)) + "), mishaps: " + mishaps;
            JsonObject data = new JsonObject();
            data.addProperty("reason", reason);
            data.addProperty("elapsed", elapsed);
            data.addProperty("casts", w.seen.size());
            JsonArray arr = new JsonArray();
            for (String id : ids) arr.add(id);
            data.add("ids", arr);
            data.addProperty("mishaps", mishaps);
            try {
                w.done.accept(HexCliReply.ok(List.of(line), data));
            } catch (Throwable t) {
                LOGGER.error("HexCLI waiter callback failed", t);
            }
        }
    }
}
