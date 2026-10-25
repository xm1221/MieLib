package cn.xm1221.MieLib.hexcli;

import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.MishapDisallowedSpell;
import at.petrak.hexcasting.api.casting.mishaps.MishapEvalTooMuch;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidPattern;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia;
import at.petrak.hexcasting.api.casting.mishaps.MishapStackSize;

/**
 * HexOP 的 Delay's Gambit 在 delay≤0 的同步递归里会 {@code catch (StackOverflowError)} 再
 * {@code new MishapEvalTooMuch()}。若此时 {@link Mishap}（或其子类）还没完成 {@code <clinit>}，
 * JVM 会在 SOE 恢复路径上初始化基类；失败后 {@code Mishap} 对本 JVM 永久
 * {@link NoClassDefFoundError}（"Could not initialize class …Mishap"），之后一切 mishap 全挂。
 * <p>
 * {@link Mishap} 的 {@code <clinit>} 本身只 new Companion，没有重依赖——真正的问题是
 * 「SOE 期间首次类初始化」这一 JVM 行为，不是 HexCLI 专用环境的 init bug。
 * 所以在 {@code SERVER_STARTED}、栈还健康时先把基类和常用子类加载完。
 */
public final class MishapPreload {
    private MishapPreload() {
    }

    /** 在服务器线程、{@code SERVER_STARTED} 时调用一次。 */
    public static void preload() {
        try {
            // 1) 基类必须先就绪——子类 NCDFE 的根往往是 Mishap 自身被毒化
            Class.forName(Mishap.class.getName(), true, Mishap.class.getClassLoader());
            if (Mishap.Companion == null) {
                throw new IllegalStateException("Mishap.Companion is null after class init");
            }
        } catch (Throwable t) {
            HexCli.LOGGER.error("[HexCLI] FAILED to preload base Mishap (mishaps will break after SOE): {}", t.toString());
            return;
        }

        Class<?>[] subclasses = {
            MishapEvalTooMuch.class,
            MishapDisallowedSpell.class,
            MishapNotEnoughArgs.class,
            MishapInvalidIota.class,
            MishapInvalidPattern.class,
            MishapNotEnoughMedia.class,
            MishapStackSize.class,
        };
        int n = 1; // Mishap itself
        for (Class<?> c : subclasses) {
            try {
                Class.forName(c.getName(), true, c.getClassLoader());
                n++;
            } catch (Throwable t) {
                HexCli.LOGGER.warn("[HexCLI] could not preload {}: {}", c.getSimpleName(), t.toString());
            }
        }
        try {
            new MishapEvalTooMuch();
            new MishapStackSize();
        } catch (Throwable t) {
            HexCli.LOGGER.warn("[HexCLI] could not instantiate MishapEvalTooMuch/StackSize: {}", t.toString());
        }
        HexCli.LOGGER.info(
            "[HexCLI] preloaded Mishap + {} subclass(es) ({} total) so StackOverflowError recovery can throw mishaps",
            n - 1, n);
    }
}
