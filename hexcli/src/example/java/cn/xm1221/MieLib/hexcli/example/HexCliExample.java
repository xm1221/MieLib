package cn.xm1221.MieLib.hexcli.example;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HexCLI 附属示例 mod 的入口（mod id {@code hexcli_example}，不会被发布）。
 * <p>
 * 这里演示 HexCLI 的“软依赖”写法：<b>本类不 import 任何 {@code cn.xm1221.MieLib.hexcli.*} 的东西</b>。
 * HexCLI 的类放在 {@link HexCliExampleCompat} 里，只有确认 hexcli 已加载时才去碰它——JVM 是懒加载的，
 * hexcli 不在时那个类永远不会被加载，所以不会出现 NoClassDefFoundError。{@code fabric.mod.json} 里
 * hexcli 只写在 {@code suggests}，不写 {@code depends}。
 * <p>
 * 启动：{@code ./gradlew :hexcli:runExampleServer}（普通 {@code :hexcli:runServer} 不带本示例）。
 */
public final class HexCliExample implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("hexcli_example");

    @Override
    public void onInitialize() {
        if (FabricLoader.getInstance().isModLoaded("hexcli")) {
            // 只有这一行会引用 HexCliExampleCompat；hexcli 不存在时不会执行，类也就不会被加载。
            HexCliExampleCompat.register();
        } else {
            LOGGER.info("[hexcli_example] hexcli not installed; skipping env registration");
        }
    }
}
