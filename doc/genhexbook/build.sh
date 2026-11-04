#!/usr/bin/env bash
# Build the static hexdoc site for a dumped /genHexBook project.
#
# Usage:
#   ./build.sh [project-dir]
#
# The project dir must contain hexdoc.toml, resources/, patterns-registry.json,
# src/ (this companion) and an .env file (see .env.template).
# The site is written to <project-dir>/_site.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "${1:-$HERE}" && pwd)"
HEXDOC="${HEXDOC:-hexdoc}"

cd "$PROJECT"

if ! command -v "$HEXDOC" >/dev/null 2>&1; then
    echo "hexdoc not found: $HEXDOC" >&2
    echo "Install it with: python3 -m venv .venv && .venv/bin/pip install -r requirements.txt" >&2
    exit 1
fi

# The livehexbook plugin in src/ is optional (modid=hexcasting uses the
# installed hexdoc-hexcasting plugin), but keeping it on PYTHONPATH is harmless.
PYTHONPATH="$PROJECT/src${PYTHONPATH:+:$PYTHONPATH}" "$HEXDOC" build --branch main

echo
echo "Site written to: $PROJECT/_site"
echo "Book:            $PROJECT/_site/src/docs/v/latest/main/index.html"
