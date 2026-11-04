#!/usr/bin/env python3
"""Rewrite hexdoc output so GitHub can store it.

hexdoc uses resource locations such as ``hexcasting:interop/...`` as directory
names. GitHub artifact upload and GitHub Pages reject ``:`` (and a few other
characters) because of NTFS. Cloudflare would accept them, but both hosts
receive the same tree, so the names are rewritten before either deploy.

Only path segments change (``:`` becomes ``_``). HTML/CSS/JS links that point
at those segments are updated, including the ``%3A`` form. A resource id that
is not followed by ``/`` is left alone.
"""

from __future__ import annotations

import sys
from pathlib import Path

FORBIDDEN = '":<>|*?\r\n'
TEXT_SUFFIXES = {
    ".html", ".css", ".js", ".mjs", ".json", ".xml", ".svg", ".txt", ".md",
    ".webmanifest", ".map",
}


def clean_segment(segment: str) -> str:
    out = segment
    for ch in FORBIDDEN:
        out = out.replace(ch, "_")
    return out


def clean_rel(rel: str) -> str:
    return "/".join(clean_segment(part) for part in rel.split("/"))


def rewrite_tree(site: Path) -> list[tuple[str, str]]:
    files = [p for p in site.rglob("*") if p.is_file()]
    mapping: list[tuple[str, str]] = []
    for path in files:
        rel = path.relative_to(site).as_posix()
        new_rel = clean_rel(rel)
        if new_rel == rel:
            continue
        dest = site / new_rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        path.rename(dest)
        mapping.append((rel, new_rel))
    # Drop directories left empty after the renames, deepest first.
    dirs = [p for p in site.rglob("*") if p.is_dir()]
    for directory in sorted(dirs, key=lambda p: len(p.parts), reverse=True):
        try:
            directory.rmdir()
        except OSError:
            pass
    return mapping


def segment_pairs(mapping: list[tuple[str, str]]) -> list[tuple[str, str]]:
    pairs: set[tuple[str, str]] = set()
    for old, new in mapping:
        for old_part, new_part in zip(old.split("/"), new.split("/")):
            if old_part != new_part:
                pairs.add((old_part, new_part))
    # Longer names first so a short segment cannot eat a longer one.
    return sorted(pairs, key=lambda item: len(item[0]), reverse=True)


def rewrite_text(site: Path, pairs: list[tuple[str, str]]) -> int:
    changed = 0
    for path in site.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        data = path.read_bytes()
        if b"\x00" in data:
            continue
        text = data.decode("utf-8", "surrogateescape")
        updated = text
        for old, new in pairs:
            updated = updated.replace(old + "/", new + "/")
            updated = updated.replace(old.replace(":", "%3A") + "/", new + "/")
            updated = updated.replace(old.replace(":", "%3a") + "/", new + "/")
        if updated != text:
            path.write_bytes(updated.encode("utf-8", "surrogateescape"))
            changed += 1
    return changed


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: sanitize_site_paths.py SITE_DIR", file=sys.stderr)
        return 2
    site = Path(sys.argv[1])
    if not site.is_dir():
        print(f"not a directory: {site}", file=sys.stderr)
        return 1
    mapping = rewrite_tree(site)
    changed = rewrite_text(site, segment_pairs(mapping))
    print(f"sanitized {len(mapping)} path(s), rewrote {changed} text file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
