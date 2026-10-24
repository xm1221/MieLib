package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 全服施法观察器。
 * <p>
 * 基于本体的 {@link CastingEnvironment#addCreateEventListener}：任何 {@code CastingVM} 构造时都会触发，
 * 所以 HexCLI 自己的 VM、Hexal 小精灵、延时施法、法术环、附属自建的环境……都会被挂上一个
 * {@link Probe} 组件（PostExecution + PostCast + ExtractMedia.Pre），不需要环境注册表，也不用 mixin。
 * <p>
 * 每次 VM 跑完一轮（PostCast）记成一个 {@link CastEvent}：
 * root（HexCLI 命令发起的）由命令自己打印；spawned（服务器上其它所有施法）直接打到控制台日志。
 */
public final class CastObserver {
    private CastObserver() {
    }

    private static int nextId = 1;
    private static final List<Consumer<CastEvent>> listeners = new CopyOnWriteArrayList<>();
    private static volatile int lastActivityTick = -1;
    private static @Nullable MinecraftServer server;

    public static void init() {
        CastingEnvironment.addCreateEventListener((env, userData) -> {
            if (env.getExtension(Probe.KEY) == null) env.addExtension(new Probe(env));
        });
    }

    static void bindServer(@Nullable MinecraftServer s) {
        server = s;
    }

    /** 最近一次观察到施法活动的服务器 tick。 */
    public static int lastActivityTick() {
        return lastActivityTick;
    }

    /** 订阅已完成的施法事件，返回取消订阅的 Runnable。 */
    public static Runnable subscribe(Consumer<CastEvent> l) {
        listeners.add(l);
        return () -> listeners.remove(l);
    }

    private static int currentTick() {
        MinecraftServer s = server;
        return s == null ? -1 : s.getTickCount();
    }

    private static void markActivity() {
        lastActivityTick = currentTick();
    }

    private static void finish(CastEvent ev) {
        markActivity();
        if (!ev.root) {
            for (String line : ev.render()) HexCli.LOGGER.info(line);
        }
        for (Consumer<CastEvent> l : listeners) {
            try {
                l.accept(ev);
            } catch (Throwable t) {
                HexCli.LOGGER.error("HexCLI cast listener failed", t);
            }
        }
    }

    /** 挂在每个环境上的观察组件。同一个环境可能被多个 VM 复用，所以每次 PostCast 后重开一条事件。 */
    public static final class Probe implements CastingEnvironmentComponent.PostExecution,
        CastingEnvironmentComponent.PostCast, CastingEnvironmentComponent.ExtractMedia.Pre {

        public static final Key<Probe> KEY = new Key<>() {
        };

        private final CastingEnvironment env;
        private @Nullable CastEvent current;

        Probe(CastingEnvironment env) {
            this.env = env;
        }

        private CastEvent event() {
            if (current == null) current = CastEvent.begin(env, nextId++, currentTick());
            return current;
        }

        @Override
        public Key<?> getKey() {
            return KEY;
        }

        @Override
        public void onPostExecution(CastResult result) {
            markActivity();
            CastEvent ev = event();
            ev.steps++;
            ev.lastResolution = result.getResolutionType();
            for (OperatorSideEffect fx : result.getSideEffects()) {
                if (fx instanceof OperatorSideEffect.DoMishap m) {
                    String msg;
                    try {
                        Component c = m.getMishap().errorMessageWithName(env, m.getErrorCtx());
                        msg = c == null ? null : c.getString();
                    } catch (Throwable t) {
                        msg = null;
                    }
                    ev.mishaps.add(msg != null ? msg : m.getMishap().getClass().getSimpleName());
                }
            }
        }

        @Override
        public long onExtractMedia(long cost, boolean simulate) {
            // Pre 阶段只记账不扣费：原样返回，交给环境自己的扣费逻辑。
            if (!simulate && cost > 0) event().mediaUsed += cost;
            return cost;
        }

        @Override
        public void onPostCast(CastingImage image) {
            CastEvent ev = event();
            current = null;
            ev.finish(image);
            finish(ev);
        }
    }
}
