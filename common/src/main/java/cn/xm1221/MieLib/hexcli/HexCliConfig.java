package cn.xm1221.MieLib.hexcli;

import cn.xm1221.MieLib.config.MielibServerConfig;
import dev.architectury.platform.Platform;

/**
 * HexCLI 的开关（默认关闭）。
 * <p>
 * 判定顺序：
 * <ol>
 *   <li>JVM 系统属性 {@code -Dmielib.hexcli=true|false}（显式设置时优先，非 {@code "true"} 一律视为关闭）；</li>
 *   <li>否则看 MieLib 服务端配置的 {@code enableHexCli}（{@code config/mielib/server.toml}，默认 false）。</li>
 * </ol>
 * 判定只在 {@link HexCli#init()} 里做一次，之后用 {@link HexCli#isActive()} 查询实际是否启用
 * （启用但 hexParse 未安装时也算未启用）。
 */
public final class HexCliConfig {
    /** 覆盖开关的系统属性名（启动参数 {@code -Dmielib.hexcli=true}）。 */
    public static final String PROPERTY = "mielib.hexcli";
    /** HexCLI 运行时依赖的 mod id（hexParse）。 */
    public static final String HEXPARSE_MOD_ID = "hexparse";

    private HexCliConfig() {
    }

    /** 按系统属性 / MieLib 服务端配置决定是否启用（不看 hexParse 是否安装）。 */
    public static boolean isEnabled() {
        String prop = System.getProperty(PROPERTY);
        if (prop != null) return prop.equals("true");
        try {
            return MielibServerConfig.getConfig().getEnableHexCli();
        } catch (RuntimeException | LinkageError e) {
            // 配置还没初始化（例如在 Mielib.init() 之前被调用）：按默认值处理
            return false;
        }
    }

    /** hexParse 是否已加载（HexCLI 的运行时硬依赖，但不是 MieLib 的依赖）。 */
    public static boolean isHexParseLoaded() {
        return Platform.isModLoaded(HEXPARSE_MOD_ID);
    }
}
