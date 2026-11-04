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
        │      patterns-registry.json   ← 实时 Action 注册表中的**附属**图案（id / startdir / signature / is_per_world；hexcasting: 由 hexdoc-hexcasting 自带）
        │      resources/              ← 每次重新导出前清空；语言文件只保留 en_us / zh_cn
        │      .git/                   ← 自动 git init + 首次提交（hexdoc 要求位于 git 仓库内）
        │      hexdoc.toml, .env        ← 由 jar 内置的 doc/genhexbook 模板生成
        │      src/ + requirements.txt + build.sh  ← 伴生包（hexdoc_livehexbook 插件）
        │
        ├─ 2. 构建 (HexdocRunner)        → hexdoc build --branch main
        │      PYTHONPATH=<project>/src，输出 _site/src/docs/v/latest/main/{en_us,zh_cn}/
        │      把 _site/src/docs 复制为 <dir>/site/，并补一个跳转到 v/latest/main/en_us/ 的 /index.html
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

### 可选 JVM 参数

| 参数 | 作用 |
|------|------|
| `-Dmielib.genhexbook.siteUrl=<url>` | 站点公开地址，用作 `og:url` 与站点基路径（部署到自定义域名时设置；末尾 `/` 会自动去掉） |
| `-Dmielib.genhexbook.clientJar=<client.jar>` | 指向一份原版 Minecraft 客户端 jar；导出时把 `assets/minecraft/{models,textures/item,textures/block,blockstates}` 复制进 `hexdoc-project/resources/`，用于渲染以原版模型为父级的附属物品 |
| `-Dmielib.genhexbook.timeoutSeconds=<秒>` | hexdoc 构建超时，默认 900 |

站点地址优先级：`-Dmielib.genhexbook.siteUrl` → `/genHexBook serve` 的 `http://127.0.0.1:<port>` →
默认 `https://mielib-hexbook.pages.dev`。

物品图标复用 hexdoc-hexcasting / hexdoc-minecraft 预渲染的纹理：插件 `hexdoc_livehexbook` 会先尝试
渲染的物品图标，再回退到 `[textures] missing = "*"` 的占位图，并把 `hexcasting` 的打包纹理元数据合并回来。
只有在专用服上渲染以原版模型为父级的附属物品时，才需要 `clientJar`；未设置时静默跳过（路径不存在时记录警告）。

hexdoc 生成的内部纹理链接形如 `<asset_url>/resources/assets/<ns>/textures/....png`，其默认
`asset_url` 是 `raw.githubusercontent.com/<repo>/<sha>`（会 404）。因此 `.env` 里的
`DEBUG_GITHUBUSERCONTENT` 被写成站点地址 `<siteUrl>`，构建后 `HexdocRunner` 会把
`hexdoc-project/resources/assets/*/textures/**` 下的 `*.png` / `*.png.mcmeta` 复制到
`<site>/resources/assets/...`（保持相对路径），于是纹理由 `<siteUrl>/resources/assets/...`
提供，部署时整个 `site/` 目录一起上传即可。

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
    index.html             # 跳转到 v/latest/main/en_us/
    v/latest/main/{en_us,zh_cn}/
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

### 导出细节

- `hexdoc-project/resources/` 每次导出前**清空**，避免旧文件让本次统计成 0。
- 语言文件只保留 `en_us.json` / `zh_cn.json`（`assets/*/lang/`；`assets/*/patchouli_books/*/` 下只保留
  `en_us/`、`zh_cn/` 目录）。hexdoc 会解析它发现的所有语言，其他 mod 的非法 BCP47 标签（如
  cloth-config 的 `en_ud`）或损坏的翻译文件（Patchouli 的 `zh_cn.json`）会让整个构建失败。
- 保留下来的 `lang/*.json` 还会用 Gson 按 JSON 对象解析一遍；解析失败（如 Patchouli 上游
  `zh_cn.json` 缺逗号——花括号成对但 JSON5 无法解析）的文件会被跳过并记录一条警告。
- `patterns-registry.json` 只含**非 `hexcasting:`** 的图案（miehex、hexparse 等附属）；原版图案由
  hexdoc-hexcasting 自带的 `hexcasting.patterns.hexdoc.json` 提供，重复会导致 `Duplicate pattern id`。
- 导出后会在 `hexdoc-project/` 内 `git init` 并做一次空提交——hexdoc 需要 `git rev-parse --show-toplevel`
  成功；未安装 git 时只记录警告并继续。

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
        │      patterns-registry.json   ← **addon** patterns from the live Action registry (id / startdir / signature / is_per_world; `hexcasting:` comes from hexdoc-hexcasting)
        │      resources/              ← wiped on every run; only en_us / zh_cn language files are kept
        │      .git/                   ← auto `git init` + first commit (hexdoc requires a git repo)
        │      hexdoc.toml, .env        ← rendered from the companion bundled in the jar
        │      src/ + requirements.txt + build.sh  ← companion (hexdoc_livehexbook plugin)
        │
        ├─ 2. build (HexdocRunner)        → hexdoc build --branch main
        │      PYTHONPATH=<project>/src; output _site/src/docs/v/latest/main/{en_us,zh_cn}/
        │      _site/src/docs is copied to <dir>/site/ with a root /index.html redirecting to v/latest/main/en_us/
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

### Optional JVM properties

| Property | Effect |
|----------|--------|
| `-Dmielib.genhexbook.siteUrl=<url>` | Public site URL used for `og:url` and the site base path (set it for a custom domain; a trailing `/` is stripped) |
| `-Dmielib.genhexbook.clientJar=<client.jar>` | Path to a vanilla Minecraft client jar; its `assets/minecraft/{models,textures/item,textures/block,blockstates}` are copied into `hexdoc-project/resources/` so addon items with vanilla model parents render |
| `-Dmielib.genhexbook.timeoutSeconds=<seconds>` | hexdoc build timeout, default 900 |

Site URL priority: `-Dmielib.genhexbook.siteUrl` → `http://127.0.0.1:<port>` when `/genHexBook serve`
is used → default `https://mielib-hexbook.pages.dev`.

Item icons reuse the textures prerendered by hexdoc-hexcasting / hexdoc-minecraft: the
`hexdoc_livehexbook` plugin tries the rendered item icon before the `[textures] missing = "*"`
placeholder and merges the packaged `hexcasting` texture metadata back. `clientJar` is only needed
on dedicated servers to render addon items whose models inherit from vanilla ones; when unset it is
skipped silently (a warning is logged if the path does not exist).

hexdoc emits internal texture URLs as `<asset_url>/resources/assets/<ns>/textures/....png`, where
`asset_url` defaults to `raw.githubusercontent.com/<repo>/<sha>` (which 404s). The project's
`.env` therefore sets `DEBUG_GITHUBUSERCONTENT` to the site URL `<siteUrl>`, and after the build
`HexdocRunner` copies every `*.png` / `*.png.mcmeta` under
`hexdoc-project/resources/assets/*/textures/**` to `<site>/resources/assets/...` (same relative
paths). Textures are thus served from `<siteUrl>/resources/assets/...`, so deploy the whole
`site/` directory.

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
    index.html             # redirects to v/latest/main/en_us/
    v/latest/main/{en_us,zh_cn}/
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

### Dump details

- `hexdoc-project/resources/` is **wiped** before each dump, so stale files can no longer make a
  re-run report `dumped 0`.
- Language files are restricted to `en_us.json` / `zh_cn.json` under `assets/*/lang/` (and only
  `en_us/`, `zh_cn/` directories under `assets/*/patchouli_books/*/`). hexdoc parses every
  language it finds, so another mod's invalid BCP47 tag (cloth-config's `en_ud`) or a broken
  translation file (Patchouli's `zh_cn.json`) aborts the whole build.
- Every kept `lang/*.json` is then parsed with Gson as a JSON object; files that fail
  (e.g. Patchouli's upstream `zh_cn.json`, which is brace-balanced but missing a comma and so
  is not JSON5-parseable) are skipped with a warning.
- `patterns-registry.json` contains only **non-`hexcasting:`** patterns (miehex, hexparse, …).
  Vanilla patterns come from hexdoc-hexcasting's packaged `hexcasting.patterns.hexdoc.json`;
  duplicating them raises `Duplicate pattern id`.
- After dumping, the project is `git init`-ed with one empty commit — hexdoc needs
  `git rev-parse --show-toplevel` to succeed. When git is unavailable a warning is logged and
  the build continues.

### Notes

- Book JSON: read from `mods/*.jar` (`assets/*/patchouli_books/thehexbook/**`,
  one level of nested JiJ jars). Patchouli’s client `BookContentsBuilder` is **not** used.
- Patterns: live `IXplatAbstractions.getActionRegistry()` + `HexTags.Actions.PER_WORLD_PATTERN`
  (canonical prototype only; never world-scrambled).
- Lang: hexdoc builds every language present in the dumped resources; `zh_cn` comes from
  translation keys / `*.flatten.json5`.
- The hexdoc project directory is self-contained: `cd hexdoc-project && PYTHONPATH=src hexdoc build --branch main`
  reproduces the site without Minecraft.
