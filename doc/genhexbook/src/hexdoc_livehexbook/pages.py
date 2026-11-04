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
