from importlib.resources import Package
from typing import Any

from hexdoc.plugin import (
    HookReturn,
    LoadTaggedUnionsImpl,
    ModPlugin,
    ModPluginImpl,
    ModPluginWithBook,
    hookimpl,
)
from typing_extensions import override

from . import pages


class LiveHexBookPlugin(LoadTaggedUnionsImpl, ModPluginImpl):
    @staticmethod
    @hookimpl
    def hexdoc_mod_plugin(branch: str) -> ModPlugin:
        return LiveHexBookModPlugin(branch=branch)

    @staticmethod
    @hookimpl
    def hexdoc_load_tagged_unions() -> HookReturn[Package]:
        # Ensure concrete stubs exist before Page validation runs.
        _register_stubs_from_resources()
        return [pages]


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


def _collect_page_types_from_resources() -> set[str]:
    import json
    from pathlib import Path

    found: set[str] = set()
    root = Path("resources")
    if not root.is_dir():
        return found
    for path in root.rglob("*.json"):
        if "patchouli_books" not in path.parts:
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            continue

        def walk(obj: Any) -> None:
            if isinstance(obj, dict):
                t = obj.get("type")
                if isinstance(t, str):
                    found.add(t if ":" in t else f"patchouli:{t}")
                for v in obj.values():
                    walk(v)
            elif isinstance(obj, list):
                for v in obj:
                    walk(v)

        changed = False

        def fix(obj: Any) -> None:
            # hexdoc LookupPatternPage / ManualPatternPage require anchor == op_id.
            # Addons sometimes omit anchor, or leave a trailing slash on one side
            # (seen: hexdim op_id=.../remove vs anchor=.../remove/).
            nonlocal changed
            if isinstance(obj, dict):
                t = obj.get("type")
                if isinstance(t, str) and (
                    t == "hexcasting:manual_pattern"
                    or t.endswith(":manual_pattern")
                    or "pattern" in t
                ):
                    op = obj.get("op_id")
                    if isinstance(op, str):
                        op_norm = op.rstrip("/")
                        if op_norm != op:
                            obj["op_id"] = op_norm
                            changed = True
                            op = op_norm
                        anchor = obj.get("anchor")
                        if not anchor:
                            obj["anchor"] = op
                            changed = True
                        elif isinstance(anchor, str):
                            a_norm = anchor.rstrip("/")
                            if a_norm != anchor or a_norm != op:
                                obj["anchor"] = op
                                changed = True
                for v in obj.values():
                    fix(v)
            elif isinstance(obj, list):
                for v in obj:
                    fix(v)

        fix(data)
        if changed:
            try:
                path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            except Exception:
                pass
        walk(data)
    return found



def _ensure_pattern_stubs_from_resources() -> list[str]:
    """Append dummy stubs for book op_ids missing from patterns-registry.json.

    Addon books (e.g. hexmapping) reference optional-mod patterns that are not
    registered when those mods are absent. hexdoc LookupPatternPage then fails
    with Unknown pattern ID. A stub is enough for the docs build.
    """
    import json
    import logging
    from pathlib import Path

    log = logging.getLogger("hexdoc_livehexbook")
    registry = Path("patterns-registry.json")
    if not registry.is_file():
        return []

    try:
        stubs = json.loads(registry.read_text(encoding="utf-8"))
    except Exception:
        return []
    if not isinstance(stubs, list):
        return []

    known: set[str] = set()
    for s in stubs:
        if isinstance(s, dict) and isinstance(s.get("id"), str):
            known.add(s["id"])

    needed: set[str] = set()
    root = Path("resources")
    if root.is_dir():
        for path in root.rglob("*.json"):
            if "patchouli_books" not in path.parts:
                continue
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except Exception:
                continue

            def walk(obj: object) -> None:
                if isinstance(obj, dict):
                    op = obj.get("op_id")
                    if isinstance(op, str) and op and not op.startswith("hexcasting:"):
                        needed.add(op.rstrip("/"))
                    for v in obj.values():
                        walk(v)
                elif isinstance(obj, list):
                    for v in obj:
                        walk(v)

            walk(data)

    added: list[str] = []
    for op_id in sorted(needed - known):
        stubs.append(
            {
                "id": op_id,
                "startdir": "EAST",
                "signature": "w",
                "is_per_world": False,
            }
        )
        added.append(op_id)
    if added:
        registry.write_text(json.dumps(stubs, ensure_ascii=False, indent=2), encoding="utf-8")
        log.warning(
            "Added dummy pattern stubs for missing op_ids: %s",
            ", ".join(added),
        )
    return added


def _register_stubs_from_resources() -> None:
    import logging

    # Import concrete upstream pages so "known" is complete before we stub.
    import hexdoc.patchouli.page.pages  # noqa: F401
    import hexdoc_hexcasting.book.page.pages  # noqa: F401

    logger = logging.getLogger("hexdoc_livehexbook")
    types = _collect_page_types_from_resources()
    # Always ensure the hand-written stubs are import-registered.
    _ = pages.EverbookEntryPage, pages.LapisworksPwshapePage, pages.TransmutingPage
    newly = pages.register_runtime_page_stubs(types)
    if newly:
        logger.warning("Registered fallback Patchouli page stubs for: %s", ", ".join(newly))
    try:
        _ensure_pattern_stubs_from_resources()
    except Exception as e:
        logger.warning("Pattern stub backfill failed: %s", e)
    try:
        rnew = pages.register_runtime_recipe_stubs(_collect_recipe_samples())
        if rnew:
            logger.warning("Registered fallback recipe stubs for: %s", ", ".join(rnew))
    except Exception as e:  # never fatal
        logger.warning("Recipe stub registration failed: %s", e)


def _collect_recipe_samples() -> dict[str, dict]:
    import json
    from pathlib import Path

    out: dict[str, dict] = {}
    root = Path("resources")
    if not root.is_dir():
        return out
    for path in root.rglob("*.json"):
        if "recipes" not in path.parts:
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            continue
        if isinstance(data, dict) and isinstance(data.get("type"), str):
            out.setdefault(data["type"], data)
    return out


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
            if loader.props.textures.strict:
                raise
            if e.__class__.__name__ in {"ValidationError", "ValueError", "KeyError", "TypeError"}:
                logger.warning(
                    "Failed to render item model %s: %s: %s",
                    model.id,
                    e.__class__.__name__,
                    e,
                )
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

