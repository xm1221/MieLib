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

### 部署

手动（Cloudflare Pages）：

```bash
npx wrangler pages deploy <dir>/site --project-name mielib-hexbook
```

需要 `CLOUDFLARE_API_TOKEN`。不必绑自定义域名。GitHub Pages 与「fork 自己的站点」见下面一节：改 `doc/genhexbook/mods.yml` 后推到 `main`，Actions 会重建并部署到 Cloudflare Pages、GitHub Pages，或两边都部署。

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

### 用 mods 列表搭自己的站点

加 Hex Casting 附属的主路径是编辑 [`doc/genhexbook/mods.yml`](../doc/genhexbook/mods.yml)，不是往仓库里塞 jar。
推到 `main`（或手动跑 Actions）后，[`.github/workflows/genhexbook-pages.yml`](../.github/workflows/genhexbook-pages.yml) 会：

1. 按清单下载 mod，并编出本仓库的 Fabric 版 MieLib（不把第三方库打进 mod jar）；
2. 拉起 Fabric 无头服，用 `-Dmielib.genhexbook.siteUrl=` 跑 `genHexBook export`（和本地 `/genHexBook` 同一条导出路径）；
3. 把 `site/` 部署到 Cloudflare Pages 和/或 GitHub Pages。

清单里已经有 HexCLI 冒烟用的那一套（Fabric API、Kotlin、Architectury、Cloth Config、Patchouli、Cardinal Components、Inline、PAUCAL、Hex Casting 0.11.4、hexParse）以及 MieHex。

#### 清单字段

| 字段 | 作用 |
|------|------|
| `minecraft` / `loader` / `fabric_loader` / `fabric_installer` | 游戏与 Fabric 服务端启动器。`loader` 只能是 `fabric` |
| `name` | 唯一名字，也是日志里的名字 |
| `enabled` | 省略等于 `true`；`false` 时跳过，字段可以不齐 |
| `role` | `library` 或 `addon`，只给人看，解析器不靠它 |
| `source` | `modrinth`、`maven`、`curse`、`url` |
| `filename` | 落到 `mods/` 的文件名；同名本地 jar 会覆盖它 |
| `sha512` | 可选。下载后校验，不通过就失败。`strip_nested` 在校验之后才改 jar |
| `strip_nested` | 要从附属 jar 里删掉的嵌套 mod id。MieHex 的发行 jar 嵌了一份旧 MieLib，删掉它，加载的才是刚编出来、带 `/genHexBook` 的那份 |

按来源再写：

| source | 字段 |
|--------|------|
| `modrinth` | `project`（slug 或 id），`version`（版本 id，或该 MC/加载器下的版本号） |
| `maven` | `repo`、`group`、`artifact`、`version`，可选 `classifier`、`extension`（默认 `jar`） |
| `curse` | `project_id`、`file_id`。仓库密钥 `CURSEFORGE_API_KEY`（只在有 Curse 条目时需要；工作流不会打印它） |
| `url` | `url`，建议再写 `filename` |

例（默认不启用，抄进 `mods:` 再把 `enabled` 改成 `true`）：

```yaml
  - name: example-maven
    enabled: false
    role: addon
    source: maven
    repo: https://maven.example.com/releases
    group: com.example
    artifact: example-fabric
    version: "1.2.3"

  - name: example-curse
    enabled: false
    role: addon
    source: curse
    project_id: 123456
    file_id: 654321

  - name: example-url
    enabled: false
    role: addon
    source: url
    url: https://example.com/addons/example-fabric.jar
    filename: example-fabric.jar
```

#### jar 投放（次要）

[`doc/genhexbook/mods-local/`](../doc/genhexbook/mods-local/) 里的 `*.jar` 被 gitignore。解析脚本会在下载之后把它们拷进服务端 `mods/`，同名文件覆盖清单里的 jar，并且**不**做 `strip_nested`。

Actions 只检出 git 里的文件，被忽略的 jar **不会进 CI**。要在 CI 里加一个没有 Maven/Modrinth 坐标的附属，用 `source: url` 指向发行文件。这个目录是本机跑 `doc/genhexbook/tools/export_site.sh` 时的备用入口。

#### Fork 之后

1. Fork 这个仓库，在 Actions 里允许工作流运行。
2. 按需要设置密钥和变量（见下表）。一个都不设也可以：工作流仍会编站点并上传名为 `genhexbook-site` 的 artifact，只是不部署。
3. 编辑 `doc/genhexbook/mods.yml`（加一条附属，或把 `enabled` 改掉）。
4. 推到你自己的 `main`。也可以在 Actions 里手动跑 **GenHexBook site**。
5. 打开部署出来的 URL。写进站点的地址是 `GENHEXBOOK_SITE_URL`，默认 `https://mielib-hexbook.pages.dev`。只部署到 GitHub 项目页时，把它改成 `https://<用户>.github.io/<仓库>`，否则纹理链接仍指向默认域名。

触发条件：推到 `main`，且改动了 `doc/genhexbook/**`、`docs/genhexbook.md`、工作流本身、`common/.../genhexbook/**` 或 `fabric/build.gradle.kts`；以及 `workflow_dispatch`。别的提交不会自动重建，需要时手动跑一次。

#### 密钥和变量

密钥写在仓库或组织的 Actions secrets 里。文件里没有令牌，日志里也不要打印。

| 名字 | 何时需要 |
|------|----------|
| `CLOUDFLARE_API_TOKEN` | Cloudflare Pages，权限含 Pages 编辑。没有它（或没有账号 id）就跳过 CF |
| `CLOUDFLARE_ACCOUNT_ID` | 同上 |
| `CURSEFORGE_API_KEY` | 清单里有启用的 `source: curse` 时 |

| 变量 | 含义 |
|------|------|
| `GENHEXBOOK_DEPLOY` | `auto`（默认）、`both`、`cloudflare`、`github`、`none`。`auto` 只部署已经配好的那一侧；`both` 要求两侧都配好，缺了就失败 |
| `GENHEXBOOK_SITE_URL` | 传给 `-Dmielib.genhexbook.siteUrl=` 的公开地址。默认 `https://mielib-hexbook.pages.dev` |
| `GENHEXBOOK_CF_PROJECT` | Cloudflare Pages 项目名，默认 `mielib-hexbook` |
| `GENHEXBOOK_GITHUB_PAGES` | `true` / `false`。留空时：`actions` 发布器在仓库已经开了 Pages 才部署；`peaceiris` 需要把它设为 `true`（或 Pages 已经存在） |
| `GENHEXBOOK_GH_PUBLISHER` | `actions`（默认）或 `peaceiris` |

#### Cloudflare Pages

1. 做一张 API token，账号权限里勾上 Cloudflare Pages 的编辑。
2. 设置 `CLOUDFLARE_API_TOKEN` 和 `CLOUDFLARE_ACCOUNT_ID`。有的 xm1221 仓库上已经有同名密钥，fork 之后要在自己的仓库再设一份；不要把值写进 git。
3. 可选：`GENHEXBOOK_CF_PROJECT`（默认 `mielib-hexbook`）。工作流会在没有项目时创建，再生产部署 `site/`。
4. 站点默认在 `https://<项目名>.pages.dev`。自定义域名时把 `GENHEXBOOK_SITE_URL` 改成那个域名。

#### GitHub Pages

两条路，由 `GENHEXBOOK_GH_PUBLISHER` 选。

**`actions`（默认）** — `actions/upload-pages-artifact` + `actions/deploy-pages`：

1. 仓库 Settings → Pages → Build and deployment → Source 选 **GitHub Actions**。
2. 工作流已经申请 `pages: write` 和 `id-token: write`，部署 job 使用名为 `github-pages` 的 environment。
3. `auto` 模式下，Pages 站点还不存在就跳过并在日志里说明；手动把部署目标选成 `github` 或 `both`，而 Pages 没开，这次运行会失败并告诉你怎么开。

**`peaceiris`** — `peaceiris/actions-gh-pages`，把站点推到孤儿分支 `gh-pages`：

1. 变量 `GENHEXBOOK_GH_PUBLISHER=peaceiris`。
2. `GENHEXBOOK_GITHUB_PAGES=true`（Pages 已经开着也可以）。
3. 若 Pages 的 Source 要指向分支，选 `gh-pages` / `/ (root)`。和 `actions` 发布器不要同时用两种 Source。

GitHub 项目页的地址带仓库路径（`https://<用户>.github.io/<仓库>/`）。用户页、组织页和 Cloudflare Pages 在域名根上。hexdoc 的页面链接多半是相对路径；如果项目页去域名根上要资源，改用 Cloudflare Pages 或绑一个根域名，并把 `GENHEXBOOK_SITE_URL` 设成实际打开的地址。

#### 本机跑同一条流水线

```bash
python3 -m venv /tmp/hexdoc-venv
/tmp/hexdoc-venv/bin/pip install -r doc/genhexbook/requirements.txt -r doc/genhexbook/tools/requirements.txt
./gradlew :fabric:build
server=build/genhexbook-server
/tmp/hexdoc-venv/bin/python doc/genhexbook/tools/resolve_mods.py \
  --out "$server/mods" --server-jar "$server/fabric-server-launch.jar"
mielib="$(find fabric/build/libs -maxdepth 1 -name '*-fabric-*.jar' \
  ! -name '*-dev*' ! -name '*-sources*' ! -name '*-javadoc*' | head -n 1)"
cp "$mielib" "$server/mods/"
bash doc/genhexbook/tools/export_site.sh \
  --server-dir "$server" --hexdoc /tmp/hexdoc-venv/bin/hexdoc \
  --site-url https://mielib-hexbook.pages.dev --out build/genhexbook-site
```

服务端 `max-tick-time=-1`：`/genHexBook` 在服务器线程里等 hexdoc，默认 60 秒看门狗会把进程杀掉。没有显示器时 `export_site.sh` 会用 `xvfb-run`（需要安装 `xvfb`），因为 hexdoc 画物品图标要 OpenGL。hexdoc 会把资源 id（如 `hexcasting:interop/...`）当成目录名；上传前把路径里的 `:` 换成 `_`，否则 GitHub 的 artifact / Pages 会拒收。CI 不下载原版客户端 jar，因此以原版模型为父级的附属物品图标会走 hexdoc 的占位图；需要时自行加 `-Dmielib.genhexbook.clientJar`。这条工作流只起 Fabric 服务端。

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

### Deploy

Manual (Cloudflare Pages):

```bash
npx wrangler pages deploy <dir>/site --project-name mielib-hexbook
```

Requires `CLOUDFLARE_API_TOKEN`. No custom domain required. GitHub Pages and the fork flow are in the next section: edit `doc/genhexbook/mods.yml`, push to `main`, and Actions rebuilds and deploys to Cloudflare Pages, GitHub Pages, or both.

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

### Fork your own site

The primary way to add a Hex Casting addon is to edit [`doc/genhexbook/mods.yml`](../doc/genhexbook/mods.yml), not to commit jars.
Pushing to `main` (or running the workflow by hand) makes [`.github/workflows/genhexbook-pages.yml`](../.github/workflows/genhexbook-pages.yml):

1. download the mods in the list and build this repo's Fabric MieLib jar (third-party jars stay out of the mod jar);
2. boot a headless Fabric server and run `genHexBook export` with `-Dmielib.genhexbook.siteUrl=` — the same export path as a local `/genHexBook`;
3. deploy `site/` to Cloudflare Pages and/or GitHub Pages.

The list is seeded with the HexCLI smoke set (Fabric API, Kotlin, Architectury, Cloth Config, Patchouli, Cardinal Components, Inline, PAUCAL, Hex Casting 0.11.4, hexParse) plus MieHex.

#### Manifest fields

| Field | Meaning |
|-------|---------|
| `minecraft` / `loader` / `fabric_loader` / `fabric_installer` | Game version and the Fabric server launcher. `loader` must be `fabric` |
| `name` | Unique name, also the name in the log |
| `enabled` | Omitted means `true`. `false` skips the entry and the other fields may be incomplete |
| `role` | `library` or `addon`. Informational; the resolver does not branch on it |
| `source` | `modrinth`, `maven`, `curse`, or `url` |
| `filename` | Name under `mods/`. A local jar with the same name replaces it |
| `sha512` | Optional. The download is rejected when it does not match. `strip_nested` rewrites the jar only after this check |
| `strip_nested` | Nested mod ids to remove from an addon jar. MieHex's release jar nests an older MieLib; removing it makes the freshly built jar (the one that contains `/genHexBook`) the copy that loads |

Per source:

| source | Fields |
|--------|--------|
| `modrinth` | `project` (slug or id), `version` (version id, or the version number for this Minecraft version and loader) |
| `maven` | `repo`, `group`, `artifact`, `version`, optional `classifier` and `extension` (default `jar`) |
| `curse` | `project_id`, `file_id`. Repository secret `CURSEFORGE_API_KEY` (only when a Curse entry is enabled; the workflow does not print it) |
| `url` | `url`, and a `filename` is recommended |

Examples (leave `enabled: false` until you want them, then copy the entry under `mods:`):

```yaml
  - name: example-maven
    enabled: false
    role: addon
    source: maven
    repo: https://maven.example.com/releases
    group: com.example
    artifact: example-fabric
    version: "1.2.3"

  - name: example-curse
    enabled: false
    role: addon
    source: curse
    project_id: 123456
    file_id: 654321

  - name: example-url
    enabled: false
    role: addon
    source: url
    url: https://example.com/addons/example-fabric.jar
    filename: example-fabric.jar
```

#### Jar drop-in (secondary)

`*.jar` files in [`doc/genhexbook/mods-local/`](../doc/genhexbook/mods-local/) are gitignored. The resolver copies them into the server `mods/` folder after the downloads. A same filename wins, and **`strip_nested` is not applied**.

Actions checks out git, so an ignored jar **never reaches CI**. For an addon that has no Maven or Modrinth coordinate, use `source: url` and point it at a release file. The directory is the escape hatch for a local `doc/genhexbook/tools/export_site.sh` run.

#### After you fork

1. Fork the repository and allow Actions to run.
2. Set the secrets and variables you need (table below). Setting none is valid: the workflow still builds the site and uploads a `genhexbook-site` artifact, and it does not deploy.
3. Edit `doc/genhexbook/mods.yml` (add an addon, or flip `enabled`).
4. Push to your `main`. You can also run **GenHexBook site** by hand under Actions.
5. Open the deployed URL. The URL written into the site is `GENHEXBOOK_SITE_URL`, default `https://mielib-hexbook.pages.dev`. If you only publish a GitHub project site, set it to `https://<user>.github.io/<repo>` or texture links keep pointing at the default host.

Triggers: a push to `main` that touches `doc/genhexbook/**`, `docs/genhexbook.md`, the workflow file, `common/.../genhexbook/**`, or `fabric/build.gradle.kts`, plus `workflow_dispatch`. Other commits do not rebuild the site; run the workflow by hand when you want that.

#### Secrets and variables

Secrets belong in the repository or organization Actions secrets. The workflow file has no token values, and the jobs must not print them.

| Name | When |
|------|------|
| `CLOUDFLARE_API_TOKEN` | Cloudflare Pages, with Pages edit. Cloudflare is skipped when this or the account id is unset |
| `CLOUDFLARE_ACCOUNT_ID` | Same as above |
| `CURSEFORGE_API_KEY` | Only when an enabled `source: curse` entry is in the list |

| Variable | Meaning |
|----------|---------|
| `GENHEXBOOK_DEPLOY` | `auto` (default), `both`, `cloudflare`, `github`, or `none`. `auto` deploys each host that is configured. `both` requires both and fails if either is missing |
| `GENHEXBOOK_SITE_URL` | Public URL passed as `-Dmielib.genhexbook.siteUrl=`. Default `https://mielib-hexbook.pages.dev` |
| `GENHEXBOOK_CF_PROJECT` | Cloudflare Pages project name, default `mielib-hexbook` |
| `GENHEXBOOK_GITHUB_PAGES` | `true` or `false`. When unset, the `actions` publisher deploys only if a Pages site already exists; `peaceiris` also needs this set to `true` (or an existing Pages site) |
| `GENHEXBOOK_GH_PUBLISHER` | `actions` (default) or `peaceiris` |

#### Cloudflare Pages

1. Create an API token with Cloudflare Pages edit on the account.
2. Set `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`. Some xm1221 repositories already have secrets with these names; a fork needs its own copy. Do not commit the values.
3. Optionally set `GENHEXBOOK_CF_PROJECT` (default `mielib-hexbook`). The workflow creates the project when it is missing, then deploys `site/`.
4. The default host is `https://<project>.pages.dev`. For a custom domain, set `GENHEXBOOK_SITE_URL` to that origin.

#### GitHub Pages

`GENHEXBOOK_GH_PUBLISHER` picks one of the two publishers.

**`actions` (default)** — `actions/upload-pages-artifact` and `actions/deploy-pages`:

1. Repository Settings → Pages → Build and deployment → Source: **GitHub Actions**.
2. The workflow already requests `pages: write` and `id-token: write`. The deploy job uses the `github-pages` environment.
3. In `auto` mode a missing Pages site is skipped with a log notice. Choosing `github` or `both` by hand while Pages is off fails the run and prints how to turn it on.

**`peaceiris`** — `peaceiris/actions-gh-pages` pushes the site to an orphan `gh-pages` branch:

1. Set `GENHEXBOOK_GH_PUBLISHER=peaceiris`.
2. Set `GENHEXBOOK_GITHUB_PAGES=true` (an existing Pages site is enough as well).
3. If the Pages source should be a branch, choose `gh-pages` and `/ (root)`. Do not point Pages at a branch and at GitHub Actions at the same time.

A GitHub project site is served under the repository path (`https://<user>.github.io/<repo>/`). User sites, organization sites, and Cloudflare Pages are at the domain root. hexdoc page links are mostly relative; if a project site requests assets from the domain root, use Cloudflare Pages or a custom root domain, and set `GENHEXBOOK_SITE_URL` to the URL you actually open.

#### Run the same pipeline locally

```bash
python3 -m venv /tmp/hexdoc-venv
/tmp/hexdoc-venv/bin/pip install -r doc/genhexbook/requirements.txt -r doc/genhexbook/tools/requirements.txt
./gradlew :fabric:build
server=build/genhexbook-server
/tmp/hexdoc-venv/bin/python doc/genhexbook/tools/resolve_mods.py \
  --out "$server/mods" --server-jar "$server/fabric-server-launch.jar"
mielib="$(find fabric/build/libs -maxdepth 1 -name '*-fabric-*.jar' \
  ! -name '*-dev*' ! -name '*-sources*' ! -name '*-javadoc*' | head -n 1)"
cp "$mielib" "$server/mods/"
bash doc/genhexbook/tools/export_site.sh \
  --server-dir "$server" --hexdoc /tmp/hexdoc-venv/bin/hexdoc \
  --site-url https://mielib-hexbook.pages.dev --out build/genhexbook-site
```

The server sets `max-tick-time=-1` because `/genHexBook` waits for hexdoc on the server thread and the default 60-second watchdog would kill it. With no display, `export_site.sh` uses `xvfb-run` (install `xvfb`); hexdoc needs OpenGL to draw item icons. hexdoc uses resource ids such as `hexcasting:interop/...` as directory names; before upload those `:` characters are rewritten to `_`, because GitHub artifact storage and GitHub Pages reject them. CI does not download a vanilla client jar, so addon items whose models use vanilla parents fall back to hexdoc's placeholder; pass `-Dmielib.genhexbook.clientJar` yourself when you need those icons. This workflow boots a Fabric server only.
