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
| `hexcli profile show` | 当前 profile、施法者、环境 id、启迪、媒质余量、施法范围 |
| `hexcli caster player` / `none` | 切回假玩家 / 无施法者（锚点留在当前位置） |
| `hexcli caster select <实体选择器>` | 让某个实体当施法者（玩家走 `hexcli:staff`，其它生物走 `hexcli:entity`） |
| `hexcli caster summon <实体> [nbt]` | 在锚点生成一个生物（带 `hexcli_spawned`、`hexcli_caster` 标签）并设为施法者 |
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
| `zombie.json` | NoAI 僵尸拿木棍施法，10 粉预算，16 格范围；夜晚 + 旁边生成一只带 `target` 标签的猪 |
| `nocaster.json` | 没有施法者（`getCastingEntity()` 为 null），锚点在出生点，32 格范围 |
| `stacked.json` | 初始栈 `[1, 2]`、渡鸦之思 `42`，1 粉预算，本体默认施法范围，紫色颜料 |

所有字段都可省略：

```jsonc
{
  "description": "说明文字",
  "env": "auto",                       // auto（玩家 -> hexcli:staff，其它 -> hexcli:entity）或注册的环境 id
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
    "entity": "minecraft:zombie",      // 仅 entity：生成的生物
    "nbt": "{NoAI:1b}",                // 仅 entity：SNBT
    "tags": ["my_tag"],                // 仅 entity：额外标签
    "selector": "@e[type=minecraft:allay,limit=1]"  // 仅 select：必须恰好选中一个生物
  },
  "media": "infinite",                 // infinite | inventory（仅玩家：真实物品 + 透支扣血）| 数字（预算，原始单位）
  "ambit": "infinite",                 // infinite | default（本体规则，含哨卫）| 数字（以施法者为圆心的半径）
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
- 启迪：HexCLI 假玩家直接用 `enlightened`（Fabric 的 FakePlayer 不记录进度）；`select` 选中的真实玩家会被授予 / 撤销
  本体的 `hexcasting:enlightenment` 进度；非玩家生物和无施法者由 `hexcli:entity` 环境直接返回该值。

### 施法环境

| id | 类 | 适用 |
|----|----|------|
| `hexcli:staff` | `HexCliCastEnv`（继承 `StaffCastEnv`） | 施法者是 ServerPlayer（假玩家或选中的玩家） |
| `hexcli:entity` | `HexCliEntityCastEnv`（直接继承 `CastingEnvironment`） | 任意 `LivingEntity` 或无施法者 |

环境通过 `HexCliEnvs.register(id, HexCliEnvFactory)` 注册（`HexCliEnvContext` 提供维度、施法者、锚点、profile、会话），
这是下一个里程碑给附属开放注册 API 的雏形。`hexcli:entity` 的要点：手持物品取生物的主 / 副手，事故作用在生物身上
（扣血、丢手上物品、失明），无施法者时全部无操作；本体的 `media_consumption` 属性只注册给了玩家，
所以非玩家扣费时跳过该倍率（否则所有耗媒质的图案都会抛异常）。

## 原理

- 默认施法者是一个 Fabric `FakePlayer` 子类（`HexCLI`，UUID `4e3c11ac-...-000000000001`）；它也始终用来跑 hexParse 解析。
- 每次 run 按 profile 的 `env` 新建环境 + `CastingVM`，只保留 `CastingImage`，所以栈和渡鸦之思跨命令保留。
- `CastObserver` 用 `CastingEnvironment.addCreateEventListener` 给**服务器上每一个**施法环境挂 `PostExecution` / `ExtractMedia.Pre` / `PostCast` 组件：
  `/hexcli` 发起的记为 `root`，其余（法术环、Hexal 小精灵、HexOP 延迟施法、卡带等）记为 `spawned`，打印事件 id、环境类、施法者、位置、栈、mishap、媒质。

## 限制

- 只有 Fabric；Forge 未做。
- 法术环每块石板是一次独立的 `CircleCastEnv` 执行，所以一个环会产生多条 spawned 事件。
- Hexal / HexOP 等附属没有在开发环境里加载过，它们的施法理论上会被观察到（走的是本体的创建事件），但未实测。
- 附属环境注册 API（公开、跨 mod 调用）留到下一个里程碑；目前只有内部的 `HexCliEnvs`。
- 没有真实客户端连上测过 `caster select` 选中真实玩家的路径（启迪进度授予 / 撤销、`media: inventory`）。
