# GenHexBook — `/genHexBook`

## 中文

在**正在运行的** Minecraft 服务端（含 HexCLI 无头服）上，把 Hex Casting 的 Patchouli
手册 `hexcasting:thehexbook`（含所有已加载附属写入的分类/条目）导出成
[hexdoc](https://github.com/hexdoc-dev/hexdoc) 工程，再调用**真正的 hexdoc** 构建静态网站。

### 架构：导出 → hexdoc → 站点

```
/genHexBook export <dir> both yes 8765
        │
        ├─ 1. 导出 (HexdocProjectWriter)  → <dir>/hexdoc-project/
        │      resources/assets|data/   ← 从 mods/*.jar 合并（专用服 ResourceManager 不含客户端资源）
        │      patterns-registry.json   ← 实时 Action 注册表（id / startdir / signature / is_per_world）
        │      hexdoc.toml, .env        ← 由 jar 内置的 doc/genhexbook 模板生成
        │      src/ + requirements.txt + build.sh  ← 伴生包（hexdoc_livehexbook 插件）
        │
        ├─ 2. 构建 (HexdocRunner)        → hexdoc build --branch main
        │      PYTHONPATH=<project>/src，输出 _site/src/docs/v/latest/main/{en_us,zh_cn}/
        │      复制为 <dir>/site/，并补一个跳转到 en_us/ 的 /index.html
        │
        └─ 3. 可选
               md=yes → <dir>/md/{en_us,zh_cn}/*.md（BookScanner + MdExporter）
               port   → 127.0.0.1:<port> 静态服务 <dir>/site
```

与旧实现的区别：不再由 Java 自己拼 HTML，图案也不再靠正则扫源码。浏览器里看到的是
hexdoc 的标准界面；图案数据来自**实时 Action 注册表**（`IXplatAbstractions.getActionRegistry()`），
因此世界内 scrambled 的大型法术角度**不会**泄露——页面展示的是注册表里的规范原型。

### 前置条件

- Python ≥ 3.11，且已安装 hexdoc（伴生包 `doc/genhexbook/requirements.txt`）：

```bash
python3 -m venv /tmp/hexdoc-venv
/tmp/hexdoc-venv/bin/pip install -r doc/genhexbook/requirements.txt   # hexdoc-hexcasting==0.11.4.1.0
```

- 服务端 JVM 能找到 hexdoc，按以下顺序：
  1. `-Dmielib.genhexbook.hexdoc=/path/to/hexdoc`
  2. `PATH` 中的 `hexdoc`
  3. `-Dmielib.genhexbook.python=/path/to/python`（以 `python -m hexdoc` 调用）
  4. `/tmp/hexdoc-genhexbook-venv/bin/hexdoc`、`/tmp/hexdoc-venv/bin/hexdoc`

### 命令（权限 ≥ 2）

| 命令 | 作用 |
|------|------|
| `/genHexBook` | 导出到 `<gameDir>/hexbook`，构建中英双语站点 |
| `/genHexBook out <目录>` | 自定义输出目录 |
| `/genHexBook lang <en_us\|zh_cn\|both>` | 只影响 Markdown 导出（hexdoc 总是构建所有语言） |
| `/genHexBook md` | 默认目录 + 导出 Markdown |
| `/genHexBook serve [端口]` | 构建后在 `127.0.0.1` 提供 `<dir>/site`（默认 8765） |
| `/genHexBook export <目录> <lang> <yes\|no> [端口]` | **组合**：目录 + 语言 + 是否 md + 可选 serve |
| `/genHexBook stop` | 停止 HTTP |
| `/genHexBook deploy-hint` | 打印 Cloudflare Pages 部署提示 |

组合请用 `export`，例如：`/genHexBook export hexbook both yes 8765`。

### 输出

```
<dir>/
  hexdoc-project/          # 可独立复现的 hexdoc 工程
  site/                    # 构建产物，直接部署
    index.html             # 跳转到 en_us
    src/docs/v/latest/main/{en_us,zh_cn}/
  hexdoc-build.log         # hexdoc 的完整输出
  md/{en_us,zh_cn}/        # 仅 md=yes
```

### 部署到 Cloudflare Pages

```bash
npx wrangler pages deploy <dir>/site --project-name mielib-hexbook
```

需要 `CLOUDFLARE_API_TOKEN`。不必绑自定义域名。

### 失败的常见原因

| 现象 | 处理 |
|------|------|
| `hexdoc not found` | 按上面的顺序设置 `-Dmielib.genhexbook.hexdoc=` 或安装到 `PATH` |
| `hexdoc build exited N` | 看 `<dir>/hexdoc-build.log` 末尾 |
| 构建超时 | `-Dmielib.genhexbook.timeoutSeconds=1800`（默认 900） |

### HexCLI 示例

```bash
java -Xmx1G -Dmielib.hexcli=true \
     -Dmielib.genhexbook.hexdoc=/tmp/hexdoc-venv/bin/hexdoc \
     -jar fabric-server-launch.jar nogui
# 控制台：
genHexBook export hexbook both yes 8765
```

---

## English

On a **running** Minecraft server (including a HexCLI headless server), export Hex
Casting’s Patchouli book `hexcasting:thehexbook` (including every loaded addon’s
categories/entries) as a [hexdoc](https://github.com/hexdoc-dev/hexdoc) project, then
build the real static site with the **actual hexdoc CLI**.

### Architecture: dump → hexdoc → site

```
/genHexBook export <dir> both yes 8765
        │
        ├─ 1. dump (HexdocProjectWriter)  → <dir>/hexdoc-project/
        │      resources/assets|data/   ← merged from mods/*.jar (dedicated servers have no client assets)
        │      patterns-registry.json   ← live Action registry (id / startdir / signature / is_per_world)
        │      hexdoc.toml, .env        ← rendered from the companion bundled in the jar
        │      src/ + requirements.txt + build.sh  ← companion (hexdoc_livehexbook plugin)
        │
        ├─ 2. build (HexdocRunner)        → hexdoc build --branch main
        │      PYTHONPATH=<project>/src; output _site/src/docs/v/latest/main/{en_us,zh_cn}/
        │      copied to <dir>/site/ with a root /index.html redirecting to en_us
        │
        └─ 3. optional
               md=yes → <dir>/md/{en_us,zh_cn}/*.md (BookScanner + MdExporter)
               port   → serve <dir>/site on 127.0.0.1:<port>
```

Unlike the old renderer, Java no longer assembles HTML, and patterns are not scraped
from sources. The browser gets the stock hexdoc UI; pattern data comes from the **live
action registry** (`IXplatAbstractions.getActionRegistry()`), so world-scrambled great
spell signatures are never exposed — pages show the canonical registry prototype.

### Requirements

- Python ≥ 3.11 with hexdoc installed (companion `doc/genhexbook/requirements.txt`):

```bash
python3 -m venv /tmp/hexdoc-venv
/tmp/hexdoc-venv/bin/pip install -r doc/genhexbook/requirements.txt   # hexdoc-hexcasting==0.11.4.1.0
```

- The server JVM must find hexdoc. Resolution order:
  1. `-Dmielib.genhexbook.hexdoc=/path/to/hexdoc`
  2. `hexdoc` on `PATH`
  3. `-Dmielib.genhexbook.python=/path/to/python` (invoked as `python -m hexdoc`)
  4. `/tmp/hexdoc-genhexbook-venv/bin/hexdoc`, `/tmp/hexdoc-venv/bin/hexdoc`

### Commands (permission level ≥ 2)

| Command | Effect |
|---------|--------|
| `/genHexBook` | Dump to `<gameDir>/hexbook`, build the bilingual site |
| `/genHexBook out <dir>` | Custom output directory |
| `/genHexBook lang <en_us\|zh_cn\|both>` | Affects Markdown export only (hexdoc always builds all languages) |
| `/genHexBook md` | Default dir + Markdown export |
| `/genHexBook serve [port]` | Serve `<dir>/site` on `127.0.0.1` (default 8765) |
| `/genHexBook export <dir> <lang> <yes\|no> [port]` | Combined: dir + lang + md + optional serve |
| `/genHexBook stop` | Stop the HTTP server |
| `/genHexBook deploy-hint` | Print the Cloudflare Pages deploy hint |

Use `export` to combine options, e.g. `/genHexBook export hexbook both yes 8765`.

### Output

```
<dir>/
  hexdoc-project/          # reproducible hexdoc project
  site/                    # build output, deploy this
    index.html             # redirects to en_us
    src/docs/v/latest/main/{en_us,zh_cn}/
  hexdoc-build.log         # full hexdoc log
  md/{en_us,zh_cn}/        # only with md=yes
```

### Deploy to Cloudflare Pages

```bash
npx wrangler pages deploy <dir>/site --project-name mielib-hexbook
```

Requires `CLOUDFLARE_API_TOKEN`. No custom domain required.

### Troubleshooting

| Symptom | Fix |
|---------|-----|
| `hexdoc not found` | Set `-Dmielib.genhexbook.hexdoc=` or install it on `PATH` |
| `hexdoc build exited N` | Read the tail of `<dir>/hexdoc-build.log` |
| Build timeout | `-Dmielib.genhexbook.timeoutSeconds=1800` (default 900) |

### Notes

- Book JSON: read from `mods/*.jar` (`assets/*/patchouli_books/thehexbook/**`,
  one level of nested JiJ jars). Patchouli’s client `BookContentsBuilder` is **not** used.
- Patterns: live `IXplatAbstractions.getActionRegistry()` + `HexTags.Actions.PER_WORLD_PATTERN`
  (canonical prototype only; never world-scrambled).
- Lang: hexdoc builds every language present in the dumped resources; `zh_cn` comes from
  translation keys / `*.flatten.json5`.
- The hexdoc project directory is self-contained: `cd hexdoc-project && PYTHONPATH=src hexdoc build --branch main`
  reproduces the site without Minecraft.
