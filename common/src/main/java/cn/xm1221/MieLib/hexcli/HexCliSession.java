package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.HexAPI;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.api.player.Sentinel;
import at.petrak.hexcasting.common.lib.HexItems;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import cn.xm1221.MieLib.hexcli.api.HexCliEnvContext;
import cn.xm1221.MieLib.hexcli.HexCliProfiles.ProfileException;
import cn.xm1221.MieLib.hexcli.remote.HexCliRemote;
import com.mojang.authlib.GameProfile;
import io.yukkuric.hexparse.hooks.GreatPatternUnlocker;
import io.yukkuric.hexparse.parsers.ParserMain;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 一个服务器一个会话：当前 profile、施法者（假玩家 / 任意生物 / 无）、跨命令保留的 {@link CastingImage}、媒质预算等。
 * 每次 run 按 profile 的 env（auto = 按施法者类型）通过 {@link HexCliEnvs} 新建环境 + {@link CastingVM}，只把 image 留下。
 */
public final class HexCliSession {
    public static final GameProfile PROFILE =
        new GameProfile(UUID.fromString("4e3c11ac-4ec1-4ec1-8e11-000000000001"), "HexCLI");

    private static @Nullable HexCliSession current;
    /** 正在由 HexCLI 命令执行的环境：CastObserver 据此区分 root / spawned（任何注册环境都适用）。 */
    private static @Nullable CastingEnvironment rootEnv;

    static boolean isRootEnv(CastingEnvironment env) {
        return env == rootEnv;
    }

    public final MinecraftServer server;
    /** 假玩家：type=player 时就是施法者；任何时候都用它来跑 hexParse 解析。 */
    public ServerPlayer player;
    private ServerLevel level;
    private Vec3 anchor;
    private @Nullable LivingEntity caster;

    /** {@code hexcli env use <id>} 设置的覆盖；null = 跟随 profile。 */
    private @Nullable ResourceLocation envOverride;

    private String profileName = "(builtin)";
    private HexCliProfile profile = new HexCliProfile();
    private CompoundTag initialImage = new CastingImage().serializeToNbt();
    private CastingImage image = new CastingImage();

    private HexCliProfile.Mode mediaMode = HexCliProfile.Mode.INFINITE;
    private long mediaBudget, mediaRemaining;
    private HexCliProfile.Mode ambitMode = HexCliProfile.Mode.INFINITE;
    private double ambitRadius;
    private boolean enlightened = true;
    private @Nullable FrozenPigment pigment;

    /** 本次 run 里法术打印出的文字。 */
    public final List<String> printed = new ArrayList<>();

    private HexCliSession(MinecraftServer server) {
        this.server = server;
        this.level = server.overworld();
        this.anchor = Vec3.atBottomCenterOf(level.getSharedSpawnPos());
        this.player = newPlayer(level, anchor, GameType.SURVIVAL);
        this.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(HexItems.STAFF_OAK));
        this.caster = player;
    }

    public static HexCliSession get(MinecraftServer server) {
        if (current == null || current.server != server) current = new HexCliSession(server);
        return current;
    }

    public static @Nullable HexCliSession current() {
        return current;
    }

    static void drop() {
        current = null;
    }

    private static ServerPlayer newPlayer(ServerLevel level, Vec3 pos, GameType mode) {
        ServerPlayer p = HexCliPlatform.createFakePlayer(level, PROFILE);
        p.moveTo(pos.x, pos.y, pos.z, 0f, 0f);
        p.setGameMode(mode);
        p.setInvulnerable(true);
        return p;
    }

    // ---- 查询 ----

    public CastingImage image() {
        return image;
    }

    public String profileName() {
        return profileName;
    }

    public HexCliProfile profile() {
        return profile;
    }

    public @Nullable LivingEntity caster() {
        return caster;
    }

    public ServerLevel level() {
        return caster != null && caster.level() instanceof ServerLevel l ? l : level;
    }

    public Vec3 anchor() {
        return caster != null ? caster.position() : anchor;
    }

    public boolean enlightened() {
        return enlightened;
    }

    public HexCliProfile.Mode mediaMode() {
        return mediaMode;
    }

    public FrozenPigment pigment() {
        return pigment != null ? pigment : FrozenPigment.DEFAULT.get();
    }

    /** profile 里配置的颜料（没配为 null）。 */
    public @Nullable FrozenPigment profilePigment() {
        return pigment;
    }

    public static String describe(@Nullable Entity e) {
        if (e == null) return "none";
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        String what = e instanceof HexCliFakePlayer ? "fake player" : e instanceof ServerPlayer ? "player" : "entity";
        return what + " " + type + " \"" + e.getName().getString() + "\" " + e.getStringUUID().substring(0, 8)
            + (e.isRemoved() ? " (REMOVED)" : "");
    }

    public String mediaText() {
        return switch (mediaMode) {
            case INFINITE -> "infinite";
            case INVENTORY -> "inventory (env pays: player items/overcast, circle impetus)";
            default -> mediaRemaining + "/" + mediaBudget + " (" + String.format(Locale.ROOT, "%.2f", mediaRemaining / 10000.0) + " dust)";
        };
    }

    public String ambitText() {
        return switch (ambitMode) {
            case INFINITE -> "infinite";
            case NUMBER -> ambitRadius + " blocks";
            default -> "default";
        };
    }

    // ---- 环境回调 ----

    /** 预算扣费：返回没付上的部分（≤0 表示付清）。 */
    public long takeMedia(long cost, boolean simulate) {
        if (mediaMode == HexCliProfile.Mode.INFINITE || mediaMode == HexCliProfile.Mode.INVENTORY) return 0;
        long take = Math.min(Math.max(cost, 0), mediaRemaining);
        if (!simulate) mediaRemaining -= take;
        return cost - take;
    }

    /** 施法范围：true/false，或 null 表示用环境自己的默认规则。 */
    public @Nullable Boolean ambitCheck(Vec3 center, Vec3 vec) {
        return switch (ambitMode) {
            case INFINITE -> true;
            case NUMBER -> center.distanceToSqr(vec) <= ambitRadius * ambitRadius;
            default -> null;
        };
    }

    public void print(Component message) {
        String text = message.getString();
        HexCli.LOGGER.info("[HexCLI] print: {}", text);
        printed.add(text);
        // 远程客户端：只推送 run 之外（延迟 / 派生施法）的打印；run 内的打印随回复返回
        if (rootEnv == null) HexCliRemote.pushPrint(text);
    }

    /** 取出并清空假玩家收集到的系统消息（hexParse 提示、法术 chat 输出）。 */
    public List<String> drainMessages() {
        return player instanceof HexCliFakePlayer fake ? fake.drainMessages() : List.of();
    }

    // ---- 运行时修改 ----

    public void setMedia(@Nullable Long budget) {
        if (budget == null) {
            mediaMode = HexCliProfile.Mode.INFINITE;
        } else {
            mediaMode = HexCliProfile.Mode.NUMBER;
            mediaBudget = mediaRemaining = budget;
        }
    }

    public void setCasterPlayer() {
        caster = player;
    }

    public void setCasterNone() {
        anchor = anchor();
        level = level();
        caster = null;
    }

    public void setCaster(LivingEntity e) {
        caster = e;
        if (e.level() instanceof ServerLevel l) level = l;
    }

    public void reset() {
        image = CastingImage.loadFromNbt(initialImage.copy(), level());
    }

    // ---- profile ----

    /** 读盘 + 应用 profile，返回摘要行。出错抛 {@link ProfileException}（此时会话保持原状或部分应用，消息说明原因）。 */
    public List<String> applyProfile(String name) {
        HexCliProfile p = HexCliProfiles.load(server, name);
        HexCliProfile.Caster c = p.caster;
        List<String> out = new ArrayList<>();

        // 先把所有可能出错的东西解析好
        ResourceLocation dimId = HexCliProfiles.id(c.dimension, "dimension");
        ServerLevel lvl = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
        if (lvl == null) throw new ProfileException("unknown dimension '" + c.dimension + "'");
        Vec3 pos = HexCliProfiles.vec(c.pos, Vec3.atBottomCenterOf(lvl.getSharedSpawnPos()), "caster.pos");
        String type = c.type == null ? "player" : c.type.toLowerCase(Locale.ROOT);
        if (!List.of("player", "entity", "select", "none").contains(type))
            throw new ProfileException("caster.type must be player/entity/select/none, got '" + c.type + "'");
        GameType gm = GameType.byName(c.gamemode == null ? "survival" : c.gamemode.toLowerCase(Locale.ROOT), null);
        if (gm == null) throw new ProfileException("caster.gamemode must be survival/creative/adventure/spectator, got '" + c.gamemode + "'");
        ItemStack main = c.mainhand != null ? HexCliProfiles.stack(c.mainhand, "caster.mainhand")
            : type.equals("player") ? new ItemStack(HexItems.STAFF_OAK) : ItemStack.EMPTY;
        ItemStack off = HexCliProfiles.stack(c.offhand, "caster.offhand");
        List<ItemStack> inv = new ArrayList<>();
        for (HexCliProfile.Stack s : c.inventory) inv.add(HexCliProfiles.stack(s, "caster.inventory"));
        ItemStack pigStack = HexCliProfiles.stack(p.pigment, "pigment");
        HexCliProfile.Mode mm, am;
        try {
            mm = HexCliProfile.modeOf(p.media, HexCliProfile.Mode.INFINITE);
        } catch (IllegalArgumentException e) {
            throw new ProfileException("media: " + e.getMessage());
        }
        try {
            am = HexCliProfile.modeOf(p.ambit, HexCliProfile.Mode.INFINITE);
        } catch (IllegalArgumentException e) {
            throw new ProfileException("ambit: " + e.getMessage());
        }
        if (am == HexCliProfile.Mode.INVENTORY) throw new ProfileException("ambit cannot be \"inventory\"");
        if (mm == HexCliProfile.Mode.DEFAULT) mm = HexCliProfile.Mode.INVENTORY; // default = 环境自己的规则
        if (!"auto".equals(p.env) && p.env != null) { // 未知 id 提前报错
            try {
                HexCliEnvs.requireKnown(HexCliEnvs.parseId(p.env));
            } catch (IllegalArgumentException e) {
                throw new ProfileException(e.getMessage());
            }
        }
        LivingEntity selected = null;
        if (type.equals("select")) {
            if (c.selector == null || c.selector.isBlank()) throw new ProfileException("caster.type=select needs caster.selector");
            selected = HexCli.selectOne(server, lvl, c.selector);
        }

        // 世界：清理旧实体、执行布置
        int killed = HexCliProfiles.killSpawned(server);
        if (killed > 0) out.add("[HexCLI] removed " + killed + " previously spawned entities");
        out.addAll(HexCliProfiles.applyWorld(server, lvl, pos, p.world));

        // 假玩家（始终重建：解析器要用，player 类型时也是施法者）
        ServerPlayer fp = newPlayer(lvl, pos, gm);
        if (type.equals("player")) {
            fp.setItemInHand(InteractionHand.MAIN_HAND, main);
            fp.setItemInHand(InteractionHand.OFF_HAND, off);
            for (int i = 0; i < c.inventory.size(); i++) {
                int slot = c.inventory.get(i).slot;
                if (slot < 0) fp.getInventory().add(inv.get(i));
                else if (slot >= fp.getInventory().getContainerSize())
                    throw new ProfileException("caster.inventory slot " + slot + " out of range");
                else fp.getInventory().setItem(slot, inv.get(i));
            }
        } else {
            fp.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(HexItems.STAFF_OAK));
        }
        player = fp;
        level = lvl;
        anchor = pos;
        LivingEntity newCaster = switch (type) {
            case "player" -> fp;
            case "none" -> null;
            case "select" -> selected;
            default -> {
                List<String> tags = new ArrayList<>(c.tags);
                tags.add(HexCliProfiles.CASTER_TAG);
                Entity e = HexCliProfiles.summon(lvl, c.entity, pos, c.nbt, tags);
                if (!(e instanceof LivingEntity le)) {
                    e.discard();
                    throw new ProfileException("caster.entity '" + c.entity + "' is not a living entity");
                }
                if (!main.isEmpty()) le.setItemInHand(InteractionHand.MAIN_HAND, main);
                if (!off.isEmpty()) le.setItemInHand(InteractionHand.OFF_HAND, off);
                yield le;
            }
        };
        caster = newCaster;

        // 启迪
        enlightened = c.enlightened;
        // 玩家（假玩家和选中的真实玩家都一样）：授予 / 撤销本体的 hexcasting:enlightenment 进度，
        // 本体 StaffCastEnv.isEnlightened 就看它，HexCLI 不改环境。
        // 无施法者的法术环在本体里总是启迪的（CircleCastEnv.isEnlightened），profile 的 enlightened 对它无效。
        if (newCaster == null && !c.enlightened)
            out.add("[HexCLI] warning: caster none uses CircleCastEnv, which is always enlightened; enlightened=false is ignored");
        // 假玩家总是设置（之后 `hexcli caster player` 也对）；选中的真实玩家也设置。
        for (ServerPlayer sp : newCaster instanceof ServerPlayer real && real != fp ? List.of(fp, real) : List.of(fp)) {
            String r = setAdvancement(sp, "enlightenment", c.enlightened);
            if (r != null) out.add("[HexCLI] warning: " + r);
        }

        // hexParse 大型法术解锁
        if (c.learnedGreatPatterns != null) {
            GreatPatternUnlocker un = GreatPatternUnlocker.get(server.overworld());
            un.clear();
            int n = 0;
            for (String g : c.learnedGreatPatterns) {
                if (g.equalsIgnoreCase("all")) n = un.unlockAll();
                else if (un.unlock(g)) n++;
                else out.add("[HexCLI] warning: hexParse does not know great pattern '" + g + "'");
            }
            un.setDirty();
            out.add("[HexCLI] hexParse great patterns unlocked: " + (c.learnedGreatPatterns.isEmpty() ? "none" : n));
        }

        // 媒质 / 范围
        if (mm == HexCliProfile.Mode.INVENTORY && newCaster == null)
            out.add("[HexCLI] note: media \"inventory\" with caster none = the test circle impetus' own media (a fresh impetus has none)");
        mediaMode = mm;
        mediaBudget = mediaRemaining = mm == HexCliProfile.Mode.NUMBER ? (long) HexCliProfile.numberOf(p.media) : 0;
        ambitMode = am;
        ambitRadius = am == HexCliProfile.Mode.NUMBER ? HexCliProfile.numberOf(p.ambit) : 0;

        // 颜料 / 哨卫
        pigment = pigStack.isEmpty() ? null : new FrozenPigment(pigStack, newCaster != null ? newCaster.getUUID() : fp.getUUID());
        if (newCaster instanceof ServerPlayer sp) {
            IXplatAbstractions.INSTANCE.setPigment(sp, pigment);
            if (p.sentinel != null) {
                ResourceKey<Level> sd = ResourceKey.create(Registries.DIMENSION, HexCliProfiles.id(p.sentinel.dimension, "sentinel dimension"));
                IXplatAbstractions.INSTANCE.setSentinel(sp, new Sentinel(p.sentinel.extendsRange,
                    HexCliProfiles.vec(p.sentinel.pos, pos, "sentinel.pos"), sd));
            } else {
                IXplatAbstractions.INSTANCE.setSentinel(sp, null);
            }
        } else if (p.sentinel != null) {
            out.add("[HexCLI] warning: sentinel only applies to player casters; ignored");
        }

        // 初始栈 / 渡鸦之思：和 run 一样用当前施法者 / 环境真正执行一遍（媒质临时视为无限），
        // 所以 "num_1 num_2" 得到的是 [1, 2] 而不是两个图案。
        profileName = name;
        profile = p;
        envOverride = null; // `profile use` 之后回到 profile.env
        CastingImage start = new CastingImage();
        if (p.initialStack != null && !p.initialStack.isBlank()) start = evalScratch(p.initialStack, "initialStack", start);
        CompoundTag img = start.serializeToNbt();
        CompoundTag userdata = img.getCompound(CastingImage.TAG_USERDATA);
        if (p.ravenmind != null && !p.ravenmind.isBlank()) {
            List<Iota> rm = evalScratch(p.ravenmind, "ravenmind", new CastingImage()).getStack();
            if (rm.isEmpty()) throw new ProfileException("ravenmind code left an empty stack");
            userdata.put(HexAPI.RAVENMIND_USERDATA, IotaType.serialize(rm.get(rm.size() - 1)));
        }
        img.put(CastingImage.TAG_USERDATA, userdata);
        initialImage = img;
        reset();

        String envId = effectiveEnvIdString();
        try {
            HexCliEnvs.resolve(effectiveEnvId(), caster);
        } catch (RuntimeException e) {
            envId = "? (" + e.getMessage() + ")";
        }
        out.add("[HexCLI] profile '" + name + "' applied: caster=" + describe(newCaster)
            + " env=" + envId + (effectiveIsAuto() ? " (auto)" : "")
            + " enlightened=" + enlightened + " media=" + mediaText() + " ambit=" + ambitText()
            + " stack=" + image.getStack().size() + (CastEvent.ravenmindOf(image) == null ? "" : " ravenmind=set"));
        if (newCaster == null) out.add("[HexCLI]   anchor " + fmt(anchor) + " in " + lvl.dimension().location());
        return out;
    }

    public static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "%.1f, %.1f, %.1f", v.x, v.y, v.z);
    }

    /** 在临时 image 上执行一段代码（媒质无限），返回结果 image；出事故则报错。 */
    private CastingImage evalScratch(String code, String what, CastingImage startImage) {
        List<Iota> iotas = parseOrThrow(code, what);
        HexCliProfile.Mode savedMode = mediaMode;
        CastingImage saved = image;
        mediaMode = HexCliProfile.Mode.INFINITE;
        image = startImage;
        try {
            CastEvent ev = run(iotas, createEnv());
            if (ev != null && !ev.mishaps.isEmpty())
                throw new ProfileException(what + " '" + code + "' mishapped: " + String.join("; ", ev.mishaps));
            return image.withResetEscape();
        } catch (IllegalArgumentException e) {
            throw new ProfileException(what + ": " + e.getMessage());
        } finally {
            image = saved;
            mediaMode = savedMode;
        }
    }

    private List<Iota> parseOrThrow(String code, String what) {
        try {
            return parse(code);
        } catch (Throwable t) {
            throw new ProfileException(what + ": hexParse failed on '" + code + "': " + t.getMessage());
        }
    }

    /** 授予 / 撤销某个 hexcasting 进度的全部条件；失败返回原因。 */
    private @Nullable String setAdvancement(ServerPlayer sp, String path, boolean grant) {
        Advancement adv = server.getAdvancements().getAdvancement(new ResourceLocation("hexcasting", path));
        if (adv == null) return "advancement hexcasting:" + path + " not found";
        try {
            AdvancementProgress prog = sp.getAdvancements().getOrStartProgress(adv);
            List<String> crs = new ArrayList<>();
            (grant ? prog.getRemainingCriteria() : prog.getCompletedCriteria()).forEach(crs::add);
            boolean changed = false;
            for (String cr : crs) {
                if (sp instanceof HexCliFakePlayer) {
                    // Fabric 的 PlayerAdvancementTrackerMixin 会拦掉 FakePlayer 的 award/revoke；
                    // 假玩家不需要奖励、通知、触发器，直接改进度对象（本体 isEnlightened 读的就是它）。
                    changed |= grant ? prog.grantProgress(cr) : prog.revokeProgress(cr);
                } else {
                    changed |= grant ? sp.getAdvancements().award(adv, cr) : sp.getAdvancements().revoke(adv, cr);
                }
            }
            boolean done = sp.getAdvancements().getOrStartProgress(adv).isDone();
            HexCli.LOGGER.debug("[HexCLI] advancement {} criteria={} changed={} done={}", path, crs, changed, done);
            return done == grant ? null : "hexcasting:" + path + " is still " + (done ? "done" : "not done");
        } catch (RuntimeException e) {
            return "could not " + (grant ? "grant" : "revoke") + " hexcasting:" + path + ": " + e;
        }
    }

    // ---- 执行 ----

    /** 当前生效的环境 id：{@code hexcli env use} 覆盖优先，否则用 profile.env（null = auto）。 */
    public @Nullable ResourceLocation effectiveEnvId() {
        return envOverride != null ? envOverride : HexCliEnvs.parseId(profile.env);
    }

    /** 当前生效环境是不是 auto（profile.env 为 auto/null 且无 env use 覆盖）。 */
    public boolean effectiveIsAuto() {
        return envOverride == null && ("auto".equals(profile.env) || profile.env == null);
    }

    /** 当前生效环境 id 的文本（含 auto），用于打印。 */
    public String effectiveEnvIdString() {
        ResourceLocation id = effectiveEnvId();
        return id == null ? "auto" : id.toString();
    }

    /** 设置本次会话的环境覆盖（{@code hexcli env use <id>}）；null = 回到 profile.env。 */
    public void setEnvOverride(@Nullable ResourceLocation id) {
        envOverride = id;
    }

    public @Nullable ResourceLocation envOverride() {
        return envOverride;
    }

    /** 解析当前生效的环境 id；失败抛 IllegalArgumentException（消息直接给用户看）。 */
    public ResourceLocation resolveEnv() {
        return HexCliEnvs.resolve(effectiveEnvId(), caster);
    }

    /** 本次施法的上下文；env id 已解析（未知 id / LIVING 有歧义时抛 IllegalArgumentException）。 */
    public HexCliEnvContext context() {
        // HexCliEnvContext 的构造器保证 envData 非 null 且是深拷贝
        return new HexCliEnvContext(level(), caster, anchor(), resolveEnv(), profile.envData, this, profile);
    }

    /** hexParse 文本 -> iota 列表。解析失败抛异常，由命令层兜住。 */
    public List<Iota> parse(String code) {
        CompoundTag tag = ParserMain.ParseCode(code, player);
        Iota iota = IotaType.deserialize(tag, level());
        List<Iota> out = new ArrayList<>();
        if (iota instanceof ListIota list) {
            for (Iota i : list.getList()) out.add(i);
        } else {
            out.add(iota);
        }
        return out;
    }

    /** 创建本次施法的环境；施法者失效 / env 不匹配时抛 IllegalArgumentException。 */
    public CastingEnvironment createEnv() {
        if (caster != null && (caster.isRemoved() || !caster.isAlive()) && caster != player)
            throw new IllegalArgumentException("caster " + describe(caster) + " is gone (dead/removed); pick another with `hexcli caster ...`");
        HexCliEnvContext ctx = context();
        ResourceLocation id = ctx.envId();
        CastingEnvironment env = HexCliEnvs.create(id, ctx);
        // 观察组件先挂（它要看到组件扣费之前的完整费用），再挂 HexCLI 的媒质 / 范围组件。
        // 只有这里创建的 root 环境会挂媒质 / 范围组件；派生施法（咒灵、法术环、延时……）只被 CastObserver 观察。
        if (env.getExtension(CastObserver.Probe.KEY) == null) env.addExtension(new CastObserver.Probe(env));
        HexCliComponents.attach(env, this, ctx.anchor());
        return env;
    }

    /** 执行一段 iota，返回这次 run 对应的 root 事件（由 {@link CastObserver} 记录）。 */
    public @Nullable CastEvent run(List<Iota> iotas, CastingEnvironment env) {
        printed.clear();
        CastEvent[] root = new CastEvent[1];
        Runnable unsubscribe = CastObserver.subscribe(ev -> {
            if (ev.root && root[0] == null) root[0] = ev;
        });
        rootEnv = env;
        try {
            CastingVM vm = new CastingVM(image, env);
            vm.queueExecuteAndWrapIotas(iotas, env.getWorld());
            // 和本体法杖一样：保留栈，清零已用操作数，避免跨命令累积撞上 op 上限
            image = vm.getImage().withOverriddenUsedOps(0);
        } finally {
            rootEnv = null;
            unsubscribe.run();
        }
        return root[0];
    }
}
