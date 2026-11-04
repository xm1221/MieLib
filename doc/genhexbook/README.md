# GenHexBook — hexdoc companion

Runtime companion for MieLib’s `/genHexBook` command. The command dumps a
**hexdoc project** from the live Minecraft server (resources, live action
registry, config) and then shells out to [hexdoc](https://pypi.org/project/hexdoc/)
to build the real static site.

`/genHexBook` is not limited to Hex Casting’s own book: every addon that writes
Patchouli pages into `thehexbook` is rendered too, and pattern data comes from
the **live action registry** instead of regex-scraping Kotlin/Java sources.

## What is in here

| Path | Purpose |
|------|---------|
| `src/hexdoc_livehexbook/` | Minimal `hexdoc` mod plugin (`ModPluginWithBook`, modid `livehexbook`). Optional when `hexdoc.toml` uses `modid = "hexcasting"` — kept so a dumped project can switch to it. |
| `src/hexdoc_livehexbook-0.0.1.dist-info/` | Dist-info that makes the plugin discoverable via the `hexdoc` entry point group. |
| `hexdoc.toml.template` | Project config: `modid = "hexcasting"`, book `hexcasting:thehexbook`, `resources/` dump, JSON pattern stubs. |
| `requirements.txt` | Pinned `hexdoc-hexcasting` (pulls in `hexdoc` + `hexdoc-minecraft`). |
| `.env.template` | Site metadata env used by hexdoc (repo, branch, public URL). |
| `build.sh` | Runs `hexdoc build --branch main` with `PYTHONPATH=src`. |

## Manual use

```bash
python3 -m venv /tmp/hexdoc-venv
/tmp/hexdoc-venv/bin/pip install -r requirements.txt

# inside a project dumped by /genHexBook:
cp .env.template .env
PYTHONPATH=src /tmp/hexdoc-venv/bin/hexdoc build --branch main
```

The book ends up in `_site/src/docs/v/latest/main/` (`index.html`, `en_us/`,
`zh_cn/`, `assets/`). Deploy the whole `_site` directory.

## Requirements

- Python ≥ 3.11 (hexdoc requirement)
- Java-side: `/genHexBook` needs `hexdoc` resolvable via
  `-Dmielib.genhexbook.hexdoc=<path>` or on `PATH`
- Optional JVM properties:
  - `-Dmielib.genhexbook.siteUrl=<url>` — public site URL for `og:url` / the site base path
    (defaults to `https://mielib-hexbook.pages.dev`, or the local serve URL with `serve`)
  - `-Dmielib.genhexbook.clientJar=<client.jar>` — vanilla client jar to copy
    `assets/minecraft/{models,textures/item,textures/block,blockstates}` from; only needed on
    dedicated servers to render addon items whose models use vanilla parents
- Item icons reuse the textures prerendered by hexdoc-hexcasting / hexdoc-minecraft; the plugin
  tries the rendered icon before the `[textures] missing = "*"` fallback.

---

# GenHexBook — hexdoc 伴生包

MieLib 的 `/genHexBook` 命令的运行时伴生包。命令会在真实运行的 Minecraft
服务端里导出**hexdoc 工程**（资源、实时 Action 注册表、配置），然后调用
[hexdoc](https://pypi.org/project/hexdoc/) 构建真正的静态站点。

`/genHexBook` 不局限于 Hex Casting 自带书：任何向 `thehexbook` 写入 Patchouli
页面的附属都会被渲染，图案数据来自**实时 Action 注册表**，而非正则扫描源码。

## 目录

| 路径 | 作用 |
|------|------|
| `src/hexdoc_livehexbook/` | 极简 `hexdoc` 模组插件（`ModPluginWithBook`，modid `livehexbook`）。当 `hexdoc.toml` 使用 `modid = "hexcasting"` 时可选，保留以便工程切换。 |
| `src/hexdoc_livehexbook-0.0.1.dist-info/` | 使插件能被 `hexdoc` 入口点发现的 dist-info。 |
| `hexdoc.toml.template` | 工程配置：`modid = "hexcasting"`、书 `hexcasting:thehexbook`、`resources/` 资源目录、JSON 图案桩。 |
| `requirements.txt` | 固定版本的 `hexdoc-hexcasting`（带出 `hexdoc` 与 `hexdoc-minecraft`）。 |
| `.env.template` | hexdoc 使用的站点元数据环境变量（仓库、分支、公开 URL）。 |
| `build.sh` | 以 `PYTHONPATH=src` 执行 `hexdoc build --branch main`。 |

## 手动使用

```bash
python3 -m venv /tmp/hexdoc-venv
/tmp/hexdoc-venv/bin/pip install -r requirements.txt

# 在 /genHexBook 导出的工程目录中：
cp .env.template .env
PYTHONPATH=src /tmp/hexdoc-venv/bin/hexdoc build --branch main
```

书生成在 `_site/src/docs/v/latest/main/`（`index.html`、`en_us/`、`zh_cn/`、
`assets/`）。部署整个 `_site` 目录即可。

## 要求

- Python ≥ 3.11（hexdoc 要求）
- Java 侧：`/genHexBook` 需要通过 `-Dmielib.genhexbook.hexdoc=<路径>` 或
  `PATH` 找到 `hexdoc`
- 可选 JVM 参数：
  - `-Dmielib.genhexbook.siteUrl=<url>` — 站点公开地址，用于 `og:url` / 站点基路径
    （默认 `https://mielib-hexbook.pages.dev`；使用 `serve` 时为本地地址）
  - `-Dmielib.genhexbook.clientJar=<client.jar>` — 原版客户端 jar，导出时复制
    `assets/minecraft/{models,textures/item,textures/block,blockstates}`；仅专用服渲染
    以原版模型为父级的附属物品时需要
- 物品图标复用 hexdoc-hexcasting / hexdoc-minecraft 预渲染的纹理：插件会先尝试渲染的图标，
  再回退到 `[textures] missing = "*"` 占位图。
