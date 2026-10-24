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
| `hexcli reset` | 清空栈、渡鸦之思、括号状态 |
| `hexcli tick <n>` | 让服务器跑 n tick，汇总期间的 spawned 施法 |
| `hexcli wait [quiet=20] [timeout=600]` | 一直等到连续 quiet tick 没有任何施法（或超时） |

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

## 原理

- 施法者是一个 Fabric `FakePlayer` 子类（`HexCLI`，UUID `4e3c11ac-...-000000000001`），站在世界出生点，主手拿橡木法杖。
- 每次 run 新建 `HexCliCastEnv`（继承 `StaffCastEnv`，媒质无限、无距离/权限限制）+ `CastingVM`，只保留 `CastingImage`，所以栈和渡鸦之思跨命令保留。
- `CastObserver` 用 `CastingEnvironment.addCreateEventListener` 给**服务器上每一个**施法环境挂 `PostExecution` / `ExtractMedia.Pre` / `PostCast` 组件：
  `/hexcli` 发起的记为 `root`，其余（法术环、Hexal 小精灵、HexOP 延迟施法、卡带等）记为 `spawned`，打印事件 id、环境类、施法者、位置、栈、mishap、媒质。

## 限制

- 只有 Fabric；Forge 未做。
- 法术环每块石板是一次独立的 `CircleCastEnv` 执行，所以一个环会产生多条 spawned 事件。
- Hexal / HexOP 等附属没有在开发环境里加载过，它们的施法理论上会被观察到（走的是本体的创建事件），但未实测。
- JSON profile（预设世界/物品/附属）留到下一个里程碑。
