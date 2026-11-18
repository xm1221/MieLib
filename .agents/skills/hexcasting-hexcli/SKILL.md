---
name: hexcasting-hexcli
description: 用 HexCLI（MieLib 自带的无头 Hex Casting 服务器 + hexParse 语法）写咒、跑咒、单步调试、读栈和 mishap 时使用；包括安装/启动 HexCLI、用 hexcli-client 提交代码、查图案注册名、以及 MieHex / MieHex:Revolution 附属图案。
---

# 咒法学：用 HexCLI 写 / 跑 / 调试 Hex Casting 咒术

HexCLI 是 MieLib 里的一个功能（不是单独的 mod）：在**无头专用服务器**上用 [hexParse](https://modrinth.com/mod/hexparse) 文本语法
执行 Hex 代码，把结果（栈、渡鸦之思、mishap、媒质消耗）打印出来。**默认关闭**，需要 `-Dmielib.hexcli=true`（或配置）开启，
并且必须装 hexParse。配套终端客户端 `hexcli-client` 通过回环地址连上服务器，适合脚本化（`--exec` / `--file`，退出码可判定成败）。

下面所有示例输出都是在 MC 1.20.1 Fabric + 一键安装包 hexcli-v1.0.0（Hex Casting 0.11.4、hexParse 1.12.0、
MieLib 0.0.2.1）上真实跑出来的，用的是 `--no-color`。标了「未实测」的部分没有跑过。

## 1. 安装与启动

### 1.20.1 Fabric：一键安装包（推荐）

发布页：<https://github.com/xm1221/MieLib/releases/tag/hexcli-v1.0.0>
资产：`hexcli-1.0.0-linux.tar.gz` / `-macos.tar.gz` / `-windows.zip`、`mielib-fabric-0.0.2.1+1.20.1-SNAPSHOT.jar`、
`hexcli-manifest.json`、`SHA256SUMS`。压缩包里只有安装器、引导脚本、客户端 jar（`hexcli-client.jar`）——**没有** MC 服务端和 mod jar，
都由安装器下载并按清单 sha512 校验。

```bash
# Linux / macOS
curl -LO https://github.com/xm1221/MieLib/releases/download/hexcli-v1.0.0/hexcli-1.0.0-linux.tar.gz
tar xzf hexcli-1.0.0-linux.tar.gz          # 解压到当前目录（不带顶层文件夹）
chmod +x install.sh
./install.sh -y                             # -y = 同意 Minecraft EULA（或 HEXCLI_ACCEPT_EULA=1）
cd server
./start-and-connect.sh                      # 开服 -> 等 hexcli/client.json -> 连上客户端；退出客户端即关服
# 或分开：./start-server.sh（只开服，控制台可直接敲 hexcli 命令）+ ./connect.sh（只连客户端）
```

```bat
:: Windows：双击 install.bat，或
install.bat -y
powershell -ExecutionPolicy Bypass -File install.ps1 -y
cd server
start-and-connect.bat
```

安装器选项（`./install.sh --help` 全列；Windows ps1 用 `-Java` / `-Mirror` / `-GithubProxy` / `-UrlRewrite`）：

| 选项 / 环境变量 | 作用 |
|---|---|
| `-y` / `--yes` / `HEXCLI_ACCEPT_EULA=1` | 非交互同意 EULA |
| `--dir <目录>` | 服务端目录（默认脚本旁边的 `server`） |
| `--java <java 或 JDK 根目录>` / `HEXCLI_JAVA` | 用已有的 Java 17+；不存在或低于 17 直接报错 |
| `JAVA_HOME` | 是 17+ 才用，否则忽略 |
| （都没有） | 下载 Temurin JRE 17 到 `./runtime`（sha256 校验，不改系统 Java/PATH） |
| `--mirror official\|bmclapi`（别名 `cn`/`mcim`）/ `HEXCLI_MIRROR` | bmclapi：Modrinth→MCIM，Fabric meta/maven→BMCLAPI；失败默认回退官方 |
| `--github-proxy <前缀>` / `HEXCLI_GITHUB_PROXY` | GitHub 下载（MieLib jar、JRE）加代理前缀，如 `https://ghfast.top/` |
| `--url-rewrite 源前缀=新前缀`（可重复）/ `HEXCLI_URL_REWRITE`（`;` 分隔） | 自定义镜像 |
| `--adoptium-api <url>` / `HEXCLI_ADOPTIUM_API` | 换 JRE 的 Adoptium API |
| `--no-fallback` | 镜像失败不回退官方 |
| `--mod-override name=path` / `HEXCLI_MIELIB_JAR` | 用本地 jar 替换清单里的某个 mod（仍做哈希校验） |
| `HEXCLI_PROGRESS=0` | 关进度条 |

选中的 Java 写进 `server/java-path.txt`，`start-*.sh` / `connect.sh` 都读它。`start-server.sh` 实际执行的是
`java -Xmx2G -Dmielib.hexcli=true -jar fabric-server-launch.jar nogui`。
首次开服 Fabric 启动器会从 Mojang 下载原版服务端（这一步不走镜像）。重复运行 `install.sh` 安全（已校验文件跳过，
`eula.txt` / `server.properties` 不覆盖）。校验：`<java> -jar hexcli-installer.jar verify --dir server`。

安装包里的 mod（来自 `hexcli-manifest.json`）：mielib、fabric-api 0.87.0、fabric-language-kotlin 1.13.7、architectury 9.2.14、
hexcasting 0.11.4、paucal 0.6.0、patchouli 84、inline 1.2.2、cardinal-components-api 5.2.1、cloth-config 11.1.106、hexParse 1.12.0。
**不含 MieHex / Revolution**（见第 5 节怎么加）。

启动成功的标志（服务器日志）：

```
[HexCLI] hexParse great patterns unlocked: 15
[HexCLI] profile 'default' applied: caster=fake player minecraft:player "HexCLI" 4e3c11ac env=auto (auto) enlightened=true media=infinite ambit=infinite stack=0
[HexCLI] remote client endpoint 127.0.0.1:44531 (see hexcli/client.json)
```

### 1.21.1（MieLib 1.21 分支，Hex 0.12）—— 未实测

没有一键安装包，手动搭：Fabric 或 NeoForge 1.21.1 专用服务端，**Java 21**，mods 里放 MieLib 1.21 版（0.0.3.x+1.21.1）、
Hex Casting 0.12（`0.12.0-devel-pre-64` 起）、hexParse 1.21.1 版及它们的依赖；启动参数加 `-Dmielib.hexcli=true`。
开关逻辑与 1.20.1 相同（源码 `HexCliConfig.PROPERTY = "mielib.hexcli"`）：

1. JVM 属性 `-Dmielib.hexcli=true|false` 显式设置时优先；任何不是 `"true"` 的值都算关。
2. 否则看 `config/mielib/server.toml` 的 `enableHexCli = true`（默认 false）。
3. 端口：`-Dmielib.hexcli.port=<n>` 或配置 `hexCliPort`（默认 0 = 每次随机）。

客户端：从 MieLib 1.21 源码 `./gradlew :hexcli-client:fatJar` 构建（协议版本同为 1，理论上 1.20.1 包里的 `hexcli-client.jar` 也能连，未实测）。
1.21 下图案注册名基本沿用，但 profile 里物品 `nbt` 等字段在 1.21 的组件体系下可能不同——以实际 `profile show` / 报错为准。

日志里看到 `[HexCLI] disabled (...)` = 没开开关；`[HexCLI] enabled but hexParse is not installed; HexCLI stays off` = 缺 hexParse。

## 2. hexcli-client

```bash
cd server
JAVA=$(cat java-path.txt)
$JAVA -jar hexcli-client.jar --dir .                       # 交互（JLine：行编辑、历史 ~/.hexcli_history、Tab 补全图案名）
$JAVA -jar hexcli-client.jar --dir . -e "num_1 num_2 add"  # 跑一段就退出
$JAVA -jar hexcli-client.jar --dir . -f spell.hexparse     # 跑文件就退出（= -e "run --file spell.hexparse"）
```

- `--dir` 指向含 `hexcli/client.json` 的服务器目录（或 `hexcli/` 本身）。`client.json` = `{host, port, token, version}`，
  端口和 token **每次开服都变**，服务器停止时删除。只监听 127.0.0.1，`--host/--port/--token` 可手动覆盖。
- `--no-color` 关颜色（非 tty 自动关）。
- **退出码**：`--exec` / `--file` 成功 0；mishap 或连接失败 1；用法错误（REPL 命令、`--step` 给 `--exec`、未知选项、文件不存在）2。**hexParse 解析错误在还有剩余 iota 时退出码仍是 0**，见下面“三类错误”。
- `--exec` 只收代码。要跑 `reset` / `stack` / `profile use` 等命令，从 stdin 喂给 REPL：

```bash
printf 'reset\nnum_5 duplicate\nnum_7 swap\nstack\nquit\n' | $JAVA -jar hexcli-client.jar --dir . --no-color
```

REPL 命令（首词、区分大小写；其余整行当 hexParse 代码）：`stack`、`reset`、`env [list|use <id|auto>]`、
`profile [list|show|use <name>]`、`media <数量|infinite>`（紫水晶粉 = 10000）、`tick <n>`、`wait [quiet] [timeout]`、
`cmd <任意 hexcli 子命令>`、`run|cast [--step] [--file <path>] <code>`、`help`、`quit`/`q`/`exit`/Ctrl-D。
括号不平衡自动续行（提示符 `...> `）。首词撞上命令名时用 `cast <code>`。

服务器控制台也能直接用：`hexcli run <code>`、`> <code>`（快捷写法）、`hexcli stack`、`hexcli reset` 等。

### 读结果

```
cast #1  EVALUATED   steps 3  ops 3      <- 第几次施法、resolution、执行的 iota 数 / op 数
  [0] ▶ 3.00                             <- 栈，从底 [0] 到顶，顶上有 ▶
ravenmind: (none)                        <- 渡鸦之思
media used: 0 (0.00 dust)
media: infinite                          <- 剩余预算
```

resolution：`EVALUATED`（正常）、`ESCAPED`（以 Consideration 等结尾，也是正常）、`ERRORED`（图案执行中 mishap）、
`INVALID`（执行了非图案 iota / 未启迪等）。mishap 一行：`! step N: <图案名>: <译文>`，N 是本次第几个 iota。
`print`（Reveal）的内容显示为 `message: ...`；hexParse 不认识的符号显示为 `hexParse: Unknown symbol: xxx`。
实体 iota 显示为 `minecraft:player "HexCLI" @ x, y, z #uuid前8位`，图案 iota 显示为 `HexPattern[方向, 角度串]`，垃圾为 `<garbage>`。

**会话栈是持久的**：每次 `run` / `-e` 都接着上一次的栈和渡鸦之思跑，mishap 留下的 `<garbage>` 也会留着。
写测试时每段代码前先 `reset`（只能在 REPL / stdin 里），见第 6 节。

### 三类错误：解析错误 / HexCLI 错误 / mishap

先分清错误出在哪一层，处理方式完全不同。以下输出全部是在测试安装上实跑的原文（`--no-color`），`exit=` 是客户端退出码。

#### ① 解析错误（hexParse，执行之前）

hexParse 把文本转成 iota 时出的问题。**坏 token 被丢掉，不会被执行**，剩下的照常执行；只要还剩至少一个 iota，
`-e` 的退出码就是 0——脚本必须自己 grep `hexParse:` 开头的行。

```
$ java -jar hexcli-client.jar --dir . --no-color -e "num_1 frobnicate num_2 add"
hexParse: Unknown symbol: frobnicate
cast #1  EVALUATED   steps 3  ops 3
  [0] ▶ 3.00
ravenmind: (none)
media used: 0 (0.00 dust)
media: infinite
exit=0
```

整段都解析不出 iota 时不施法，退出码 1（注意这时**没有** `Unknown symbol` 那行）：

```
$ ... -e "frobnicate"
[HexCLI] hexParse produced no iotas for: frobnicate
exit=1
```

括号不平衡（实测，行为不统一，别依赖它）：

| 输入 | 实际结果 | exit |
|---|---|---|
| `( num_1 num_2`（缺 `)`） | 不报任何错；Introspection 一直没关，后面全被“收进括号”不执行：`cast #2  ESCAPED   steps 3  ops 1` / `(empty stack)` | 0 |
| `num_1 num_2 add )`（多 `)`） | 不是解析错误，`)` 被当成 Retrospection 执行 → mishap：`! step 4: Retrospection: Did not first use Introspection`，栈 `3.00`, `HexPattern[EAST, eee]` | 1 |
| `[1 2`（缺 `]`） | `hexParse: Error when parsing: 'java.lang.String net.minecraft.class_2477.method_4679(java.lang.String)'`，然后列表被自动闭合并执行 → `INVALID` `! step 1: Expected to evaluate a pattern, but evaluated [1.00, 2.00] instead` | 1 |
| `\ [1 [2 3] 4`（缺 `]`，前面有转义） | 同样的 `hexParse: Error when parsing: ...` 行，但列表被自动闭合后入栈：`ESCAPED` `[0] ▶ [1.00, [2.00, 3.00], 4.00]` | 0 |
| `1 2]`（多 `]`） | 同样的 `hexParse: Error when parsing: ...`，然后 `INVALID` `! step 1: Expected to evaluate a pattern, but evaluated 1.00 instead` | 1 |

（`Error when parsing` 后面那串是 hexParse 在专用服务器上取不到本地化文本时漏出来的混淆方法名，不是真正的原因；看到它就去查方括号。）
交互 REPL 里括号不平衡会自动续行（`...> `），不会立刻发出去。另外 `{ num_1 }` 被当作 `( … )` 接受（得到 `[HexPattern[SOUTH_EAST, aqaaw]]`）。

还有**静默误解析**，连 `hexParse:` 行都没有：`num_1.2.3` 变成了 `0.00`，`vec_a_b` 变成了向量 `(0.00, 0.00, 0.00)`
（`-e "num_1 num_2 add num_1.2.3 vec_a_b"` → 栈 `3.00`, `0.00`，然后 `INVALID ... evaluated (0.00, 0.00, 0.00) instead`，exit=1）。

#### ② HexCLI 自身的错误（Error，与咒术无关）

连接、参数、命令、配置问题。咒术根本没执行（或者执行的东西和咒术无关）。

客户端启动 / 连接（实测）：

| 情况 | 输出 | exit |
|---|---|---|
| 服务器没开 / 已正常停止（`client.json` 已删）/ `--dir` 指错 | `hexcli: no ./hexcli/client.json`（`--dir /tmp/nonexist` 时为 `hexcli: no /tmp/nonexist/hexcli/client.json`） | 1 |
| 端口不对（`--port 1`；或服务器被强杀后留下的过期 `client.json`） | `hexcli: Connection refused` | 1 |
| token 不对（`--token deadbeef`） | `hexcli: connection closed during handshake` | 1 |
| `--port abc` | `hexcli: bad --port 'abc'` + 帮助文本 | 2 |
| 未知选项 `--bogus` | `hexcli: unknown option '--bogus'` + 帮助文本 | 2 |
| `-e "stack"`（把命令交给 --exec） | `hexcli: --exec takes hex code; commands (stack) work in the REPL (e.g. pipe them via stdin)` | 2 |
| `-e "run --step num_1"` | `hexcli: --step is interactive; it cannot be used with --exec` | 2 |
| `-f nope.hexparse` | `hexcli: file not found: /<当前目录>/nope.hexparse` | 2 |
| 连着的时候服务器停了 | `[hexcli] connection lost; reconnecting every 2s...`；之后再发代码：`[hexcli] not connected` | REPL 不退出（`quit` 后 0） |

REPL 里的命令错误（实测；REPL 不因此退出，管道喂完 `quit` 后 exit=0）：

```
hex> profile use nope
[HexCLI] profile error: unknown profile 'nope' (no ./hexcli/profiles/nope.json); known: default, nocaster, packaged, stacked, unenlightened
hex> env use nope:thing
[HexCLI] env error: unknown HexCLI env 'nope:thing', known: auto, mielib:staff, mielib:unowned_circle, mielib:packaged, mielib:circle
hex> media abc
[HexCLI] media must be 'infinite' or a non-negative integer (dust = 10000), got 'abc'
hex> tick abc
[hexcli] error: not a number: abc
hex> wait abc
[hexcli] usage: wait [quiet] [timeout] (integers)
hex> run --bogus num_1
[hexcli] unknown option --bogus (run/cast take --step, --file <path>)  (usage: run [--step] [--file <path>] [code])
hex> run --file nope.hexparse
[hexcli] file not found: /<当前目录>/nope.hexparse
hex> profile frob
[hexcli] usage: profile [list|show|use <name>]
hex> env frob
[hexcli] usage: env [list|use <id>]
hex> cmd bogus
Incorrect argument for command
hexcli bogus<--[HERE]
hex> cmd caster select @e[type=minecraft:cow]
Only one entity is allowed, but the provided selector allows more than one
hexcli caster select @e[type=minecraft:cow]<--[HERE]
```

**没有“未知 REPL 命令”这种错误**：不是命令名的首词整行都当 hexParse 代码，所以敲错命令（`stak`）会变成解析错误
`[HexCLI] hexParse produced no iotas for: stak`。另外 `tick -5` 不报错（打印 `letting -5 ticks pass...` 然后 `done after 1 ticks`）。

功能开关 / 依赖（服务器日志，实测）：

- `-Dmielib.hexcli=false` 开服：`[HexCLI] disabled (enable with -Dmielib.hexcli=true or enableHexCli=true in the MieLib server config)`；
  不写 `client.json`，客户端报 `hexcli: no ./hexcli/client.json`，exit=1。
- 开了开关但 mods 里没有 hexParse：`[main/ERROR]: [HexCLI] enabled but hexParse is not installed; HexCLI stays off`；
  控制台敲 `hexcli run num_1` / `> num_1` 都是原版的 `Unknown or incomplete command, see below for error` + `hexcli run num_1<--[HERE]`。
  （开关关闭时控制台的这条输出没有单独测，按源码同样不注册命令。）

#### ③ Mishap（Hex Casting 执行期失败）

代码解析成功、开始执行后由 Hex Casting 本体抛出的失败：resolution 为 `ERRORED` / `INVALID`，有 `! step N: <图案>: <原因>` 行，
当次 cast 在该步停止（后面的 iota 不执行），栈上常留下 `<garbage>`，`-e` / `-f` 的 exit=1。

```
$ ... -e "add"
cast #8  ERRORED   steps 1  ops 0
  [0]   <garbage>
  [1] ▶ <garbage>
ravenmind: (none)
! step 1: Additive Distillation: expected 2 or more arguments but the stack was empty
media used: 0 (0.00 dust)
media: infinite
exit=1
```

更多实测 mishap（参数类型、媒质不足、超出范围、未启迪）见第 4 节。

#### 汇总

| 类别 | 长什么样 | 阶段 | exit（`-e`/`-f`） | 怎么处理 |
|---|---|---|---|---|
| ① 解析错误 | `hexParse: Unknown symbol: x`、`hexParse: Error when parsing: ...`、`[HexCLI] hexParse produced no iotas for: ...`；也可能完全没提示（缺 `)`、`num_1.2.3`） | 解析，执行前 | 有剩余 iota 时 **0**；全部无效时 1 | 脚本里 grep `^hexParse:` 和 `produced no iotas`；核对 steps 数和栈；检查括号和图案注册名 |
| ② HexCLI 错误 | `hexcli: ...`（客户端）、`[hexcli] ...` / `[HexCLI] ... error: ...`（REPL）、`[HexCLI] disabled` 等日志 | 连接 / 参数 / 命令 / 启动 | 连接失败 1；参数 / 用法错 2；REPL 内不退出 | 检查服务器是否在跑、`client.json`、`--dir`、开关和 hexParse；改命令或参数；与咒术无关 |
| ③ Mishap | `ERRORED` / `INVALID` + `! step N: ...`，栈上 `<garbage>` | 执行期 | **1** | 按 step N 定位那个图案；修正参数、媒质、范围、启迪；重跑前 `reset` 清掉 garbage |

### 单步 `--step`（实测，stdin 喂回车）

```
hex> run --step num_1 num_2 add
[HexCLI] stepping 3 top-level iota(s) of num_1 num_2 add
[1/3] Numerical Reflection: 1
step [Enter=next c=continue q=abort]> cast #24  EVALUATED   steps 1  ops 1
  [0] ▶ 1.00
...
[3/3] add
step [Enter=next c=continue q=abort]> cast #26  EVALUATED   steps 1  ops 1
  [0] ▶ 3.00
[hexcli] step session finished
```

回车 = 下一步，`c` = 跑到底，`q` = 中止（已执行部分留在栈上）。Hermes / Thoth 算一步。可与 `--file` 合用：`run --step --file x.hexparse`。

### Profile 与环境

`hexcli/profiles/*.json`，`profile list` 实测输出：

```
  * default - fake player with an oak staff, enlightened, all great patterns unlocked, infinite media/ambit
    nocaster - no caster: a real spell circle (redstone impetus + 3 slates) is built at world spawn and the code runs in the base CircleCastEnv; 1 dust budget, ambit 32 blocks around the anchor
    packaged - fake player casting through the cypher in the main hand (env mielib:packaged); infinite media/ambit
    stacked - fake player starting with stack [1, 2] and ravenmind 42; 1 dust budget
    unenlightened - same fake player but NOT enlightened: great spells parse (unlocked) but mishap when cast
```

`env list` 实测（1.0.0）：`mielib:staff`（player，法杖）、`mielib:unowned_circle`（无施法者的自动法术环）、`mielib:packaged`（手持 cypher 等）、
`mielib:circle`（以玩家为环主的法术环）；auto：player→staff，none→unowned_circle，living→唯一声明支持 LIVING 的环境（默认没有）。
`cmd caster player|none`、`cmd caster summon <实体>`、`cmd world summon <实体> <标签>` 可改施法者 / 摆目标。

## 3. hexParse 语法速查

代码是空白分隔的 token，解析成一串 iota 依次执行。来源：hexParse SYNTAX.md + 实测。

| 写法 | 得到 | 备注 |
|---|---|---|
| `add`、`entity_pos/eye`、`hexcasting:add` | 图案（按注册名路径） | 是 `write/local` 不是 `write_local` |
| `num_3`、`num_-2.5`、`num_0.5` | Numerical Reflection 图案 | **要执行的数字必须这样写** |
| `3`、`1.5` | 数字 iota 本身 | 直接执行会 mishap；放进 `[ ]` 或 `\` 后面才有用 |
| `vec_1_2_3`、`vec_1_2`、`vec` | 向量 iota | 缺的轴是 0；要入栈得 `\ vec_1_2_3` |
| `true` / `false`、`null`、`garbage` | 布尔 / Null / 垃圾 iota | 大小写不敏感；同样需要 `\` |
| `[ ... ]` | 列表 iota（可嵌套） | 执行时也要 `\ [ ... ]` |
| `( ... )` | Introspection / Retrospection | 把中间的图案收成列表入栈（最常用的“代码块”） |
| `\` 或 `escape` | Consideration | 让下一个 iota 原样入栈 |
| `mask_v-v` | Bookkeeper's Gambit | `-` 保留、`v` 丢弃，左边是栈深处 |
| `_qaq` | 按角度串的原始图案 | 起始方向固定，未知图案也能写 |
| `self` / `myself` | 施法者实体 iota | **HexCLI 里会变成 Null**（解析用的是控制台），用 `get_caster` |
| `entity_<uuid>` | 实体 iota | 未实测 |
| `comment_xxx`、`tab_4`、`// ...` | 注释 / 缩进 | 文件里多行、`//` 注释实测可用 |

内置方言：`hermes`=`eval`，`iris`=`eval/cc`，`thoth`=`for_each`，`pop`=`mask_v`，`concat`=`add`，`list_size`=`abs`，`open_paren`/`close_paren`。

### 常用图案注册名（Hex Casting 0.11.4，`hexcasting:` 可省）

完整列表：客户端里行首按 Tab，或协议 `complete` 请求返回的 `patternIds`。

- 常量：`const/null` `const/true` `const/false` `const/vec/px|py|pz|nx|ny|nz|0` `const/double/pi|tau|e|phi`
- 数学：`add` `sub` `mul` `div` `abs` `pow` `floor` `ceil` `modulo` `sin` `cos` `tan` `arcsin` `arccos` `arctan` `arctan2` `logarithm` `random` `construct_vec` `deconstruct_vec` `coerce_axial`
- 逻辑 / 比较：`and` `or` `xor` `not` `bool_coerce` `greater` `less` `greater_eq` `less_eq` `equals` `not_equals` `if`（Augur's Exaltation：`bool 真值 假值 if`）
- 栈：`duplicate` `duplicate_n` `2dup` `swap` `rotate` `rotate_reverse` `over` `tuck` `stack_len` `fisherman` `fisherman/copy` `swizzle` `mask_…`
- 列表：`empty_list` `singleton` `append` `unappend` `index` `index_of` `slice` `reverse` `last_n_list`（Flock's Gambit：`a b c num_3 last_n_list`）`splat` `remove_from` `replace` `construct` `deconstruct` `unique`
- 元运行：`eval`（Hermes）`eval/cc`（Iris）`for_each`（Thoth：`代码列表 数据列表 for_each`）`halt` `thanatos` `open_paren` `close_paren` `escape`
- 读写：`read/local` `write/local`（渡鸦之思）`read` `write`（手持物品）`read/entity` `write/entity` `akashic/read` `akashic/write`
- 实体 / 感知：`get_caster` `entity_pos/eye` `entity_pos/foot` `get_entity_look` `get_entity_height` `get_entity_velocity` `raycast` `raycast/axis` `raycast/entity` `get_entity[/animal|monster|item|player|living]` `zone_entity[/...]` `get_media`
- 类型：`type_equals` `type_not_equals`
- 法术（举例）：`print` `explode` `explode/fire` `add_motion` `blink` `break_block` `place_block` `conjure_block` `conjure_light` `create_water` `ignite` `beep` `sentinel/create` …；大型：`lightning` `teleport/great` `brainsweep` `flight` `create_lava` `summon_rain` …
- hexParse 自带：`code2focus` `focus2code` `remove_comments` `create_linebreak` `switch_comment` `learn_patterns` `donate` `compile`

## 4. 实测示例（输出原样）

以下每段都是 `reset` 后单独跑的。

**加法**

```
hex> num_1 num_2 add
cast #1  EVALUATED   steps 3  ops 3
  [0] ▶ 3.00
ravenmind: (none)
media used: 0 (0.00 dust)
media: infinite
```

`-e` 形式同样输出上面这段，退出码 0。

**栈操作：duplicate / swap**

```
hex> num_5 duplicate
cast #3  EVALUATED   steps 2  ops 2
  [0]   5.00
  [1] ▶ 5.00
hex> num_7 swap
cast #4  EVALUATED   steps 2  ops 2
  [0]   5.00
  [1]   7.00
  [2] ▶ 5.00
```

**Bookkeeper**：`num_1 num_2 num_3 mask_v-v` → `[0] ▶ 2.00`

**数学**：`num_-2.5 num_0.5 mul` → `-1.25`；`num_2 num_10 pow num_3 div floor` → `341.00`

**向量**：`\ vec_1_2_3 const/vec/py add` → `[0] ▶ (1.00, 3.00, 3.00)`

**实体**：

```
hex> get_caster duplicate entity_pos/eye
cast #13  EVALUATED   steps 3  ops 3
  [0]   minecraft:player "HexCLI" @ -159.5, 85.0, -111.5 #4e3c11ac
  [1] ▶ (-159.50, 86.62, -111.50)
```

**各类字面量**

```
hex> \ true \ null \ garbage \ [1 [2 3] vec_0_1_0] \ get_caster
cast #21  ESCAPED   steps 10  ops 5
  [0]   True
  [1]   Null
  [2]   <garbage>
  [3]   [1.00, [2.00, 3.00], (0.00, 1.00, 0.00)]
  [4] ▶ HexPattern[NORTH_EAST, qaq]
```

**条件**：`num_1 num_2 less ( num_100 ) ( num_200 ) if eval` → `[0] ▶ 100.00`

**渡鸦之思**：`num_42 write/local read/local num_3 add` → 栈 `45.00`，`ravenmind: 42.00`

**print**：`num_7 print` → 栈 `7.00`，另有一行 `message: 7.00`

**列表 + Thoth 循环**

```
hex> num_1 num_2 num_3 num_4 num_4 last_n_list
cast #5  EVALUATED   steps 6  ops 6
  [0] ▶ [1.00, 2.00, 3.00, 4.00]

hex> \ [1 2 3] ( num_10 mul ) swap for_each
cast #7  EVALUATED   steps 18  ops 14
  [0] ▶ [10.00, 20.00, 30.00]
```

**文件**（`loop.hexparse`，多行 + `//` 注释）：

```
// Thoth's Gambit: 把列表里每个数平方
( duplicate mul )
num_1 num_2 num_3 num_4 num_4 last_n_list
for_each
```

```
hex> run --file loop.hexparse
cast #6  EVALUATED   steps 24  ops 21
  [0] ▶ [1.00, 4.00, 9.00, 16.00]
```

### 故意触发 mishap（实测）

```
$ java -jar hexcli-client.jar --dir . --no-color -e "add"      # 空栈
cast #8  ERRORED   steps 1  ops 0
  [0]   <garbage>
  [1] ▶ <garbage>
ravenmind: (none)
! step 1: Additive Distillation: expected 2 or more arguments but the stack was empty
media used: 0 (0.00 dust)
media: infinite
exit=1
```

```
-e "1"            -> cast #9  INVALID ... ! step 1: Expected to evaluate a pattern, but evaluated 1.00 instead
-e "num_1 const/true add"
                  -> cast #10 ERRORED ... ! step 3: Additive Distillation: got 2 unexpected iotas at indices 0-1 of the stack: 1.00, True
mask_v-  (空栈)   -> ! step 1: Bookkeeper's Gambit: v-: expected 2 or more arguments but the stack was empty
num_1 num_2 less ( num_100 ) ( num_200 ) rotate if eval
                  -> ! step 11: Augur's Exaltation: expected a boolean at index 2 of the stack, but got a list: [...]
```

媒质 / 范围 / 启迪（`profile use stacked` = 1 粉预算、本体默认范围）：

```
hex> get_caster entity_pos/eye const/vec/py add num_2 explode
cast #30  ERRORED   steps 6  ops 5
! step 6: Explosion: That Hex needed more media than I had... I should double-check my math.
media used: 61250 (6.13 dust)
media: 0/10000 (0.00 dust)

hex> media infinite
hex> \ vec_1000_80_1000 num_1 explode
! step 4: Explosion: (1000.00, 80.00, 1000.00) is out of range

hex> profile use unenlightened
hex> get_caster entity_pos/foot lightning
cast #42  INVALID   steps 3  ops 2
! step 3: MishapUnenlightened
message: The spell failed, somehow... am I not skilled enough?
```

一次 cast 里 mishap 后后续 iota 不再执行（控制台 `hexcli run add add` 只报 step 1）。

## 5. MieHex / MieHex:Revolution 扩展图案

**一键安装包不带这两个附属。** 要用就把对应 MC 版本的 jar 放进 `server/mods/`（1.20.1 Fabric：`miehex-fabric-…+1.20.1.jar`、
`miehex_revolution-fabric-…+0.11.4.jar`，它们 JiJ 了旧版 MieLib，Fabric 会自动选更新的那份），重启服务器。
确认方式：日志 `Loading N mods` 里有 `miehex` / `miehex_revolution`，`Registered action: miehex:...`，
图案数从 `179 regular actions` 变成 `227`。下列名字都可以裸写（hexParse 按路径匹配；撞名时用全名或 `/hexParse conflict`）。

### MieHex（mod id `miehex`）

1.20.1（实测已注册，17 个）：`quine` `get_enchant` `enchant_add` `enchant` `new_idea` `idea_get` `idea_modify` `summon_idea_entity`
`brave_eval`（赫尔墨斯之勇气）`easy_thrust` `easy_extract` `evolution`（达尔文之策略）`catch`（赫菲斯托斯之策略：执行列表并把 mishap 捕获成事故 iota）
`throw`（阿波罗之策略：抛出栈顶）`type_iota`（明辨之纯化：iota 类型）`mishap_type`（剖因之纯化）`mishap_args`（析理之纯化）。
1.21（源码 `ActionRegisry`，未实测）：同上，另加 `block_type` `item_type` `entity_type` `item_type/hand`。

### MieHex:Revolution（mod id `miehex_revolution`）

1.20.1（实测已注册，32 个），1.21 源码中同名：
`const/max` `const/min` `page` `loader/time` `use` `use_on` `use_on/with` `monitor/set` `monitor/get` `idea/set` `idea_shard/write`
`eval_in`（以列表为栈执行图案）`indexes`（列表中匹配项的全部下标）`llk_loop`（数字 + 图案列表，执行 N 次）
`entity/move` `entity/step` `entity/lasthurt` `entity/target/get` `entity/target/set` `entity/rot` `interact`（大型）
`circle/used` `changeenv`
`imports/create` `imports/bind` `imports/bind_direct` `imports/get` `imports/merge` `imports/from_a` `imports/to_list` `imports/from_list` `raw/pattern`（刻印集）。

实测（1.20.1，两附属都装上）：

```
hex> \ [1 2 1 3 1] num_1 indexes        -> [0] ▶ [0.00, 2.00, 4.00]
hex> \ [2 3] ( mul ) eval_in            -> [0] ▶ 6.00
hex> num_0 num_3 ( num_1 add ) llk_loop -> [0] ▶ 3.00
hex> num_5 type_iota                    -> [0] ▶ DoubleIota
hex> ( add ) catch                      -> [0] ▶ Mishap@382641113      （EVALUATED，没有报错）
hex> ( add ) catch mishap_type          -> [0] ▶ MishapNotEnoughArgs
hex> num_1 throw                        -> cast #9  ERRORED ... ! step 2: Apollo's Gambit: Throw:1.00
hex> const/max const/min                -> 1.7976931348623157e308 / 其相反数（以 1797693…000.00 的长串打印）
```

其余（实体操控、使用方块、监视器、刻印集等）需要真实实体 / 方块 / 副手物品，未在 HexCLI 里实测。

## 6. 常见坑

- **裸数字不是数字图案**：`1 2 add` → `Expected to evaluate a pattern, but evaluated 1.00 instead`。写 `num_1 num_2 add`。
  （1.0.0 安装包里服务器启动日志的旧提示 “Try: hexcli run 1 2 add” 是错的，已在 c673420 修正为 `hexcli run num_1 num_2 add`。）
- **`Unknown symbol` 是 hexParse 的解析失败，不是执行了一条错误指令**：那个 token 在解析阶段就被丢掉、根本没进施法，所以不会有 mishap；其余 token 照常执行，`-e` 退出码仍是 0（整段都无法解析时才是 1，提示变成 `produced no iotas`）。脚本里要自己 grep `^hexParse:`。详见“三类错误”。
- **持久栈**：`-e` 之间栈不清空；mishap 会留下 `<garbage>`。测试前用 stdin 喂 `reset`。持久栈上跑 Thoth 时，每轮迭代都会带上整个旧栈，
  结果列表里会混进 `<garbage>` 等旧元素（实测 `-f loop.hexparse` 在脏栈上得到 `[<garbage>, …, 1.00, 1.00, …, 16.00]`）。
- **字面量要转义**：`vec_…`、`true`、`null`、`[ … ]` 直接执行都是 mishap，前面加 `\`。代码块用 `( … )`。
- **`self` 在 HexCLI 里是 Null**：用 `get_caster`。
- **Augur 参数顺序**：`条件 真分支 假分支 if`，再 `eval` 才执行选中的分支。
- **数字格式**：输出一律两位小数（`3.00`、`-1.25`），断言时按字符串 `3.00` 比较；超大数会打印成很长的整数串。
- **媒质 / 范围**：`default` 是无限媒质、无限范围，在里面能过的咒到真实游戏可能 `needed more media` / `out of range`。
  用 `profile use stacked`（1 粉、默认范围）或 `media <n>` 模拟真实限制；`nocaster` 是 1 粉 + 锚点 32 格。
- **施法者**：`none`（`nocaster` / `cmd caster none`）时 `get_caster` 返回 Null，走法术环环境、恒为已启迪；
  `cmd caster summon minecraft:zombie` 默认没有支持 LIVING 的环境，会提示需要附属用 `HexCliAPI.registerEnv` 注册。
- **启迪**：`unenlightened` 下大型法术 `MishapUnenlightened`；`default` 已解锁 15 个大型图案。
- **Java 版本**：1.20.1 要 Java 17+（安装器会拒绝更低的）；1.21.1 要 Java 21。
- **Windows**：用 1.0.0 正式包（a06e734 修了 bat 里 `--dir "%~dp0"` 尾部反斜杠把引号转义、导致 connect.bat 闪退；
  201f14c 修了 PowerShell 5.1 因 `java -version` 写 stderr 提前退出）。自己传路径时别以 `\` 结尾，带空格的路径加双引号：
  `install.bat -y -Mirror bmclapi -Java "C:\Program Files\Java\jdk-17"`。
- **下载 404**：草稿 / 预发布 release 的资产对未登录用户是 404；只用已发布的 tag（如 `hexcli-v1.0.0`）。国内网络用 `--mirror bmclapi --github-proxy …`。
- **端口**：Minecraft 端口默认 25565（只监听 127.0.0.1），同机多开要改 `server.properties` 的 `server-port`；HexCLI 远程端口每次随机，以 `client.json` 为准。
- **图案名**：用注册名路径（`write/local`、`entity_pos/eye`），不是英文显示名。不确定就 Tab 补全或看 `complete`。
