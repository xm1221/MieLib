#!/usr/bin/env python3
"""Resolve doc/genhexbook/mods.yml into a Fabric server mods/ directory.

Downloads Modrinth, Maven, CurseForge, and direct-URL jars, checks an optional
sha512, then applies strip_nested. Jars in --local (mods-local) are copied
afterwards and replace a downloaded jar of the same filename. They are not
rewritten.

CurseForge entries need the CURSEFORGE_API_KEY environment variable. The key
is sent as a header and is never printed.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

try:
    import yaml
except ImportError:
    print("PyYAML is required: pip install -r doc/genhexbook/tools/requirements.txt", file=sys.stderr)
    sys.exit(1)

UA = "xm1221/MieLib genhexbook (+https://github.com/xm1221/MieLib)"
SOURCES = ("modrinth", "maven", "curse", "url")


def fail(message: str) -> None:
    raise SystemExit(message)


def http_json(url: str, headers: dict[str, str] | None = None) -> object:
    """GET JSON. Raises urllib.error.HTTPError / URLError — callers decide."""
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json", **(headers or {})})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def get_json(url: str, headers: dict[str, str] | None = None) -> object:
    try:
        return http_json(url, headers)
    except urllib.error.HTTPError as exc:
        fail(f"HTTP {exc.code} for {url.split('?', 1)[0]}")
    except urllib.error.URLError as exc:
        fail(f"request failed for {url.split('?', 1)[0]}: {exc.reason}")


def download(url: str, dest: Path, sha512: str | None) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    last_error: Exception | None = None
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(req, timeout=180) as resp, tmp.open("wb") as out:
                shutil.copyfileobj(resp, out)
            last_error = None
            break
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            last_error = exc
            time.sleep(2 * attempt)
    if last_error is not None:
        tmp.unlink(missing_ok=True)
        # Drop query strings so a CurseForge download token is not logged.
        fail(f"download failed for {dest.name}: {str(last_error).split('?', 1)[0]}")
    if sha512:
        digest = hashlib.sha512(tmp.read_bytes()).hexdigest()
        if digest.lower() != sha512.lower():
            tmp.unlink(missing_ok=True)
            fail(f"sha512 mismatch for {dest.name}")
    tmp.replace(dest)


def safe_filename(name: str, what: str) -> str:
    if not name or name != Path(name).name or "/" in name or "\\" in name or name in (".", ".."):
        fail(f"{what}: unsafe filename {name!r}")
    return name


def pick_modrinth_file(version: dict) -> tuple[str, str]:
    files = version.get("files") or []
    jars = [f for f in files if str(f.get("filename", "")).endswith(".jar")]
    pool = jars or files
    if not pool:
        fail(f"modrinth version {version.get('id')} has no files")
    chosen = next((f for f in pool if f.get("primary")), pool[0])
    url = chosen.get("url")
    filename = chosen.get("filename")
    if not url or not filename:
        fail(f"modrinth version {version.get('id')} file is missing url or filename")
    return url, filename


def resolve_modrinth(mod: dict, game: str, loader: str) -> tuple[str, str]:
    project = str(mod["project"])
    version = str(mod["version"])
    if len(version) == 8 and version.isalnum():
        try:
            body = http_json(f"https://api.modrinth.com/v2/version/{urllib.parse.quote(version)}")
        except urllib.error.HTTPError as exc:
            if exc.code != 404:
                fail(f"{mod['name']}: HTTP {exc.code} looking up Modrinth version {version}")
            body = None
        else:
            if isinstance(body, dict):
                return pick_modrinth_file(body)
    query = urllib.parse.urlencode({
        "game_versions": json.dumps([game]),
        "loaders": json.dumps([loader]),
    })
    try:
        listed = http_json(
            f"https://api.modrinth.com/v2/project/{urllib.parse.quote(project)}/version?{query}"
        )
    except urllib.error.HTTPError as exc:
        fail(f"{mod['name']}: HTTP {exc.code} listing Modrinth project {project}")
    except urllib.error.URLError as exc:
        fail(f"{mod['name']}: Modrinth request failed: {exc.reason}")
    if not isinstance(listed, list):
        fail(f"{mod['name']}: unexpected Modrinth response")
    for item in listed:
        if item.get("id") == version or item.get("version_number") == version:
            return pick_modrinth_file(item)
    fail(f"{mod['name']}: Modrinth version {version!r} not found for {game} {loader}")


def resolve_maven(mod: dict) -> tuple[str, str]:
    repo = str(mod["repo"]).rstrip("/")
    group = str(mod["group"]).strip(".").replace(".", "/")
    artifact = str(mod["artifact"])
    version = str(mod["version"])
    extension = str(mod.get("extension") or "jar")
    classifier = str(mod.get("classifier") or "")
    filename = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + f".{extension}"
    url = f"{repo}/{group}/{artifact}/{version}/{filename}"
    return url, filename


def resolve_curse(mod: dict) -> tuple[str, str]:
    key = os.environ.get("CURSEFORGE_API_KEY", "")
    if not key:
        fail(f"{mod['name']}: source curse requires CURSEFORGE_API_KEY (not printed)")
    project_id = mod["project_id"]
    file_id = mod["file_id"]
    body = get_json(
        f"https://api.curseforge.com/v1/mods/{project_id}/files/{file_id}/download-url",
        headers={"x-api-key": key},
    )
    if not isinstance(body, dict) or not isinstance(body.get("data"), str):
        fail(f"{mod['name']}: CurseForge did not return a download URL")
    url = body["data"]
    filename = str(mod.get("filename") or url.rstrip("/").split("/")[-1].split("?")[0])
    return url, filename


def resolve_url(mod: dict) -> tuple[str, str]:
    url = str(mod["url"])
    filename = str(mod.get("filename") or url.rstrip("/").split("/")[-1].split("?")[0])
    if not filename:
        fail(f"{mod['name']}: source url needs a filename")
    return url, filename


def resolve_remote(mod: dict, game: str, loader: str) -> tuple[str, str]:
    source = mod["source"]
    if source == "modrinth":
        return resolve_modrinth(mod, game, loader)
    if source == "maven":
        return resolve_maven(mod)
    if source == "curse":
        return resolve_curse(mod)
    if source == "url":
        return resolve_url(mod)
    fail(f"{mod['name']}: unknown source {source!r} (expected {' | '.join(SOURCES)})")


def nested_modid(data: bytes) -> str | None:
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as jar:
            if "fabric.mod.json" not in jar.namelist():
                return None
            meta = json.loads(jar.read("fabric.mod.json"))
    except (zipfile.BadZipFile, KeyError, json.JSONDecodeError, UnicodeError):
        return None
    modid = meta.get("id")
    return str(modid) if isinstance(modid, str) else None


def strip_nested(src: Path, dest: Path, modids: set[str]) -> list[str]:
    removed: list[str] = []
    with zipfile.ZipFile(src) as zin:
        names = zin.namelist()
        drop: set[str] = set()
        for name in names:
            if not name.startswith("META-INF/jars/") or not name.endswith(".jar"):
                continue
            if nested_modid(zin.read(name)) in modids:
                drop.add(name)
        fabric = None
        if drop and "fabric.mod.json" in names:
            fabric = json.loads(zin.read("fabric.mod.json"))
            kept = []
            for entry in fabric.get("jars") or []:
                if isinstance(entry, dict) and entry.get("file") in drop:
                    removed.append(str(entry.get("file")))
                    continue
                kept.append(entry)
            fabric["jars"] = kept
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_suffix(dest.suffix + ".strip")
        with zipfile.ZipFile(tmp, "w") as zout:
            for info in zin.infolist():
                if info.filename in drop or info.filename.endswith("/"):
                    continue
                payload = zin.read(info.filename)
                if info.filename == "fabric.mod.json" and fabric is not None:
                    payload = (json.dumps(fabric, indent=2) + "\n").encode("utf-8")
                zout.writestr(info, payload)
        tmp.replace(dest)
    return removed


def require(mod: dict, *keys: str) -> None:
    missing = [key for key in keys if mod.get(key) in (None, "")]
    if missing:
        fail(f"{mod.get('name', '?')}: missing {', '.join(missing)}")


def validate(mod: dict) -> None:
    require(mod, "name", "source")
    source = mod["source"]
    if source not in SOURCES:
        fail(f"{mod['name']}: unknown source {source!r}")
    if source == "modrinth":
        require(mod, "project", "version")
    elif source == "maven":
        require(mod, "repo", "group", "artifact", "version")
    elif source == "curse":
        require(mod, "project_id", "file_id")
    elif source == "url":
        require(mod, "url")
    if "filename" in mod and mod["filename"]:
        safe_filename(str(mod["filename"]), mod["name"])
    nested = mod.get("strip_nested") or []
    if not isinstance(nested, list) or not all(isinstance(item, str) and item for item in nested):
        fail(f"{mod['name']}: strip_nested must be a list of mod ids")


def enabled(mod: dict) -> bool:
    flag = mod.get("enabled", True)
    if not isinstance(flag, bool):
        fail(f"{mod.get('name', '?')}: enabled must be true or false")
    return flag


def load_manifest(path: Path) -> dict:
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        fail(f"cannot parse {path}: {exc}")
    if not isinstance(data, dict):
        fail(f"{path} must be a mapping")
    if data.get("loader") != "fabric":
        fail("loader must be fabric (this workflow boots a Fabric server)")
    for key in ("minecraft", "fabric_loader", "fabric_installer"):
        if not data.get(key):
            fail(f"manifest is missing {key}")
    mods = data.get("mods")
    if not isinstance(mods, list) or not mods:
        fail("mods must be a non-empty list")
    names: set[str] = set()
    for mod in mods:
        if not isinstance(mod, dict):
            fail("each mods entry must be a mapping")
        if not enabled(mod):
            if not mod.get("name"):
                fail("a disabled mod still needs a name")
            continue
        validate(mod)
        if mod["name"] in names:
            fail(f"duplicate mod name {mod['name']}")
        names.add(mod["name"])
    if "hexcasting" not in names:
        print("warning: no enabled mod named hexcasting", file=sys.stderr)
    return data


def place(src: Path, dest: Path, nested: list[str]) -> None:
    if nested:
        removed = strip_nested(src, dest, set(nested))
        if removed:
            print(f"  stripped nested {', '.join(removed)}")
        elif not dest.exists():
            fail(f"strip_nested produced no jar for {dest.name}")
    else:
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dest)


def main() -> int:
    parser = argparse.ArgumentParser(description="Download the GenHexBook mods list")
    parser.add_argument("--manifest", type=Path, default=Path("doc/genhexbook/mods.yml"))
    parser.add_argument("--local", type=Path, default=Path("doc/genhexbook/mods-local"))
    parser.add_argument("--out", type=Path, required=True, help="Server mods directory")
    parser.add_argument("--server-jar", type=Path, help="Where to write the Fabric server launcher")
    parser.add_argument(
        "--cache",
        type=Path,
        help="Download cache (default: sibling of --out, never inside the mods folder)",
    )
    args = parser.parse_args()

    manifest = load_manifest(args.manifest)
    game = str(manifest["minecraft"])
    loader = str(manifest["loader"])
    # Keep the cache out of mods/. Fabric loads every *.jar directly in that folder;
    # a nested MieLib kept for the sha512 cache must not sit beside the stripped jar.
    cache = args.cache or (args.out.parent / ".genhexbook-mod-cache")
    cache.mkdir(parents=True, exist_ok=True)
    args.out.mkdir(parents=True, exist_ok=True)

    filenames: dict[str, dict] = {}
    for mod in manifest["mods"]:
        if not enabled(mod):
            print(f"skip {mod['name']} (enabled: false)")
            continue
        url, remote_name = resolve_remote(mod, game, loader)
        filename = safe_filename(str(mod.get("filename") or remote_name), mod["name"])
        if filename in filenames:
            fail(f"duplicate filename {filename} ({mod['name']} and {filenames[filename]['name']})")
        filenames[filename] = mod
        cached = cache / filename
        sha512 = str(mod["sha512"]) if mod.get("sha512") else None
        if not (cached.is_file() and sha512 and hashlib.sha512(cached.read_bytes()).hexdigest().lower() == sha512.lower()):
            print(f"fetch {mod['name']} ({mod['source']}) -> {filename}")
            download(url, cached, sha512)
        else:
            print(f"cache {mod['name']} -> {filename}")
        place(cached, args.out / filename, list(mod.get("strip_nested") or []))

    if args.local.is_dir():
        for jar in sorted(args.local.glob("*.jar")):
            filename = safe_filename(jar.name, "mods-local")
            print(f"local {filename} (copied as-is; overrides a downloaded jar of the same name)")
            shutil.copyfile(jar, args.out / filename)

    if args.server_jar:
        launcher = (
            "https://meta.fabricmc.net/v2/versions/loader/"
            f"{manifest['minecraft']}/{manifest['fabric_loader']}/{manifest['fabric_installer']}/server/jar"
        )
        print(f"fetch fabric server launcher -> {args.server_jar.name}")
        download(launcher, args.server_jar, None)

    jars = sorted(p.name for p in args.out.glob("*.jar"))
    print(f"{len(jars)} jar(s) in {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
