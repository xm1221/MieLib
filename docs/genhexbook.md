# GenHexBook — `/genHexBook`

## 中文

在**正在运行的** Minecraft 服务端（含 HexCLI 无头服）上，把 Hex Casting 的 Patchouli 手册 `hexcasting:thehexbook`（含所有已加载附属写入的分类/条目）渲染成类似 [hexdoc](https://github.com/hexdoc-dev/hexdoc) 的静态网站。

与 hexdoc 的关键区别：hexdoc 用正则从模组源码抓图案；本命令在真实 MC 进程里走 **Action 注册表**（`IXplatAbstractions.getActionRegistry()`），得到 id / 起始方向 / 角度串，并识别 `per_world_pattern`（大型法术）。世界内 scrambled 角度**不会**泄露——页面上画的是注册表里的**规范原型**，并打上 per-world 标记。

### 命令（权限 ≥ 2）

| 命令 | 作用 |
|------|------|
| `/genHexBook` | 输出到 `<gameDir>/hexbook`，语言 `en_us`+`zh_cn` |
| `/genHexBook out <目录>` | 自定义输出目录（默认 both，无 md） |
| `/genHexBook lang <en_us\|zh_cn\|both>` | 选择语言 |
| `/genHexBook md` | 默认目录 + both + 导出 Markdown |
| `/genHexBook serve [端口]` | 生成后在 `127.0.0.1` 提供静态文件（默认 8765） |
| `/genHexBook export <目录> <lang> <yes\|no> [端口]` | **组合**：目录 + 语言 + 是否 md + 可选 serve |
| `/genHexBook stop` | 停止 HTTP |
| `/genHexBook deploy-hint` | 打印 Cloudflare Pages 部署提示 |

组合请用 `export`，例如：`/genHexBook export hexbook both yes 8765`。子命令互为并列，不能写成 `out … lang … md serve`。

### 部署到 Cloudflare Pages

```bash
npx wrangler pages deploy <hexbook目录> --project-name mielib-hexbook
```

需要 `CLOUDFLARE_API_TOKEN`。不必绑自定义域名。

### 配置

- `-Dmielib.genhexbook.revealGreat=true`：在文案上更明确展示大型法术说明（仍只用规范原型，不用世界 scrambled 串）。

### HexCLI 示例

```bash
java -Xmx1G -Dmielib.hexcli=true -jar fabric-server-launch.jar nogui
# 控制台：
genHexBook export hexbook both yes 8765
```

---

## English

On a **running** Minecraft server (including a HexCLI headless server), render Hex Casting’s Patchouli book `hexcasting:thehexbook` (including every loaded addon’s categories/entries) into a hexdoc-style static site.

Unlike hexdoc (which scrapes pattern data from mod sources with regex), this command reads the **live action registry** inside a real MC process. Per-world / great spells are tagged; the page shows the **canonical registry prototype**, never the world’s scrambled signature.

### Commands (permission level ≥ 2)

| Command | Effect |
|---------|--------|
| `/genHexBook` | Write to `<gameDir>/hexbook`, langs `en_us`+`zh_cn` |
| `/genHexBook out <dir>` | Custom output directory |
| `/genHexBook lang <en_us\|zh_cn\|both>` | Language selection |
| `/genHexBook md` | Default dir + both + Markdown |
| `/genHexBook serve [port]` | Serve on `127.0.0.1` (default 8765) |
| `/genHexBook export <dir> <lang> <yes\|no> [port]` | Combined: dir + lang + md + optional serve |
| `/genHexBook stop` | Stop the HTTP server |
| `/genHexBook deploy-hint` | Print Cloudflare Pages deploy hint |

Use `export` to combine options, e.g. `/genHexBook export hexbook both yes 8765`. Subcommands are siblings and cannot be chained as `out … lang … md serve`.

### Deploy to Cloudflare Pages

```bash
npx wrangler pages deploy <hexbook-dir> --project-name mielib-hexbook
```

Requires `CLOUDFLARE_API_TOKEN`. No custom domain required.

### Notes

- Book JSON: try server `ResourceManager` first; on dedicated/HexCLI (empty pack) fall back to scanning `mods/*.jar` (`ModAssetWalker`) for `data/*/patchouli_books/thehexbook/**`. Patchouli client `BookContentsBuilder` is **not** used.
- Patterns: live `IXplatAbstractions.getActionRegistry()` + `HexTags.Actions.PER_WORLD_PATTERN` (canonical prototype only; never world-scrambled).
- Lang: Hex Casting ships book JSON under `en_us` only; `zh_cn` comes from translation keys / `*.flatten.json5`.
