package cn.xm1221.MieLib.hexcli.example;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HexCLI 附属示例 mod 的入口（mod id {@code hexcli_example}，不会被发布）。
 * <p>
 * HexCLI 现在是 MieLib 的一部分（{@code mielib}），所以软依赖检查看的是 {@code mielib}。
 * 这里演示“软依赖”写法：<b>本类不 import 任何 {@code cn.xm1221.MieLib.hexcli.*} 的东西</b>。
 * HexCLI 的类放在 {@link HexCliExampleCompat} 里，只有确认 MieLib 已加载时才去碰它——JVM 是懒加载的，
 * MieLib 不在时那个类永远不会被加载，所以不会出现 NoClassDefFoundError。{@code fabric.mod.json} 里
 * mielib 只写在 {@code suggests}，不写 {@code depends}。
 * <p>
 * {@code HexCliAPI.registerEnv} 只写静态注册表，<b>与 HexCLI 是否启用无关</b>：MieLib 在就可以无条件注册，
 * 环境会在 HexCLI 启用（{@code -Dmielib.hexcli=true} 或服务端配置 {@code enableHexCli = true}）后可用。
 * <p>
 * 启动：{@code ./gradlew :fabric:runHexcliExampleServer}（普通 {@code :fabric:runHexcliServer} 不带本示例）。
 */
public final class HexCliExample implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("hexcli_example");

    @Override
    public void onInitialize() {
        if (FabricLoader.getInstance().isModLoaded("mielib")) {
            // 只有这一行会引用 HexCliExampleCompat；MieLib 不存在时不会执行，类也就不会被加载。
            HexCliExampleCompat.register();
        } else {
            LOGGER.info("[hexcli_example] MieLib not installed; skipping env registration");
        }
    }
}
