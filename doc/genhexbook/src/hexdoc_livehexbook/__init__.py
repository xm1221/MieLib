from importlib.resources import Package, files
from typing import Any

from hexdoc.plugin import HookReturn, ModPlugin, ModPluginImpl, ModPluginWithBook, hookimpl
from typing_extensions import override


class LiveHexBookPlugin(ModPluginImpl):
    @staticmethod
    @hookimpl
    def hexdoc_mod_plugin(branch: str) -> ModPlugin:
        return LiveHexBookModPlugin(branch=branch)


class LiveHexBookModPlugin(ModPluginWithBook):
    @property
    @override
    def modid(self) -> str:
        return "livehexbook"

    @property
    @override
    def full_version(self) -> str:
        return "0.0.1"

    @property
    @override
    def mod_version(self) -> str:
        return "0.0.1+1.20.1"

    @property
    @override
    def plugin_version(self) -> str:
        return "0.0.1"

    @property
    @override
    def compat_minecraft_version(self) -> str:
        return "1.20.1"

    @override
    def resource_dirs(self) -> HookReturn[Package]:
        return []

    @override
    def jinja_template_root(self) -> tuple[Package, str] | None:
        return None



# --- genHexBook texture fixes -------------------------------------------------
# 1. hexdoc validates item icons as PNGTexture | AnimatedTexture | SingleItemTexture
#    | MultiItemTexture. With textures.missing="*" the first branch would return the
#    "missing" image before the rendered item icon is tried, so image lookups by item
#    id must fail instead of falling back.
# 2. The live project reuses modid "hexcasting", which replaces the packaged
#    hexdoc-hexcasting texture metadata; merge it back underneath ours.
def _patch_textures() -> None:
    import hexdoc.cli.app as app
    from hexdoc.data import HexdocMetadata
    from hexdoc.minecraft.assets.animated import AnimatedTexture
    from hexdoc.minecraft.assets.items import MultiItemTexture, SingleItemTexture
    from hexdoc.minecraft.assets.textures import PNGTexture

    def _wrap(tex_cls, skip):
        orig = tex_cls.lookup.__func__

        def lookup(cls, id, lookups, allowed_missing):
            if id not in cls.get_lookup(lookups) and skip(id, lookups):
                raise ValueError(f"No {cls.__name__} for {id}")
            return orig(cls, id, lookups, allowed_missing)

        tex_cls.lookup = classmethod(lookup)

    def not_texture_path(id, lookups):
        return not id.path.startswith("textures/")

    for c in (PNGTexture, AnimatedTexture):
        _wrap(c, not_texture_path)
    _wrap(SingleItemTexture, lambda id, l: id in MultiItemTexture.get_lookup(l))

    orig_render = app.render_textures_and_export_metadata

    def render(loader, asset_loader):
        packaged = loader.load_metadata(model_type=HexdocMetadata)
        result = orig_render(loader, asset_loader)
        modid = loader.props.modid
        old, new = packaged.get(modid), result.get(modid)
        if old is not None and new is not None:
            for kind, table in old.textures.items():
                merged = dict(table)
                merged.update(new.textures[kind])
                new.textures[kind] = merged
        return result

    app.render_textures_and_export_metadata = render


_patch_textures()
