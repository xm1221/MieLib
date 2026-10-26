package cn.xm1221.MieLib.hexcli.api;

import cn.xm1221.MieLib.Mielib;
import cn.xm1221.MieLib.hexcli.HexCliEnvs;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * HexCLI 对附属公开的施法环境注册 API（稳定、跨 mod 调用）。
 * <p>
 * 附属在自己的 {@code onInitialize} 里调用 {@link #registerEnv(ResourceLocation, EnvSpec)} 注册环境，
 * HexCLI 在 profile 里用 {@code "env": "<id>"} 或命令 {@code hexcli env use <id>} 选用它；媒质 / 施法范围组件
 * 由 HexCLI 统一以扩展组件的形式挂上，对注册的环境一视同仁（见 {@code HexCliComponents}）。
 * <p>
 * 注册表是静态的、线程安全（注册与查询都是 {@code synchronized}）；内置环境在静态初始化块里登记，与各 mod 的 init
 * 顺序无关，所以附属在任意阶段注册都不会踩到顺序问题。
 * <p>
 * 软依赖写法（不要把 mielib 写进 {@code depends}，只写 {@code suggests}）：入口类里
 * {@code if (FabricLoader.getInstance().isModLoaded("mielib")) MyCompat.register();}
 * （Forge：{@code ModList.get().isLoaded("mielib")}；Architectury：{@code Platform.isModLoaded("mielib")}），
 * 把 {@code MyCompat} 放在单独类里、里面才 import 本包——JVM 只在 MieLib 存在时才加载那个类。
 * 完整例子见 {@code fabric/src/example}（mod id {@code hexcli_example}，用 {@code :fabric:runHexcliExampleServer} 启动）。
 * <p>
 * {@link #registerEnv} 只写入静态注册表，<b>与 HexCLI 是否启用无关</b>，所以附属可以无条件调用
 * （只要 MieLib 在）：注册的环境等 HexCLI 启用后自然可见。
 */
public final class HexCliAPI {
    private HexCliAPI() {
    }

    /** 内置环境 id 的命名空间（= MieLib 的 mod id）。 */
    public static final String NAMESPACE = Mielib.MODID;
    /** 保留 id：profile 里写 {@code "env": "auto"} 表示按施法者类型动态解析，不能注册。 */
    public static final ResourceLocation AUTO = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "auto");
    public static final ResourceLocation STAFF = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "staff");
    public static final ResourceLocation UNOWNED_CIRCLE = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "unowned_circle");
    public static final ResourceLocation PACKAGED = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "packaged");

    public static final ResourceLocation CIRCLE = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "circle");

    /**
     * 注册一个环境。重复 id 直接抛异常（带“已被谁注册”信息）；{@code mielib:auto} 是保留 id，不可注册。
     * 可以从任意 mod 的 {@code onInitialize} 调用，与 HexCLI 自己的初始化顺序无关，
     * 也不需要 HexCLI 已启用（只写静态注册表）。
     *
     * @throws IllegalArgumentException 重复 id，或尝试注册保留 id {@code mielib:auto}
     */
    public static void registerEnv(ResourceLocation id, EnvSpec spec) {
        HexCliEnvs.registerEnv(id, spec);
    }

    /** 所有已注册环境，按注册顺序（不可修改）。 */
    public static Map<ResourceLocation, EnvSpec> envs() {
        return HexCliEnvs.envs();
    }

    /** 查某个环境 id；没有返回 null。 */
    public static @Nullable EnvSpec env(ResourceLocation id) {
        return HexCliEnvs.env(id);
    }
}
