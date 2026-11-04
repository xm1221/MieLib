# mods-local — jar drop-in（次要）

把 `*.jar` 放在这个目录里，解析脚本会在 mods 列表下载完成之后把它们复制进服务端 `mods/`。
同名文件覆盖列表里下载的 jar。这里的 jar **原样复制**，不会套用 `strip_nested`。

这个目录被 gitignore（`*.jar`）。GitHub Actions 只检出仓库里的文件，所以**被忽略的 jar 不会进 CI**。
CI 上加附属，请编辑 [`../mods.yml`](../mods.yml)（Modrinth / Maven / CurseForge / 直链）。
本地跑 `tools/export_site.sh` 时，这个目录才是放 jar 的地方。

不要把这些 jar 打进 MieLib 本体。

# mods-local — jar drop-in (secondary)

Drop `*.jar` files here. The resolver copies them into the server `mods/` folder
after it downloads the manifest, and a same filename replaces the downloaded jar.
Local jars are copied as-is (`strip_nested` is not applied).

`*.jar` in this directory is gitignored. GitHub Actions checks out the repository,
so an ignored jar **never reaches CI**. To add an addon in CI, edit
[`../mods.yml`](../mods.yml) (Modrinth / Maven / CurseForge / direct URL).
Use this folder when you run `tools/export_site.sh` on your own machine.

Do not bundle these jars into the MieLib mod jar.
