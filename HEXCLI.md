# HexCLI

> **1.21.1 branch:** HexCLI is **not compiled** into the mod. Hex Casting 0.12 removed
> `IotaType.deserialize` / `IotaType.serialize` / `IotaType.getDisplay` and `CastingImage.getUserData` /
> `serializeToNbt` (codecs and image components instead). The sources are still in
> `common/src/main/java/cn/xm1221/MieLib/hexcli` and the loader `hexcli` packages, and Gradle
> excludes that tree. `Mielib.init` does not call `HexCli.init`. Porting it is a follow-up;
> it does not block `CastingVMHook` or `DrawPatternHook`.

在**无头专用服务器**上用 [hexParse](https://modrinth.com/mod/hexparse) 语法敲 Hex 代码，直接在控制台看到执行结果。

HexCLI 现在是 **MieLib 的一部分**（`common` / `fabric` / `forge` 三个模块都有），不再是独立子项目、没有自己的
mod id、也不会另外出一个 jar——它随 MieLib 的 jar 一起发布。命令根仍然是 `hexcli`，profile 目录仍然是
`<run dir>/hexcli/profiles`，日志前缀仍然是 `[HexCLI]`；这些都是功能名，不是 mod id。

**默认关闭**，见下面的“启用”。

## 启用

HexCLI 需要 [hexParse](https://modrinth.com/mod/hexparse)（MieLib 对它是**软依赖**：不装也能正常用 MieLib，
只是 HexCLI 起不来）。

二选一，系统属性优先：

1. 启动参数：`-Dmielib.hexcli=true`（显式设置时优先；任何不是 `"true"` 的值都表示关闭，包括 `-Dmielib.hexcli=false`）。
2. MieLib 服务端配置：`config/mielib/server.toml` 里的 `enableHexCli = true`（默认 `false`；配置目录由
   Cloth AutoConfig 的 `PartitioningSerializer` 决定，文件名为 `<全局名>/<分区名>.toml`，即 `mielib/server.toml`）。

判定只在启动时做一次：

- 关闭 → 日志一行 `[HexCLI] disabled (enable with -Dmielib.hexcli=true or enableHexCli=true in the MieLib server config)`，
  不注册任何命令 / 事件，也不挂施法观察器；控制台的 `> code` 快捷写法同样不生效（原样交给原版命令处理）。
- 开启但没装 hexParse → `[HexCLI] enabled but hexParse is not installed; HexCLI stays off`，同样什么都不注册。
- 开启且 hexParse 在 → 正常注册。

## 开发运行

```bash
# 仓库根目录；gradlew 没有可执行位时用 bash gradlew
bash gradlew -Dorg.gradle.java.home=/path/to/jdk17 :fabric:runHexcliServer
bash gradlew -Dorg.gradle.java.home=/path/to/jdk17 :fabric:runHexcliExampleServer   # 额外带示例附属
bash gradlew -Dorg.gradle.java.home=/path/to/jdk17 :forge:runHexcliServer
```

三个运行目录都是对应模块下的 `run/hexcli`（`fabric/run/hexcli`、`forge/run/hexcli`），并且已经带上
`-Dmielib.hexcli=true`。首次运行会自动写 `eula.txt` 和 `server.properties`（离线模式、无出生点保护、`level-name=hexcli-world`）。
看到 `Done (...)! For help, type "help"` 后直接在终端输入命令。

示例附属（mod id `hexcli_example`）是 `fabric` 的 `example` source set：只在 `runHexcliExampleServer` 里加载，
**不会进任何 jar**。`./gradlew :fabric:build` 会编译它（保证不腐烂），但 jar / remapJar / shadowJar 只用 `main`。

## 命令（控制台或 OP 2 级）

| 命令 | 作用 |
|------|------|
| `hexcli run <code>` | 解析并执行一段 hexParse 代码 |
| `> <code>` | 控制台快捷写法，等价于 `hexcli run <code>`（HexCLI 启用时） |
| `hexcli stack` | 查看当前会话的栈和渡鸦之思 |
| `hexcli reset` | 恢复到当前 profile 的初始栈 / 渡鸦之思（清空括号状态） |
| `hexcli tick <n>` | 让服务器跑 n tick，汇总期间的 spawned 施法 |
| `hexcli wait [quiet=20] [timeout=600]` | 一直等到连续 quiet tick 没有任何施法（或超时） |
| `hexcli profile list` | 列出 `<run dir>/hexcli/profiles/*.json`，`*` 标出当前 profile |
| `hexcli profile use <name>` | 从磁盘重新读取并应用：世界布置、重建施法者、重置栈 / 媒质预算 |
| `hexcli profile show` | 当前 profile、施法者、环境 id（含是否 auto）、启迪、媒质余量、施法范围 |
| `hexcli env list` | 列出所有已注册环境（id、支持施法者、来源、说明），`*` 标出当前生效的那个 |
| `hexcli env use <id\|auto>` | 本次会话改用某个环境（`auto` = 跟随施法者类型）；`profile use` 会清掉这个覆盖 |
| `hexcli caster player` / `none` | 切回假玩家 / 无施法者（锚点留在当前位置） |
| `hexcli caster select <实体选择器>` | 让某个实体当施法者（玩家走 `mielib:staff`；非玩家生物走唯一一个声明支持 `LIVING` 的环境，没有就报清楚的错误） |
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

## 终端客户端

服务器端跑起来以后，可以用一个**独立的终端客户端**（`hexcli-client` 子项目，纯 Java 17，不是 mod、不进任何 mod jar）
连上去交互：带行编辑、历史、Tab 补全、彩色栈，以及 `stack`、`help` 之类的命令。协议是回环地址上的
换行分隔 JSON，所以客户端可以单独运行、单独打包。

### 启动

1. 用 HexCLI 打开服务器（见上面的“开发运行”）。服务器启动后会打印：

   ```
   [HexCLI] remote client endpoint 127.0.0.1:NNNNN (see hexcli/client.json)
   ```

   并在 `<run dir>/hexcli/client.json` 写入 `{"host","port","token","version"}`（端口是随机挑的，token 每次启动都变）。

2. 构建并运行客户端（仓库根目录）：

   ```bash
   # 打包成自带依赖的 jar
   ./gradlew :hexcli-client:fatJar
   # Linux / macOS
   java -jar hexcli-client/build/libs/hexcli-client-*-all.jar --dir fabric/run/hexcli
   ```

   Windows（`gradlew.bat`）：

   ```bat
   gradlew.bat :hexcli-client:fatJar
   java -jar hexcli-client\build\libs\hexcli-client-1.0-all.jar --dir fabric\run\hexcli
   ```

   PowerShell 里也可以写：

   ```powershell
   java -jar .\hexcli-client\build\libs\hexcli-client-1.0-all.jar --dir .\fabric\run\hexcli
   ```

   `--dir` 指向服务器目录（里面要有 `hexcli/client.json`；直接指向 `hexcli/` 也行）。不给 `--dir` 时依次找
   当前目录、`./run/hexcli`、`./fabric/run/hexcli`、`./forge/run/hexcli`。

   `./gradlew :hexcli-client:run` 也能跑，但它没有真正的终端（没有行编辑 / 历史 / 补全），日常用 `java -jar`。

### 选项

| 选项 | 作用 |
|------|------|
| `--dir <server dir>` | `client.json` 所在目录（默认见上） |
| `--host` / `--port` / `--token` | 覆盖 `client.json` 里的值（手动转发端口时用） |
| `-e, --exec <code>` | 跑一段代码、打印结果就退出；成功退出码 0，mishap / 错误退出码 1（脚本 / 冒烟测试用） |
| `-f, --file <path>` | 施放一个 `.hexparse` 文件后退出，等于 `-e "run --file <path>"` |
| `--no-color` | 关闭颜色（不是 tty 时也自动关闭） |
| `-h, --help` | 帮助 |

### 交互

一行的第一个词（区分大小写）如果是命令名，就当命令执行，其余部分是参数；否则整行作为 hexParse 代码发给服务器，按回车执行。
`()`、`[]`、`{}` 不平衡时自动续行（提示符变成 `...> `）。没有 `:` 前缀写法：以 `:` 开头的行也按代码发给 hexParse。
命令名优先于同名图案；要施放这种代码，用 `cast <code>`（或 `run <code>`），或者在 hexParse 里给图案起别名。
Tab 补全：行首补全命令名和图案名（服务器 action registry 里的名字，连上后拉一次），之后补全图案名，`env use` 补环境 id，
`profile use` 补 profile 名，`run` / `cast` 后面以 `-` 开头补选项，`--file` 后面补路径（只列目录和 `.hexparse` / `.hex` / `.txt`）。历史存在 `~/.hexcli_history`。

| 命令 | 作用 |
|------|------|
| `stack` / `reset` | 同 `hexcli stack` / `hexcli reset` |
| `env [list\|use <id>]` | 同 `hexcli env ...`（`use auto` 也行） |
| `profile [list\|show\|use <name>]` | 同 `hexcli profile ...` |
| `media <amount\|infinite>` | 同 `hexcli media ...` |
| `tick <n>` / `wait [quiet] [timeout]` | 同 `hexcli tick` / `hexcli wait`（异步：结束时才打印结果） |
| `cmd <hexcli 子命令>` | 执行任意 `hexcli ...`（例如 `cmd caster summon minecraft:zombie`、`cmd world summon ...`） |
| `run [选项] <code>` / `cast [选项] <code>` | 把后面的内容当代码施放；要施放第一个词正好是命令名的东西（比如叫 `stack` 的图案别名）就用它。选项见下 |
| `help` / `quit`（也接受 `q` / `exit` / Ctrl-D） | 帮助 / 退出 |

`--exec` 只接受代码：`--exec "cast <code>"` / `run <code>` 照常施放后面的内容（可以带 `--file`），第一个词是其它命令名、
或者带了 `--step`（需要交互）时报错并以退出码 2 退出。

#### 命令选项

通用约定：选项是命令名之后、正文之前的 `--xxx`；遇到第一个不以 `--` 开头的词就是正文，单独一个 `--` 表示后面全是正文
（正文本身以 `--` 开头时用）。不认识的选项会提示并且不执行。目前只有 `run` / `cast` 有选项：

| 选项 | 作用 |
|------|------|
| `--file <path>` | 把文件内容（UTF-8）当代码施放；路径相对客户端的当前目录，支持 `~/`，带空格的路径用双引号。约定扩展名 `.hexparse`，注释和换行由 hexParse 处理。文件不存在 / 为空会直接报错。和正文二选一 |
| `--step` | 单步：服务器把代码解析成顶层 iota，每步执行一个（Hermes / Thoth 之类算一步，里面的展开不单独停），用的是持久的会话栈 |

单步时每步先打印 `[i/N] <iota>`（图案显示 registry 名，数字等特殊图案显示处理器名），然后等按键：
回车执行这一步并打印栈，`c` 不再停、一直跑到底，`q` 中止（已经执行的部分留在栈上）。某一步出 mishap 就停下，mishap 只显示一次。
两个选项可以一起用：`run --step --file spell.hexparse`。示例文件见 [`docs/examples/hexcli/demo.hexparse`](docs/examples/hexcli/demo.hexparse)。

协议上是三个请求：`step.begin {code, label?}`（解析，回 `data.step = {total, index, done, preview}`）、`step.next`（执行下一个，
回复同 `run`，另带 `data.step`，出 mishap 时 `step.stopped = "mishap"`）、`step.abort`。单步状态在服务器上只有一份，`reset` 会清掉它。

运行结果：先一行 resolution（`EVALUATED` 绿色，`ERRORED` / `INVALID` 红色），然后栈从底到顶（顶上一个 `▶`），
每个 iota 按它的类型颜色上色；列表横排在一行里（`[1.00, [2.00, 3.00], (0.00, 1.00, 0.00)]`，括号按嵌套深度换色，不手动换行、由终端自己折行，超过 500 个元素时后面显示成 `… (+N)`）；太暗的颜色会自动提亮；垃圾 iota 显示为 `<garbage>`；
接着渡鸦之思、mishap（红色 `! step N: ...`，N 是本次执行里第几个 iota，每条只显示一次）、`print:`、消息、impetus、
用掉的媒质（灰色）。其它命令的文本行原样打印。终端声明真彩色（`COLORTERM=truecolor` 或 Windows Terminal）时用 24 位色，否则 256 色。

服务器上的**其它**施法（法术环、Hexal 小精灵、延时施法……）会以事件形式实时打印在当前提示符上方；
不在自己 `run` 里的法术 `print` 也会实时推送（`run` 里的随回复显示，不再推送）。

断线（服务器重启 / 端口变了）会打印一行提示，然后每 2 秒重试，每次都重新读 `client.json`（端口和 token 每次启动都不同）；
没连上时发请求会打印 `not connected`。

### 协议概要

回环地址上的 TCP，换行分隔 UTF-8 JSON（单行最大 1 MB）：

1. 客户端先发 `{"type":"hello","token":"..."}`。token 对 → `{"type":"hello","ok":true,"version":1,"profile":"default","env":"mielib:staff"}`；
   不对 → `{"type":"hello","ok":false,"error":"bad token"}` 并断开。
2. 请求 `{"id":<int>,"type":"...",...}` → 回复 `{"id":..,"type":"reply","ok":bool,"lines":[...],"data":{...}}`。
   类型：`run{code}`、`stack`、`reset`、`env.list`、`env.use{env}`、`profile.list`、`profile.show`、`profile.use{name}`、
   `media{amount}`、`tick{ticks}`、`wait{quiet?,timeout?}`、`command{line}`（跑 `hexcli <line>`）、`complete`、`ping`。
3. 服务器主动推送 `{"type":"event","event":"cast","cast":{...}}`（每一次非 root 施法）和
   `{"type":"event","event":"print","text":"..."}`。

`run` 的 `data` 里是结构化的施法记录（`cast` 含 `stack` / `ravenmind` / `mishaps` / `mediaUsed` 等，每个 iota 有
`type`、`text`、`color`、`rich` 分段，列表还有 `children`，实体有 `entity`），外加 `prints` / `messages` /
`parserMessages` / `impetus` / `media` / `error`。控制台输出和客户端看到的是同一套文本（实体 iota 显示成
`minecraft:zombie "Zombie" @ 3.5, 77.0, 0.5 #1a2b3c4d`，不再是 Inline 的占位符）。

### 安全

- 只绑定**回环地址**（`127.0.0.1` / `::1`），不对局域网开放；没有 TLS，适合本机调试。
- 每次启动生成 16 字节随机 token（hex），写在 `<server dir>/hexcli/client.json`（服务器停止时删除）；
  不知道 token 的连接会被立即断开。
- 所有游戏操作都提交到服务器线程执行，socket 线程只做 JSON 读写。
- 端口：`-Dmielib.hexcli.port=<n>` 或 MieLib 服务端配置 `hexCliPort`（默认 `0` = 每次启动自动挑空闲端口）。

## Profile（JSON）

服务器启动时会在 `<run dir>/hexcli/profiles/` 写出示例（已存在则不覆盖），并自动应用 `default`：

| 文件 | 内容 |
|------|------|
| `default.json` | 假玩家 + 橡木法杖，已启迪，hexParse 大型法术全部解锁，媒质 / 范围无限 |
| `unenlightened.json` | 同上但**未启迪**：大型法术能解析，但施放时 `MishapUnenlightened` |
| `nocaster.json` | 没有施法者：`mielib:circle`，在出生点自动搭一个真实的红石促动石法术环，用本体 `CircleCastEnv` 施法；1 粉预算，32 格范围 |
| `stacked.json` | 初始栈 `[1, 2]`、渡鸦之思 `42`，1 粉预算，本体默认施法范围，紫色颜料 |
| `packaged.json` | 主手 `hexcasting:cypher`，`env: "mielib:packaged"`（`envData.hand = "main"`），媒质 / 范围无限 |

所有字段都可省略：

```jsonc
{
  "description": "说明文字",
  "env": "auto",                       // auto（玩家 -> mielib:staff，无施法者 -> mielib:circle，其它生物 -> 唯一支持 LIVING 的环境）或注册的环境 id（裸路径 = mielib:）
  "envData": {"hand": "main"},         // 自由 JSON 对象，原样（深拷贝）交给该环境的工厂；mielib:packaged 用 "hand"
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
| `mielib:staff` | `StaffCastEnv(player, MAIN_HAND)` | `player` | 法杖施法 |
| `mielib:circle` | `CircleCastEnv(level, CircleExecutionState)` | `none` | 在锚点自动搭一个真实法术环（红石促动石朝东 + 3 块石板；已有则复用），`print` 写到促动石显示上 |
| `mielib:packaged` | `PackagedItemCastEnv(player, hand)` | `player` | 手里的 cypher / trinket / artifact；`envData.hand` = `"main"`（默认）/ `"off"`，手里不是打包类物品时报错并提示改 `caster.mainhand` |

内置环境的来源在 `hexcli env list` 里显示为 `mielib (MieLib)`。

`env: "auto"`（默认）的解析规则：

- 施法者是玩家（含假玩家）→ `mielib:staff`；
- 无施法者 → `mielib:circle`；
- 非玩家生物 → 在**所有声明支持 `LIVING` 的已注册环境**里挑：恰好一个就用它；一个都没有 → 报错并提示怎么注册；
  多于一个 → 报错要求显式选（`"env": "<id>"` 或 `hexcli env use <id>`），**不会**按加载顺序猜。

显式写的环境与当前施法者不匹配时，工厂调用前就会报错，例如
`env mielib:staff requires caster player, current caster is entity minecraft:zombie "Zombie" ... (LIVING); try ...`。
id 可以写裸路径（`staff` = `mielib:staff`）；`auto` / 空 = 自动。

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
- `HexCliAPI.envs()`（注册顺序、不可修改）、`env(id)`、常量 `AUTO / STAFF / CIRCLE / PACKAGED / NAMESPACE`。
- 重复 id 抛 `IllegalArgumentException`（消息里带原注册者）；`mielib:auto` 是保留 id。
- **注册表是静态的，与 HexCLI 是否启用无关**：只要 MieLib 在，附属就可以在自己的 `onInitialize` 里无条件调用
  `registerEnv`；环境在 HexCLI 启用后自然可用。注册与 HexCLI 自己的初始化顺序也无关。

**软依赖写法**（把 mielib 写进 `suggests`，不要写进 `depends`）：入口类里只做判断，真正的 API 调用放在单独类里，
这样 MieLib 不在时那个类不会被 JVM 加载：

```java
public final class MyMod implements ModInitializer {
    public void onInitialize() {
        // Forge: ModList.get().isLoaded("mielib")；跨加载器：dev.architectury.platform.Platform.isModLoaded("mielib")
        if (FabricLoader.getInstance().isModLoaded("mielib")) MyModHexCliCompat.register();
    }
}
```

完整示例在 `fabric/src/example/`（mod id `hexcli_example`，不随 MieLib 发布），用
`bash gradlew -Dorg.gradle.java.home=/path/to/jdk17 :fabric:runHexcliExampleServer` 启动开发服务器 + 示例 mod；
普通 `:fabric:runHexcliServer` 不带它。示例注册了 `hexcli_example:mob`（`LIVING`），会打印收到的 `envData`，
并演示重复注册会被拒绝。测试：

```
hexcli caster summon minecraft:zombie   # 非玩家施法者
hexcli env list                         # auto 应解析到 hexcli_example:mob（唯一支持 LIVING 的环境）
hexcli run num_1 num_2 add              # 用示例的生物环境施法；环境构造时日志打印 envData，print 写到 [hexcli_example] mob: ...
```

注意：本体只给玩家注册了 `hexcasting:media_consumption` 属性，而 `CastingEnvironment.extractMedia` 对任何施法者都会读它，
非玩家生物当施法者时会抛 `Can't find attribute hexcasting:media_consumption`。示例用一个 mixin
（`LivingEntityAttributesMixin`，给 `LivingEntity.createLivingAttributes` 补上这个属性）绕过；在 MieLib 本体里给生物
补这个属性是后续单独的任务，附属自己的生物环境在那之前也需要同样处理。

## 原理

- 假玩家由平台模块提供（Fabric 的 `FakePlayer` / Forge 的 `FakePlayer` 子类，都实现 `HexCliFakePlayer`），
  UUID `4e3c11ac-...-000000000001`；它也始终用来跑 hexParse 解析。收集系统消息以便打印到控制台。
- 平台相关的东西只有三处：假玩家（`HexCliPlatform` + 各平台的 `HexCliPlatformImpl`）、mod 名查询
  （Architectury `Platform.getOptionalMod`）、控制台 `> code` 改写的 mixin（`common/.../mixin/hexcli/DedicatedServerConsoleMixin`，
  注册在 `mielib-common.mixins.json` 的 `server` 段；只在 `HexCli.isActive()` 时改写）。其余全在 `common`。
- 每次 run 按 profile 的 `env` 新建环境 + `CastingVM`，只保留 `CastingImage`，所以栈和渡鸦之思跨命令保留。
- 观察用的 `CastObserver.Probe` 在 root 环境上先于媒质组件添加，所以“media used”是组件处理前的完整花费。
- `CastObserver` 用 `CastingEnvironment.addCreateEventListener` 给**服务器上每一个**施法环境挂 `PostExecution` / `ExtractMedia.Pre` / `PostCast` 组件：
  `/hexcli` 发起的记为 `root`，其余（法术环、Hexal 小精灵、HexOP 延迟施法、卡带等）记为 `spawned`，打印事件 id、环境类、施法者、位置、栈、mishap、媒质。

## 限制

- 法术环每块石板是一次独立的 `CircleCastEnv` 执行，所以一个环会产生多条 spawned 事件。
- Hexal / HexOP 等附属没有在开发环境里加载过，它们的施法理论上会被观察到（走的是本体的创建事件），但未实测。
- 没有内置实体施法环境（计划在 MieLib 本体实现）；`caster entity / summon / select 非玩家` 需要附属用
  `HexCliAPI.registerEnv` 注册一个支持 `LIVING` 的环境（示例见 `fabric/src/example`），或显式 `hexcli env use <id>`。
- `mielib:circle` 恒为已启迪；`media: inventory` 时由促动石付费（自动搭的环默认 0 媒质）。
- `mielib:staff` 的方块编辑权限是本体的真实规则（开发运行的 `server.properties` 默认 spawn-protection=0）。
- 没有真实客户端连上测过 `caster select` 选中真实玩家的路径（启迪进度授予 / 撤销、`media: inventory`）。
- Forge 侧的假玩家 / 命令 / 观察器与 Fabric 共用同一套 common 代码，但 Forge 开发运行未做过完整的手工回归。