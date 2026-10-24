# HexCLI

在**无头专用服务器**上用 [hexParse](https://modrinth.com/mod/hexparse) 语法敲 Hex 代码，直接在控制台看到执行结果。
MieLib 仓库里的独立子项目：只做 Fabric，产物 `hexcli-fabric-*.jar`，**不会**打进 MieLib 的 jar。

## 启动

```bash
# 仓库根目录；gradlew 没有可执行位时用 bash gradlew
bash gradlew -Dorg.gradle.java.home=/path/to/jdk17 :hexcli:runServer
```

首次运行会在 `hexcli/run/` 自动写 `eula.txt` 和 `server.properties`（离线模式、无出生点保护）。
看到 `Done (...)! For help, type "help"` 后直接在终端输入命令。

## 命令（控制台或 OP 2 级）

| 命令 | 作用 |
|------|------|
| `hexcli run <code>` | 解析并执行一段 hexParse 代码 |
| `> <code>` | 控制台快捷写法，等价于 `hexcli run <code>` |
| `hexcli stack` | 查看当前会话的栈和渡鸦之思 |
| `hexcli reset` | 恢复到当前 profile 的初始栈 / 渡鸦之思（清空括号状态） |
| `hexcli tick <n>` | 让服务器跑 n tick，汇总期间的 spawned 施法 |
| `hexcli wait [quiet=20] [timeout=600]` | 一直等到连续 quiet tick 没有任何施法（或超时） |
| `hexcli profile list` | 列出 `hexcli/run/hexcli/profiles/*.json`，`*` 标出当前 profile |
| `hexcli profile use <name>` | 从磁盘重新读取并应用：世界布置、重建施法者、重置栈 / 媒质预算 |
| `hexcli profile show` | 当前 profile、施法者、环境 id（含是否 auto）、启迪、媒质余量、施法范围 |
| `hexcli env list` | 列出所有已注册环境（id、支持施法者、来源、说明），`*` 标出当前生效的那个 |
| `hexcli env use <id\|auto>` | 本次会话改用某个环境（`auto` = 跟随施法者类型）；`profile use` 会清掉这个覆盖 |
| `hexcli caster player` / `none` | 切回假玩家 / 无施法者（锚点留在当前位置） |
| `hexcli caster select <实体选择器>` | 让某个实体当施法者（玩家走 `hexcli:staff`；非玩家生物走唯一一个声明支持 `LIVING` 的环境，没有就报清楚的错误） |
| `hexcli caster summon <实体> [nbt]` | 在锚点生成一个生物（带 `hexcli_spawned`、`hexcli_caster` 标签）并设为施法者（同上） |
| `hexcli world summon <实体> <标签> [nbt]` | 在锚点生成法术目标（带 `hexcli_spawned` + 该标签） |
| `hexcli media <数量\|infinite>` | 运行时改媒质预算（紫水晶粉 = 10000） |

## 例子

```
> num_1 num_2 add          # 栈: 3.00
> num_42 write/local       # 渡鸦之思: 42.00
> read/local add           # 栈: 45.00
> add                      # 栈不够 -> mishap，会打印译文
hexcli wait 40 600
```

注意：hexParse 里裸数字 `1` 是**数字 iota**，直接执行会 mishap（“应执行图案，却执行了 1.00”）；
要用数字反射图案写 `num_1`。图案名用 Hex 的注册名路径（`write/local`，不是 `write_local`）；
hexParse 的“未知符号”提示会打印成 `[HexCLI] hexParse: ...`。

## Profile（JSON）

服务器启动时会在 `hexcli/run/hexcli/profiles/` 写出示例（已存在则不覆盖），并自动应用 `default`：

| 文件 | 内容 |
|------|------|
| `default.json` | 假玩家 + 橡木法杖，已启迪，hexParse 大型法术全部解锁，媒质 / 范围无限 |
| `unenlightened.json` | 同上但**未启迪**：大型法术能解析，但施放时 `MishapUnenlightened` |
| `nocaster.json` | 没有施法者：`hexcli:circle`，在出生点自动搭一个真实的红石促动石法术环，用本体 `CircleCastEnv` 施法；1 粉预算，32 格范围 |
| `stacked.json` | 初始栈 `[1, 2]`、渡鸦之思 `42`，1 粉预算，本体默认施法范围，紫色颜料 |
| `packaged.json` | 主手 `hexcasting:cypher`，`env: "hexcli:packaged"`（`envData.hand = "main"`），媒质 / 范围无限 |

所有字段都可省略：

```jsonc
{
  "description": "说明文字",
  "env": "auto",                       // auto（玩家 -> hexcli:staff，无施法者 -> hexcli:circle，其它生物 -> 唯一支持 LIVING 的环境）或注册的环境 id（裸路径 = hexcli:）
  "envData": {"hand": "main"},         // 自由 JSON 对象，原样（深拷贝）交给该环境的工厂；hexcli:packaged 用 "hand"
  "caster": {
    "type": "player",                  // player | entity | select | none
    "gamemode": "survival",            // 仅 player
    "dimension": "minecraft:overworld",
    "pos": [0.5, 65, 0.5],             // 默认：该维度出生点
    "mainhand": {"id": "hexcasting:staff/oak", "count": 1, "nbt": "{...}"},  // player 默认橡木法杖
    "offhand": null,
    "inventory": [{"slot": 0, "id": "hexcasting:amethyst_dust", "count": 64}],  // 仅 player；slot -1 = 随便放
    "enlightened": true,               // 是否已启迪（能否施放大型法术）
    "learnedGreatPatterns": ["all"],   // hexParse 能直接解析的大型法术：["all"]、[]（全锁）或具体 id；省略 = 不动
    "entity": "minecraft:zombie",      // 仅 entity：生成的生物（没有内置实体环境，需 "env" 指向附属注册的 LIVING 环境）
    "nbt": "{NoAI:1b}",                // 仅 entity：SNBT
    "tags": ["my_tag"],                // 仅 entity：额外标签
    "selector": "@e[type=minecraft:allay,limit=1]"  // 仅 select：必须恰好选中一个生物
  },
  "media": "infinite",                 // infinite | inventory / default（环境自己付：玩家物品 + 透支，法术环 = 促动石媒质）| 数字（预算，原始单位，不够的部分交给环境自己付）
  "ambit": "infinite",                 // infinite | default（环境自己的规则，含哨卫）| 数字（以施法者为圆心的半径，无施法者时以锚点为圆心）
  "pigment": {"id": "hexcasting:dye_colorizer_purple"},
  "sentinel": {"pos": [0, 70, 0], "dimension": "minecraft:overworld", "extendsRange": false},  // 仅玩家
  "initialStack": "num_1 num_2",       // hexParse 代码，用当前施法者执行一遍，结果栈作为初始栈
  "ravenmind": "num_42",               // hexParse 代码，执行后栈顶写进渡鸦之思
  "world": {
    "time": 6000, "weather": "clear",  // clear | rain | thunder
    "gamerules": {"doDaylightCycle": "false"},
    "setblocks": [{"pos": [0, 64, 3], "block": "minecraft:stone"}],
    "structures": [{"id": "minecraft:igloo/top", "pos": [10, 64, 10]}],   // 原版 place template
    "summons": [{"entity": "minecraft:pig", "pos": [2, 65, 2], "nbt": "{NoAI:1b}", "tags": ["target"]}],  // pos 默认锚点
    "commands": ["say hi"]             // 其它原版命令（权限 4，在施法者所在维度执行）
  }
}
```

- 每次应用 profile 前会先清掉所有带 `hexcli_spawned` 标签的实体，所以重复 `profile use` 是幂等的。
- 出错（JSON 语法、未知物品 / 实体 / 维度、坏 SNBT、选择器选中 0 个或多个、未知 profile 名……）只打印一行
  `[HexCLI] profile error: ...`，不会崩服。世界布置里某条命令失败会单独列出，其余照常执行。
- 启迪：本体 `StaffCastEnv` 通过 `hexcasting:enlightenment` 进度判断。假玩家的进度由 HexCLI 直接改 `AdvancementProgress`
  （Fabric 的 mixin 会拦下 FakePlayer 的 award / revoke）；`select` 选中的真实玩家正常授予 / 撤销。
  无施法者的 `CircleCastEnv` 本体规定恒为已启迪，`enlightened: false` 会被忽略并给出警告。

### 施法环境

HexCLI **不包装、不继承**任何施法环境：注册表里每个 id 对应一个工厂，每次 run 由工厂返回本体（或附属）的
真实环境对象；媒质 / 范围只通过扩展组件挂上去（见下）。

| id | 本体类 | 支持的施法者 | 说明 |
|----|--------|--------------|------|
| `hexcli:staff` | `StaffCastEnv(player, MAIN_HAND)` | `player` | 法杖施法 |
| `hexcli:circle` | `CircleCastEnv(level, CircleExecutionState)` | `none` | 在锚点自动搭一个真实法术环（红石促动石朝东 + 3 块石板；已有则复用），`print` 写到促动石显示上 |
| `hexcli:packaged` | `PackagedItemCastEnv(player, hand)` | `player` | 手里的 cypher / trinket / artifact；`envData.hand` = `"main"`（默认）/ `"off"`，手里不是打包类物品时报错并提示改 `caster.mainhand` |

`env: "auto"`（默认）的解析规则：

- 施法者是玩家（含假玩家）→ `hexcli:staff`；
- 无施法者 → `hexcli:circle`；
- 非玩家生物 → 在**所有声明支持 `LIVING` 的已注册环境**里挑：恰好一个就用它；一个都没有 → 报错并提示怎么注册；
  多于一个 → 报错要求显式选（`"env": "<id>"` 或 `hexcli env use <id>`），**不会**按加载顺序猜。

显式写的环境与当前施法者不匹配时，工厂调用前就会报错，例如
`env hexcli:staff requires caster player, current caster is entity minecraft:zombie "Zombie" ... (LIVING); try ...`。
id 可以写裸路径（`staff` = `hexcli:staff`）；`auto` / 空 = 自动。

**媒质 / 施法范围只通过扩展组件实现**（`HexCliComponents`，`env.addExtension`）：

- `ExtractMedia.Pre`：infinite → 返回 0；数字 → 从预算里扣，剩下的交给环境自己付；inventory / default → 原样返回。
  （本体先乘施法者的 `media_consumption` 属性，再按添加顺序跑 Pre 组件。）
- `IsVecInRange`：infinite → true；default → 环境自己的结果；数字 → 到施法者（无施法者时为锚点）的距离 ≤ 半径。

组件挂在**所有经注册表创建的 root 环境**上（包括附属注册的环境），派生出来的环境（法术环、咒灵、延时施法……）只被观察、不改规则。

### 环境注册 API

公开契约在 `cn.xm1221.MieLib.hexcli.api`（其余包都是内部实现）：

```java
HexCliAPI.registerEnv(
    new ResourceLocation("my_mod", "mob"),
    EnvSpec.builder(ctx -> new MyMobCastEnv(ctx.level(), (LivingEntity) ctx.caster(), ctx.envData()))
        .casters(CasterKind.LIVING)              // PLAYER / LIVING / NONE，至少一个；anyCaster() = 全部
        .description("my non-player caster env")
        .source("my_mod")                        // 可选，默认取 id 的命名空间
        .build());
```

- `CasterKind.of(entity)`：`null` → `NONE`，`ServerPlayer` → `PLAYER`，其余 `LivingEntity` → `LIVING`。
- `EnvSpec.accepts(caster)` / `castersText()`（如 `"player|living"`）；工厂返回 `null` 或抛非 `IllegalArgumentException`
  的异常都会被包装成清楚的错误；`IllegalArgumentException` 的消息原样打印。
- `HexCliEnvContext`：`level()`、`caster()`（可空）、`anchor()`、`envId()`（解析后的 id）、`envData()`（profile 里
  `"envData"` 对象的**深拷贝**，永远非 null）、`session()` / `profile()`（`@ApiStatus.Internal`）。
- `HexCliAPI.envs()`（注册顺序、不可修改）、`env(id)`、常量 `AUTO / STAFF / CIRCLE / PACKAGED / MOD_ID`。
- 重复 id 抛 `IllegalArgumentException`（消息里带原注册者）；`hexcli:auto` 是保留 id。注册表是静态的，
  可以在任意 mod 的 `onInitialize` 里注册，与 HexCLI 自己的初始化顺序无关。

**软依赖写法**（把 hexcli 写进 `suggests`，不要写进 `depends`）：入口类里只做判断，真正的 API 调用放在单独类里，
这样 hexcli 不在时那个类不会被 JVM 加载：

```java
public final class MyMod implements ModInitializer {
    public void onInitialize() {
        if (FabricLoader.getInstance().isModLoaded("hexcli")) MyModHexCliCompat.register();
    }
}
```

完整示例在 `hexcli/src/example/`（mod id `hexcli_example`，不随 hexcli 发布），用
`bash gradlew -Dorg.gradle.java.home=/path/to/jdk17 :hexcli:runExampleServer` 启动同一个开发服务器 + 示例 mod；
普通 `:hexcli:runServer` 不带它。示例注册了 `hexcli_example:mob`（`LIVING`），会打印收到的 `envData`，
并演示重复注册会被拒绝。测试：

```
hexcli caster summon minecraft:zombie   # 非玩家施法者
hexcli env list                         # auto 应解析到 hexcli_example:mob（唯一支持 LIVING 的环境）
hexcli run num_1 num_2 add              # 用示例的生物环境施法；环境构造时日志打印 envData，print 写到 [hexcli_example] mob: ...
```

注意：本体只给玩家注册了 `hexcasting:media_consumption` 属性，而 `CastingEnvironment.extractMedia` 对任何施法者都会读它，
非玩家生物当施法者时会抛 `Can't find attribute hexcasting:media_consumption`。示例用一个 mixin
（`LivingEntityAttributesMixin`，给 `LivingEntity.createLivingAttributes` 补上这个属性）绕过；真正的修复计划放在 MieLib 本体
（生物施法环境那项工作），附属自己的生物环境在那之前也需要同样处理。

Forge 还没有支持；思路相同（`ModList.get().isLoaded(...)` + 单独的 compat 类）。

> 计划：下一个里程碑会把 HexCLI 并入 MieLib 本体（common / fabric / forge），去掉独立的 `hexcli` mod id
> （环境 id 会变成 `mielib:...`，软依赖判断也改成判断 MieLib 并检查 HexCLI 是否启用），默认关闭、显式开启。
> 这里的 API 形状（`registerEnv` / `EnvSpec` / `CasterKind` / `HexCliEnvContext`）计划保持不变，只是包名 / id 跟着迁移。

## 原理

- 默认施法者是一个 Fabric `FakePlayer` 子类（`HexCLI`，UUID `4e3c11ac-...-000000000001`）；它也始终用来跑 hexParse 解析。
- 每次 run 按 profile 的 `env` 新建环境 + `CastingVM`，只保留 `CastingImage`，所以栈和渡鸦之思跨命令保留。
- 观察用的 `CastObserver.Probe` 在 root 环境上先于媒质组件添加，所以“media used”是组件处理前的完整花费。
- `CastObserver` 用 `CastingEnvironment.addCreateEventListener` 给**服务器上每一个**施法环境挂 `PostExecution` / `ExtractMedia.Pre` / `PostCast` 组件：
  `/hexcli` 发起的记为 `root`，其余（法术环、Hexal 小精灵、HexOP 延迟施法、卡带等）记为 `spawned`，打印事件 id、环境类、施法者、位置、栈、mishap、媒质。

## 限制

- 只有 Fabric；Forge 未做。
- 法术环每块石板是一次独立的 `CircleCastEnv` 执行，所以一个环会产生多条 spawned 事件。
- Hexal / HexOP 等附属没有在开发环境里加载过，它们的施法理论上会被观察到（走的是本体的创建事件），但未实测。
- 没有内置实体施法环境（计划在 MieLib 本体实现）；`caster entity / summon / select 非玩家` 需要附属用
  `HexCliAPI.registerEnv` 注册一个支持 `LIVING` 的环境（示例见 `src/example`），或显式 `hexcli env use <id>`。
- `hexcli:circle` 恒为已启迪；`media: inventory` 时由促动石付费（自动搭的环默认 0 媒质）。
- `hexcli:staff` 的方块编辑权限是本体的真实规则（`run/server.properties` 默认 spawn-protection=0）。
- 没有真实客户端连上测过 `caster select` 选中真实玩家的路径（启迪进度授予 / 撤销、`media: inventory`）。
