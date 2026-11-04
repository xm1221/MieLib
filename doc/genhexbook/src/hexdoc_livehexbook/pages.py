"""Stub Patchouli page types for Hex Casting addons hexdoc does not ship.

Unknown page types abort ``hexdoc build``. We register permissive stubs so the
rest of the book (and the pattern registry) still renders. Icons/text that the
stub cannot model fall back to the ``patchouli:text`` / ``patchouli:empty``
templates.
"""

from __future__ import annotations

from typing import Any

from hexdoc.patchouli.page import EmptyPage, PageWithText


class EverbookEntryPage(EmptyPage, type="hexcasting:everbook_entry"):
    """Hexal everbook placeholder pages — no body, same as upstream hexdoc-hexal."""


class LapisworksPwshapePage(
    PageWithText,
    type="hexcasting:lapisworks/pwshape",
    template_type="patchouli:text",
    extra="allow",
):
    """Lapisworks per-world shape editor page; show summary text only."""

    op_id: Any = None
    input: Any = None
    output: Any = None
    origins: Any = None
    allowed: Any = None


class TransmutingPage(
    PageWithText,
    type="hexcasting:transmuting",
    template_type="patchouli:text",
    extra="allow",
):
    """Hexical transmuting recipe page stub."""


def register_runtime_page_stubs(type_ids: set[str]) -> list[str]:
    """Dynamically register PageWithText stubs for any remaining unknown types.

    Returns the type ids that were newly registered.
    """
    import re

    from hexdoc.patchouli.page.abstract_pages import Page

    known = set(Page._InternallyTaggedUnion__concrete_subtypes.keys())
    registered: list[str] = []
    for type_id in sorted(type_ids):
        if type_id in known:
            continue
        # Skip bare/invalid tags; Patchouli normalizes un-namespaced ones already.
        if not isinstance(type_id, str) or ":" not in type_id:
            continue
        safe = re.sub(r"[^A-Za-z0-9_]", "_", type_id)
        # Creating the subclass registers it via InternallyTaggedUnion.__init_subclass__.
        type(
            f"AddonFallback_{safe}",
            (PageWithText,),
            {"__doc__": f"Auto stub for {type_id}"},
            type=type_id,
            template_type="patchouli:text",
            extra="allow",
        )
        registered.append(type_id)
        known.add(type_id)
    return registered


# --- addon recipe types --------------------------------------------------------
# Addons register custom recipe serializers (e.g. hexdebug:focus_holder_filling_shaped)
# that are structurally vanilla shaped/shapeless crafting. Register stub subclasses
# so Patchouli crafting pages referencing them can still render.


def register_runtime_recipe_stubs(recipes: dict[str, dict]) -> list[str]:
    import types as _types

    from hexdoc.minecraft.recipe import recipes as _r
    from hexdoc.minecraft.recipe.recipes import Recipe

    known: set[str] = set()

    def _collect(cls: type) -> None:
        for sub in cls.__subclasses__():
            t = getattr(sub, "type", None)
            if t is not None:
                known.add(str(t))
            _collect(sub)

    _collect(Recipe)
    added: list[str] = []
    for type_id, sample in sorted(recipes.items()):
        if ":" not in type_id or type_id in known:
            continue
        if "pattern" in sample and "key" in sample:
            base = _r.CraftingShapedRecipe
        elif isinstance(sample.get("ingredients"), list):
            base = _r.CraftingShapelessRecipe
        else:
            continue
        name = "Stub_" + "".join(c if c.isalnum() else "_" for c in type_id)
        try:
            _types.new_class(
                name,
                (base,),
                {"type": type_id, "template_type": str(base.type)},
                lambda ns: ns.update({"__module__": __name__}),
            )
        except Exception:
            continue
        added.append(type_id)
    return added
