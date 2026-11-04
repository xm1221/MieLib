"""Unified error tolerance for the ``/genHexBook`` hexdoc site build.

Why this module exists
----------------------
``/genHexBook`` merges the Patchouli books of ~90 Hex Casting addons into one
hexdoc project. hexdoc itself is strict: a single unparseable page, entry,
category or internal link aborts the entire build. Patching each new addon with
a targeted stub or op_id backfill turned into whack-a-mole (see the
``genhexbook`` history), so this module replaces all of that with one policy:

``hexcasting`` content must be complete
    Any failure in a book file that came from the ``hexcasting`` jar still
    aborts the build. Silence there would mean shipping an incomplete book.

everything addons contributed is best effort
    Drop the smallest piece that cannot be parsed - a page first, then the
    entry, then the category - and log a warning that names the source mod.
    A dropped entry never brings its category down, and a dropped category
    never brings the book down.

broken internal links degrade to plain text, everywhere
    A dangling ``$(l:...)`` is a content bug in one addon, not a reason to
    lose the whole site. ``LinkStyle.href`` reports the dead target and renders
    nothing; ``macros/styles.html.jinja`` (shipped next to this module) turns
    such a link into plain text instead of an empty anchor.

The source mod of every dumped book file comes from ``resource-origins.json``,
written by ``HexdocProjectWriter`` (Java). Without that manifest nothing is
treated as strict; the build still succeeds but logs that the completeness
check was skipped.

Everything here is installed by :func:`install` at plugin import time, so the
hooks are in place before ``hexdoc build`` loads the book.
"""

from __future__ import annotations

import copy
import json
import logging
from collections import Counter
from pathlib import Path
from typing import Any

logger = logging.getLogger("hexdoc_livehexbook.tolerance")

#: hexdoc release whose private internals (``Book._load_categories`` /
#: ``Book._load_entries`` / ``LinkStyle``) these hooks are written against.
TESTED_HEXDOC_VERSION = "1!0.1.0a40"

#: Manifest written next to ``hexdoc.toml`` by ``HexdocProjectWriter``.
ORIGINS_FILE = "resource-origins.json"

_counts: Counter[str] = Counter()
_warned: set[str] = set()

_origins: dict[str, str] | None = None
_origins_loaded = False
_origins_props_dir: Path | None = None


# --------------------------------------------------------------------------- #
# source-mod bookkeeping
# --------------------------------------------------------------------------- #


def set_props_dir(props_dir: Path | None) -> None:
    """Hint where ``hexdoc.toml`` lives, for the source manifest lookup."""
    global _origins_props_dir
    _origins_props_dir = props_dir


def _load_origins() -> dict[str, str]:
    """Read ``resource-origins.json`` once: ``<book>/<lang>/<folder>/<id> -> modid``."""
    global _origins, _origins_loaded
    if _origins_loaded:
        return _origins or {}
    _origins_loaded = True

    candidates: list[Path] = []
    if _origins_props_dir is not None:
        candidates.append(_origins_props_dir / ORIGINS_FILE)
    candidates.append(Path.cwd() / ORIGINS_FILE)

    for path in candidates:
        if not path.is_file():
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001 - a broken manifest is not fatal
            logger.warning("[tolerance] cannot read %s: %s", path, exc)
            continue
        if isinstance(data, dict):
            _origins = {
                str(key): value
                for key, value in data.items()
                if isinstance(value, str)
            }
            logger.info(
                "[tolerance] source manifest %s: %d book files",
                path.name,
                len(_origins),
            )
            return _origins

    _origins = {}
    logger.warning(
        "[tolerance] %s not found: addon content is still tolerated, but the "
        '"hexcasting book must be complete" check is disabled',
        ORIGINS_FILE,
    )
    return _origins


def _book_key(book_id: Any, lang: str, folder: str, id: Any) -> str:
    """Manifest key for one book file."""
    return f"{book_id.path}/{lang}/{folder}/{id.path}"


def _owner(key: str) -> str | None:
    return _load_origins().get(key)


def _is_strict(key: str, book_ns: str) -> bool:
    """True when this file belongs to the book's own mod (``hexcasting``)."""
    origins = _load_origins()
    if not origins:
        return False
    return origins.get(key) == book_ns


# --------------------------------------------------------------------------- #
# reporting
# --------------------------------------------------------------------------- #


def _first_line(exc: BaseException) -> str:
    text = str(exc).strip().splitlines()
    return text[0] if text else exc.__class__.__name__


def _warn(kind: str, key: str, exc: BaseException, owner: str | None) -> None:
    """Log one dropped item, at most once per key."""
    if key in _warned:
        return
    _warned.add(key)
    _counts[kind] += 1
    logger.warning(
        "[tolerance] dropped %s %s%s: %s: %s",
        kind,
        key,
        f" (from {owner})" if owner else "",
        exc.__class__.__name__,
        _first_line(exc),
    )


def _report(book_id: Any) -> None:
    if not _counts:
        return
    logger.warning(
        "[tolerance] %s: dropped %d page(s), %d entry(ies), %d categor(y/ies), "
        "%d broken link(s); the warnings above name the source mods",
        book_id,
        _counts["page"],
        _counts["entry"],
        _counts["category"],
        _counts["link"],
    )


# --------------------------------------------------------------------------- #
# categories
# --------------------------------------------------------------------------- #


def _load_categories(self, context: Any, book_ctx: Any) -> None:
    """``Book._load_categories`` with per-category tolerance.

    Mirrors ``hexdoc/patchouli/book.py`` (hexdoc 1!0.1.0a40). Differences: a
    category that fails to validate is dropped instead of aborting the book, a
    parent cycle is broken instead of raising, and a category whose parent never
    loaded is moved to the root instead of raising.
    """
    from hexdoc.core import ModResourceLoader
    from hexdoc.patchouli.category import Category
    from hexdoc.utils import cast_context, sorted_dict
    from hexdoc.utils.graphs import TypedDiGraph

    loader = ModResourceLoader.of(context)
    book_id = book_ctx.book_id
    book_ns = book_id.namespace
    lang = loader.props.default_lang
    ctx = cast_context(context)

    categories = dict[Any, Any]()
    graph = TypedDiGraph[Any]()
    nodes: set[Any] = set()

    for resource_dir, id, data in loader.load_book_assets(
        book_id,
        "categories",
        self.use_resource_pack,
    ):
        key = _book_key(book_id, lang, "categories", id)
        try:
            category = Category.load(resource_dir, id, data, ctx)
        except Exception as exc:  # noqa: BLE001
            if _is_strict(key, book_ns):
                raise
            _warn("category", key, exc, _owner(key))
            continue

        if not category.is_flag_enabled:
            logger.info(
                "Skipping category %s due to disabled flag %s", id, category.flag
            )
            continue

        categories[id] = category
        nodes.add(id)
        if category.parent_id:
            nodes.add(category.parent_id)
            graph.add_edge(category.parent_id, category.id)

    if not categories:
        raise ValueError(
            "No categories found, are the paths in your properties file correct?"
        )

    # A cycle cannot be ordered. Break it (addons do not always agree on who is
    # the root) rather than failing the whole book.
    while cycle := graph.find_cycle():
        logger.warning(
            "[tolerance] broke a category parent cycle: %s",
            " -> ".join(f"{u} -> {v}" for u, v in cycle),
        )
        for u, v in cycle:
            if graph.has_edge(u, v):
                graph.remove_edge(u, v)

    # A parent that never loaded (dropped above, or shipped by an absent mod)
    # would make hexdoc raise; move its children to the root instead.
    for parent_id in [node for node in graph.nodes if node not in categories]:
        children = [child for _, child in graph.iter_out_edges(parent_id)]
        key = _book_key(book_id, lang, "categories", parent_id)
        if _is_strict(key, book_ns):
            raise ValueError(
                f"Parent category {parent_id} required by "
                f"{', '.join(str(child) for child in children)} does not exist"
            )
        logger.warning(
            "[tolerance] missing parent category %s (required by %s); "
            "those categories move to the root",
            parent_id,
            ", ".join(str(child) for child in children) or "-",
        )
        for child in children:
            if child in categories:
                categories[child].parent_id = None
        graph.remove_node(parent_id)

    # late-init _parent_cmp_key, exactly like hexdoc
    for parent_id in graph.topological_sort():
        parent = categories.get(parent_id)
        if parent is None:
            continue
        for _, child_id in graph.iter_out_edges(parent_id):
            if child_id in categories:
                categories[child_id]._parent_cmp_key = parent._cmp_key

    for id, category in sorted_dict(categories).items():
        self._categories[id] = category

        link_base = book_ctx.get_link_base(category.resource_dir)
        book_ctx.book_links[category.book_link_key] = link_base.with_fragment(
            category.fragment
        )


# --------------------------------------------------------------------------- #
# entries
# --------------------------------------------------------------------------- #


def _salvage_pages(data: dict, context: dict) -> tuple[dict, list[Exception]] | None:
    """Re-validate an entry's pages one by one, dropping the ones that fail.

    Returns the rewritten entry data and the dropped page errors, or ``None``
    when the entry has no page list to salvage from.
    """
    from hexdoc.patchouli.page import Page

    pages = data.get("pages")
    if not isinstance(pages, list) or not pages:
        return None

    kept: list[Any] = []
    errors: list[Exception] = []
    for page in pages:
        try:
            # Defensive copy: page validation normalizes the raw dict in place.
            Page.model_validate(copy.deepcopy(page), context=context)
        except Exception as exc:  # noqa: BLE001
            errors.append(exc)
        else:
            kept.append(page)

    if not errors:
        return None

    return data | {"pages": kept}, errors


def _load_entries(self, context: Any, book_ctx: Any, loader: Any) -> None:
    """``Book._load_entries`` with per-entry and per-page tolerance.

    Mirrors ``hexdoc/patchouli/book.py`` (hexdoc 1!0.1.0a40). Differences: a
    page that fails to validate is removed from its entry (warning), an entry
    that still fails is skipped (warning), and an entry whose category never
    loaded keeps its anchors but is not attached to the table of contents.
    """
    from collections import defaultdict

    from hexdoc.patchouli.entry import Entry
    from hexdoc.utils import cast_context, sorted_dict

    book_id = book_ctx.book_id
    book_ns = book_id.namespace
    lang = loader.props.default_lang
    ctx = cast_context(context)

    internal_entries = defaultdict[Any, dict[Any, Any]](dict)
    spoilered_categories = dict[Any, bool]()

    for resource_dir, id, data in loader.load_book_assets(
        book_id,
        "entries",
        self.use_resource_pack,
    ):
        key = _book_key(book_id, lang, "entries", id)
        strict = _is_strict(key, book_ns)
        owner = _owner(key)

        try:
            entry = Entry.load(resource_dir, id, data, ctx)
        except Exception as exc:  # noqa: BLE001
            if strict:
                raise
            entry = None
            salvaged = _salvage_pages(data, ctx)
            if salvaged is None:
                _warn("entry", key, exc, owner)
            else:
                new_data, page_errors = salvaged
                for page_error in page_errors:
                    _warn("page", key, page_error, owner)
                try:
                    entry = Entry.load(resource_dir, id, new_data, ctx)
                except Exception as exc2:  # noqa: BLE001
                    _warn("entry", key, exc2, owner)

        if entry is None:
            continue

        if not entry.is_flag_enabled:
            logger.info("Skipping entry %s due to disabled flag %s", id, entry.flag)
            continue

        spoilered_categories[entry.category_id] = (
            entry.is_spoiler and spoilered_categories.get(entry.category_id, True)
        )

        if resource_dir.internal:
            internal_entries[entry.category_id][entry.id] = entry
        self._all_entries[entry.id] = entry

        link_base = book_ctx.get_link_base(resource_dir)
        book_ctx.book_links[entry.book_link_key] = link_base.with_fragment(
            entry.fragment
        )

        for page in entry.pages:
            page_key = page.book_link_key(entry.book_link_key)
            page_fragment = page.fragment(entry.fragment)
            if page_key is not None and page_fragment is not None:
                book_ctx.book_links[page_key] = link_base.with_fragment(page_fragment)

    # Entries whose category never loaded (dropped above, or an addon forgot to
    # ship it) cannot be listed, but they keep their page anchors so links into
    # them still resolve instead of degrading to plain text.
    for category_id in [cid for cid in internal_entries if cid not in self._categories]:
        entries = internal_entries.pop(category_id)
        marker = f"categories/{category_id}"
        if marker not in _warned:
            _warned.add(marker)
            logger.warning(
                "[tolerance] %d entr(ies) (%s) belong to the missing category %s; "
                "they are not listed in the table of contents",
                len(entries),
                ", ".join(str(entry_id) for entry_id in list(entries)[:3]),
                category_id,
            )
        for entry_id, entry in entries.items():
            self._all_entries.setdefault(entry_id, entry)

    if not internal_entries:
        raise ValueError(
            f"No internal entries found for book {book_id}, is this the correct id?"
        )

    for category_id, new_entries in internal_entries.items():
        category = self._categories[category_id]
        category.entries = sorted_dict(category.entries | new_entries)
        if is_spoiler := spoilered_categories.get(category.id):
            category.is_spoiler = is_spoiler

    _report(book_id)


# --------------------------------------------------------------------------- #
# links
# --------------------------------------------------------------------------- #


def _patch_links() -> None:
    """Make a dangling ``$(l:...)`` resolve to nothing instead of raising."""
    from jinja2 import pass_context

    from hexdoc.patchouli.text import LinkStyle

    if getattr(LinkStyle, "_genhexbook_patched", False):
        return

    def report(book_link: Any) -> None:
        key = book_link.book_links_key
        if key in _warned:
            return
        _warned.add(key)
        _counts["link"] += 1
        logger.warning(
            "[tolerance] broken link %s (target %s): rendered as plain text",
            book_link.raw_value,
            key,
        )

    @pass_context
    def href(self, context):  # noqa: ANN001, ANN202
        value = self.value
        if isinstance(value, str):
            return value
        key = value.book_links_key
        book_links = context["book_links"]
        if key not in book_links:
            report(value)
            return ""
        return str(book_links[key])

    @pass_context
    def is_broken_link(self, context) -> bool:  # noqa: ANN001
        value = self.value
        if isinstance(value, str):
            return False
        if value.book_links_key not in context["book_links"]:
            report(value)
            return True
        return False

    LinkStyle.href = href
    LinkStyle.is_broken_link = is_broken_link
    LinkStyle._genhexbook_patched = True


# --------------------------------------------------------------------------- #
# templates
# --------------------------------------------------------------------------- #


def _patch_missing_page_templates() -> None:
    """Render an addon page type that has no template as nothing, not as a crash.

    ``pages.register_runtime_page_stubs`` lets hexdoc *parse* page types it does
    not know (addons invent their own), but parsing is only half of it: the
    entry template still does ``{% include page.template ~ ".html.jinja" %}``,
    and a page type nobody wrote a template for aborts the render of the whole
    book. Those pages are dropped from the rendered output instead.
    """
    from jinja2 import TemplateNotFound

    from hexdoc.jinja.render import HexdocTemplateLoader

    if getattr(HexdocTemplateLoader, "_genhexbook_patched", False):
        return

    original_get_source = HexdocTemplateLoader.get_source

    def get_source(self, environment, template):  # noqa: ANN001, ANN202
        try:
            return original_get_source(self, environment, template)
        except TemplateNotFound:
            if not template.startswith("pages/"):
                raise
            marker = f"template:{template}"
            if marker not in _warned:
                _warned.add(marker)
                _counts["page-template"] += 1
                logger.warning(
                    "[tolerance] no template for page type %s: those pages render "
                    "as nothing (addon page type that hexdoc cannot display)",
                    template.removesuffix(".html.jinja").removeprefix("pages/"),
                )
            return "", None, lambda: True

    HexdocTemplateLoader.get_source = get_source
    HexdocTemplateLoader._genhexbook_patched = True


# --------------------------------------------------------------------------- #
# install
# --------------------------------------------------------------------------- #


def install() -> None:
    """Install every hook. Safe to call more than once."""
    from hexdoc.patchouli.book import Book

    if getattr(Book, "_genhexbook_tolerance", False):
        return

    Book._load_categories = _load_categories
    Book._load_entries = _load_entries
    Book._genhexbook_tolerance = True

    _patch_links()
    _patch_missing_page_templates()

    try:
        from hexdoc.__version__ import VERSION as hexdoc_version
    except Exception:  # noqa: BLE001
        try:
            from hexdoc import __version__ as hexdoc_version  # type: ignore[attr-defined]
        except Exception:  # noqa: BLE001
            hexdoc_version = "unknown"
    if hexdoc_version != TESTED_HEXDOC_VERSION:
        logger.warning(
            "[tolerance] written against hexdoc %s, running %s: re-check "
            "Book._load_categories / Book._load_entries / LinkStyle",
            TESTED_HEXDOC_VERSION,
            hexdoc_version,
        )
