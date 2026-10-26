#!/usr/bin/env python3
"""Generate / verify ``hexcli-installer/manifest/hexcli-manifest.json``.

The manifest is embedded in the installer jar and lists everything the installer
downloads: the Fabric server launcher plus one entry per runtime mod. Modrinth
entries are derived from ``hexcli-installer/manifest/mods.lock.json`` (a list of
``{"name", "project", "versionId"}``); MieLib itself is a GitHub release asset and
is passed in explicitly with ``--mielib-jar`` / ``--mielib-url``.

Usage
-----
    # normal (re)generation, e.g. in CI after :fabric:build
    python3 hexcli-installer/tools/gen-manifest.py \
        --mielib-jar fabric/build/libs/mielib-fabric-0.0.2.1+1.20.1.jar \
        --mielib-url https://github.com/xm1221/MieLib/releases/download/hexcli-v1.0.0/mielib-fabric-0.0.2.1+1.20.1.jar

    # CI / maintenance check: fail if the checked-in manifest drifted from Modrinth
    python3 hexcli-installer/tools/gen-manifest.py --check

Only the Python standard library is used. This script is a build-time tool; it is
not shipped in the installer archive.

Note: --check always re-resolves the Modrinth entries, so it needs network access
(CI does). The MieLib entry is only compared when --mielib-jar is given, because its
hashes cannot be recomputed from Modrinth.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request

MODRINTH_API = "https://api.modrinth.com/v2"
FABRIC_META_API = "https://meta.fabricmc.net/v2"
USER_AGENT = "xm1221-MieLib-hexcli-installer/1.0 (+https://github.com/xm1221/MieLib)"

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(HERE)          # hexcli-installer/
REPO_ROOT = os.path.dirname(PROJECT_DIR)     # repo root
DEFAULT_LOCK = os.path.join(PROJECT_DIR, "manifest", "mods.lock.json")
DEFAULT_OUT = os.path.join(PROJECT_DIR, "manifest", "hexcli-manifest.json")

# Every entry in mods.lock.json is required: the server does not start without it (Hex Casting and
# its hard dependencies) or HexCLI itself is unusable (hexParse). "required" is informational for
# readers of the lock file; the installer downloads all of them unconditionally.

# Keys compared by --check. Order matters only for readable output.
MOD_COMPARE_KEYS = ("project", "versionId", "fileName", "url", "sha512", "sha1", "size")


class ManifestError(RuntimeError):
    pass


def http_json(url: str) -> object:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        raise ManifestError(f"GET {url} failed: HTTP {e.code} {e.reason}") from e
    except urllib.error.URLError as e:
        raise ManifestError(f"GET {url} failed: {e.reason}") from e


def file_hashes(path: str) -> tuple[str, str, int]:
    """Return (sha512, sha1, size) for a local file."""
    sha512 = hashlib.sha512()
    sha1 = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            sha512.update(chunk)
            sha1.update(chunk)
    return sha512.hexdigest(), sha1.hexdigest(), os.path.getsize(path)


def toml_version(path: str, key: str) -> str:
    """Read a plain ``key = "value"`` entry from gradle/libs.versions.toml.

    The file is TOML, but pulling in a TOML parser just for two scalars is not
    worth it; the version keys we need are simple quoted strings.
    """
    try:
        with open(path, encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        raise ManifestError(f"cannot read {path}: {e}") from e
    m = re.search(r'^\s*' + re.escape(key) + r'\s*=\s*"([^"]+)"', text, re.MULTILINE)
    if not m:
        raise ManifestError(f"no {key} = \"...\" in {path}")
    return m.group(1)


def properties_version(path: str, key: str) -> str:
    """Read a ``key=value`` entry from gradle.properties (values are unquoted there)."""
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith(key + "="):
                    return line.split("=", 1)[1].strip()
    except OSError as e:
        raise ManifestError(f"cannot read {path}: {e}") from e
    raise ManifestError(f"no {key}=... in {path}")


def latest_fabric_installer() -> str:
    versions = http_json(f"{FABRIC_META_API}/versions/installer")
    for v in versions:
        if v.get("stable"):
            return v["version"]
    if versions:
        return versions[0]["version"]
    raise ManifestError("Fabric meta returned no installer versions")


def load_lock(path: str) -> list[dict]:
    try:
        with open(path, encoding="utf-8") as f:
            lock = json.load(f)
    except OSError as e:
        raise ManifestError(f"cannot read {path}: {e}") from e
    except json.JSONDecodeError as e:
        raise ManifestError(f"bad JSON in {path}: {e}") from e
    if not isinstance(lock, list):
        raise ManifestError(f"{path} must contain a JSON array")
    seen = set()
    for i, entry in enumerate(lock):
        for key in ("name", "project", "versionId"):
            if not isinstance(entry.get(key), str) or not entry[key]:
                raise ManifestError(f"{path}: entry #{i} is missing a non-empty {key!r}")
        if entry["name"] in seen:
            raise ManifestError(f"{path}: duplicate mod name {entry['name']!r}")
        seen.add(entry["name"])
    return lock


def modrinth_entry(entry: dict, minecraft: str, project_cache: dict[str, dict]) -> dict:
    """Resolve one lock entry to a manifest mod entry (primary file, hashes)."""
    name = entry["name"]
    slug = entry["project"]
    version = http_json(f"{MODRINTH_API}/version/{entry['versionId']}")

    # Guard against a stale / mistyped lock entry pointing at a different project.
    project = project_cache.get(slug)
    if project is None:
        project = http_json(f"{MODRINTH_API}/project/{slug}")
        project_cache[slug] = project
    if project.get("id") != version.get("project_id"):
        raise ManifestError(
            f"{name}: version {entry['versionId']} belongs to project {version.get('project_id')}, "
            f"not {slug} ({project.get('id')})"
        )
    if minecraft not in version.get("game_versions", []):
        raise ManifestError(f"{name}: version {version['version_number']} does not support Minecraft {minecraft}")
    if "fabric" not in version.get("loaders", []):
        raise ManifestError(f"{name}: version {version['version_number']} is not a Fabric build")

    files = [f for f in version.get("files", []) if f.get("primary")] or version.get("files", [])
    if not files:
        raise ManifestError(f"{name}: version {version['version_number']} has no files")
    f = files[0]
    hashes = f.get("hashes", {})
    if "sha512" not in hashes:
        raise ManifestError(f"{name}: Modrinth did not provide a sha512 for {f['filename']}")

    out = {
        "name": name,
        "source": "modrinth",
        "project": slug,
        "versionId": version["id"],
        "fileName": f["filename"],
        "url": f["url"],
        "sha512": hashes["sha512"],
        "size": f["size"],
    }
    if hashes.get("sha1"):
        out["sha1"] = hashes["sha1"]
    return out


def mielib_entry(jar: str, url: str, fallback_urls: list[str]) -> dict:
    if not os.path.isfile(jar):
        raise ManifestError(f"--mielib-jar does not exist: {jar}")
    sha512, sha1, size = file_hashes(jar)
    return {
        "name": "mielib",
        "source": "github",
        "project": "xm1221/MieLib",
        "fileName": os.path.basename(jar),
        "url": url,
        "sha512": sha512,
        "sha1": sha1,
        "size": size,
        "fallbackUrls": fallback_urls,
    }


def build_manifest(args: argparse.Namespace) -> dict:
    minecraft = args.minecraft or toml_version(os.path.join(REPO_ROOT, "gradle", "libs.versions.toml"), "minecraft")
    loader = args.loader or toml_version(os.path.join(REPO_ROOT, "gradle", "libs.versions.toml"), "fabric-loader")
    installer = args.installer or latest_fabric_installer()

    lock = load_lock(args.lock)
    project_cache: dict[str, dict] = {}
    mods = []
    if args.mielib_jar and args.mielib_url:
        mods.append(mielib_entry(args.mielib_jar, args.mielib_url, args.mielib_fallback))
    elif args.mielib_jar or args.mielib_url:
        raise ManifestError("--mielib-jar and --mielib-url must be given together")
    for entry in lock:
        mods.append(modrinth_entry(entry, minecraft, project_cache))

    return {
        "installerVersion": args.installer_version,
        "minecraft": minecraft,
        "fabricLoader": loader,
        "fabricInstaller": installer,
        "serverLauncher": {
            "url": (
                f"{FABRIC_META_API}/versions/loader/{minecraft}/{loader}/{installer}/server/jar"
            ),
            "fileName": "fabric-server-launch.jar",
        },
        "mods": mods,
    }


def check(args: argparse.Namespace, expected_mods: list[dict]) -> int:
    """Compare Modrinth-derived entries (and optionally MieLib) with the checked-in manifest."""
    try:
        with open(args.out, encoding="utf-8") as f:
            current = json.load(f)
    except OSError as e:
        print(f"error: cannot read {args.out}: {e}", file=sys.stderr)
        return 1
    except json.JSONDecodeError as e:
        print(f"error: bad JSON in {args.out}: {e}", file=sys.stderr)
        return 1

    current_by_name = {m.get("name"): m for m in current.get("mods", [])}
    expected_by_name = {m["name"]: m for m in expected_mods}
    drift: list[str] = []

    for name, exp in expected_by_name.items():
        got = current_by_name.get(name)
        if got is None:
            drift.append(f"{name}: missing from the manifest")
            continue
        for key in MOD_COMPARE_KEYS:
            if got.get(key) != exp.get(key):
                drift.append(f"{name}.{key}: manifest={got.get(key)!r} modrinth={exp.get(key)!r}")

    # MieLib is only checked when a local jar was supplied (CI does this). Without it we
    # cannot recompute its hashes, so it must not be reported as drift.
    check_mielib = args.mielib_jar is not None
    if check_mielib and "mielib" not in expected_by_name:
        drift.append("internal: --mielib-jar given but no mielib entry was generated")

    for name in current_by_name:
        if name in expected_by_name:
            continue
        if name == "mielib" and not check_mielib:
            continue
        drift.append(f"{name}: present in the manifest but not in mods.lock.json")

    if drift:
        print("manifest drift detected (run gen-manifest.py to update):", file=sys.stderr)
        for line in drift:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print(f"{args.out}: up to date ({len(expected_by_name)} mods checked)")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Generate the HexCLI installer manifest from Modrinth + a local MieLib jar.")
    p.add_argument("--lock", default=DEFAULT_LOCK, help=f"mods.lock.json (default: {DEFAULT_LOCK})")
    p.add_argument("--out", default=DEFAULT_OUT, help=f"manifest output (default: {DEFAULT_OUT})")
    p.add_argument("--minecraft", default=None, help="Minecraft version (default: from libs.versions.toml)")
    p.add_argument("--loader", default=None, help="Fabric loader version (default: from libs.versions.toml)")
    p.add_argument("--installer", default=None, help="Fabric installer version (default: latest stable from Fabric meta)")
    p.add_argument("--installer-version", default=None, help="installerVersion field (default: hexcliInstallerVersion from gradle.properties)")
    p.add_argument("--mielib-jar", default=None, help="built MieLib Fabric jar (adds the GitHub-sourced mielib entry)")
    p.add_argument("--mielib-url", default=None, help="public download URL of that jar (GitHub release asset)")
    p.add_argument("--mielib-fallback", action="append", default=[], help="extra fallback URL for MieLib (repeatable)")
    p.add_argument("--check", action="store_true", help="only verify the checked-in manifest against Modrinth; do not write")
    args = p.parse_args(argv)

    if args.installer_version is None:
        args.installer_version = properties_version(
            os.path.join(REPO_ROOT, "gradle.properties"), "hexcliInstallerVersion"
        )

    try:
        if args.check:
            # Re-resolve the Modrinth entries, but never write and never require a MieLib jar.
            lock = load_lock(args.lock)
            minecraft = args.minecraft or toml_version(
                os.path.join(REPO_ROOT, "gradle", "libs.versions.toml"), "minecraft"
            )
            project_cache: dict[str, dict] = {}
            expected = [modrinth_entry(e, minecraft, project_cache) for e in lock]
            if args.mielib_jar:
                if not args.mielib_url:
                    raise ManifestError("--check with --mielib-jar also needs --mielib-url")
                expected.insert(0, mielib_entry(args.mielib_jar, args.mielib_url, args.mielib_fallback))
            return check(args, expected)

        manifest = build_manifest(args)
        with open(args.out, "w", encoding="utf-8", newline="\n") as f:
            json.dump(manifest, f, indent=2, ensure_ascii=False)
            f.write("\n")
        print(f"wrote {args.out} ({len(manifest['mods'])} mods, Minecraft {manifest['minecraft']}, "
              f"loader {manifest['fabricLoader']}, installer {manifest['fabricInstaller']})")
        return 0
    except ManifestError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
