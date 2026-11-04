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
# 3. Missing / invalid item-model overrides (common in big addon sets) raise
#    FileNotFoundError inside ModelItem.find_texture; catch them when
#    textures.strict=false so one bad hat model cannot abort the whole site.
def _patch_textures() -> None:
    import logging

    import hexdoc.cli.app as app
    import hexdoc.minecraft.assets.load_assets as load_assets
    from hexdoc.data import HexdocMetadata
    from hexdoc.minecraft.assets.animated import AnimatedTexture
    from hexdoc.minecraft.assets.items import MultiItemTexture, SingleItemTexture
    from hexdoc.minecraft.assets.models import ModelItem
    from hexdoc.minecraft.assets.textures import PNGTexture

    logger = logging.getLogger("hexdoc_livehexbook")

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

    # Addon item models often point overrides at runtime-generated or absent
    # models (e.g. hexpigmentplus:item/magician_hat_high). Upstream only catches
    # TextureNotFoundError; FileNotFoundError / ValidationError kill the build.
    # With textures.strict=false, treat those as "no icon" and keep going.
    orig_override = ModelItem._find_override_texture

    def _find_override_texture(self, index, override, loader, gaslighting_items, checked_overrides):
        try:
            return orig_override(self, index, override, loader, gaslighting_items, checked_overrides)
        except FileNotFoundError as e:
            logger.warning("Skipping missing override model %s on %s: %s", override.model, self.id, e)
            return None

    ModelItem._find_override_texture = _find_override_texture

    orig_load_item = load_assets.load_and_render_item

    def load_and_render_item(model, loader, renderer, gaslighting_items, image_textures, site_url):
        try:
            return orig_load_item(model, loader, renderer, gaslighting_items, image_textures, site_url)
        except FileNotFoundError as e:
            if loader.props.textures.strict:
                raise
            logger.warning("No texture for item model %s: %s", model.id, e)
            return None
        except Exception as e:
            # ValidationError etc. from weird override models
            if loader.props.textures.strict:
                raise
            if e.__class__.__name__ in {"ValidationError", "ValueError", "KeyError", "TypeError"}:
                logger.warning("Failed to render item model %s: %s: %s", model.id, e.__class__.__name__, e)
                return None
            raise

    load_assets.load_and_render_item = load_and_render_item

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
