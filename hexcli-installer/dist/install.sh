#!/bin/sh
# HexCLI one-click installer - Linux / macOS bootstrap.
#
# Downloads a private Temurin JRE 17 into ./runtime (never touching the system Java or PATH),
# then runs hexcli-installer.jar to download and verify the Fabric server launcher and mods.
#
# Usage:
#   ./install.sh [-y] [--dir <server dir>] [--manifest <file|url>]
#                [--mod-override name=path] [--java <path>]
#
#   -y, --yes, --accept-eula   accept https://aka.ms/MinecraftEULA without asking
#   --dir <dir>                server directory (default: server, next to this script)
#   --manifest <file|url>      use a different installer manifest
#   --mod-override name=path   use a local jar for one mod (still hash-checked)
#   --java <path>              use this Java instead of downloading / reusing ./runtime
#
# Environment:
#   HEXCLI_ACCEPT_EULA=1       same as -y
#   HEXCLI_JAVA=/path/to/java  same as --java
#   HEXCLI_MIELIB_JAR=<path>   shorthand for --mod-override mielib=<path>
#   HEXCLI_ADOPTIUM_API=<url>  override the Adoptium API base (mirrors / tests)
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$DIR"

ADOPTIUM=${HEXCLI_ADOPTIUM_API:-https://api.adoptium.net/v3}
RUNTIME="$DIR/runtime"
SERVER_DIR="server"
ACCEPT=0
MANIFEST=""
MOD_OVERRIDE=""
JAVA_OVERRIDE=${HEXCLI_JAVA:-}

if [ "${HEXCLI_ACCEPT_EULA:-}" = "1" ] || [ "${HEXCLI_ACCEPT_EULA:-}" = "true" ]; then
    ACCEPT=1
fi

while [ $# -gt 0 ]; do
    case "$1" in
        -y|--yes|--accept-eula) ACCEPT=1 ;;
        --dir)
            [ $# -ge 2 ] || { echo "error: --dir needs a value" >&2; exit 2; }
            SERVER_DIR=$2; shift ;;
        --dir=*) SERVER_DIR=${1#*=} ;;
        --manifest)
            [ $# -ge 2 ] || { echo "error: --manifest needs a value" >&2; exit 2; }
            MANIFEST=$2; shift ;;
        --manifest=*) MANIFEST=${1#*=} ;;
        --mod-override)
            [ $# -ge 2 ] || { echo "error: --mod-override needs name=path" >&2; exit 2; }
            MOD_OVERRIDE=$2; shift ;;
        --mod-override=*) MOD_OVERRIDE=${1#*=} ;;
        --java)
            [ $# -ge 2 ] || { echo "error: --java needs a value" >&2; exit 2; }
            JAVA_OVERRIDE=$2; shift ;;
        --java=*) JAVA_OVERRIDE=${1#*=} ;;
        -h|--help)
            sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *)
            echo "error: unknown option '$1' (try --help)" >&2
            exit 2 ;;
    esac
    shift
done

# ---- helpers ---------------------------------------------------------------

fetch() { # fetch <url> <output file>
    if command -v curl >/dev/null 2>&1; then
        # -sS: no progress bar, but still print errors.
        curl -fsSL --retry 3 --connect-timeout 30 -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$2" "$1"
    else
        echo "error: need curl or wget to download the Java runtime" >&2
        return 1
    fi
}

# First value of a "key": "value" pair in a JSON document (Adoptium responses are pretty-printed).
json_value() { # json_value <file> <key>
    tr -d '\r' < "$1" | grep -o "\"$2\": *\"[^\"]*\"" | head -n 1 | sed 's/^[^:]*: *"//; s/"$//'
}

sha256_of() { # sha256_of <file> -> lowercase hex on stdout
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$1" | sed 's/.*= *//'
    else
        echo "error: need sha256sum, shasum or openssl to verify the Java download" >&2
        return 1
    fi
}

java_major() { # java_major <java executable> -> major version number
    "$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
}

runtime_java() { # runtime_java -> path to a Java 17+ inside ./runtime, or nothing
    for candidate in "$RUNTIME/bin/java" "$RUNTIME/Contents/Home/bin/java"; do
        if [ -x "$candidate" ]; then
            major=$(java_major "$candidate" || true)
            if [ -n "$major" ] && [ "$major" -ge 17 ] 2>/dev/null; then
                printf '%s\n' "$candidate"
                return 0
            fi
        fi
    done
    return 1
}

detect_os() {
    case "$(uname -s)" in
        Linux) echo linux ;;
        Darwin) echo mac ;;
        *) echo "error: unsupported operating system $(uname -s); use install.ps1 on Windows" >&2; exit 1 ;;
    esac
}

detect_arch() {
    case "$(uname -m)" in
        x86_64|amd64) echo x64 ;;
        aarch64|arm64) echo aarch64 ;;
        *) echo "error: unsupported CPU architecture $(uname -m)" >&2; exit 1 ;;
    esac
}

# ---- 1. private Java 17 ----------------------------------------------------

JAVA=""
if [ -n "$JAVA_OVERRIDE" ]; then
    [ -x "$JAVA_OVERRIDE" ] || { echo "error: --java/HEXCLI_JAVA is not executable: $JAVA_OVERRIDE" >&2; exit 1; }
    JAVA=$JAVA_OVERRIDE
    echo "using Java from HEXCLI_JAVA: $JAVA"
else
    JAVA=$(runtime_java || true)
fi

if [ -z "$JAVA" ]; then
    OS=$(detect_os)
    ARCH=$(detect_arch)
    echo "no Java 17+ in ./runtime; downloading Temurin JRE 17 for $OS/$ARCH"
    echo "(this is a private runtime in ./runtime - the system Java and PATH are not touched)"

    TMP=$(mktemp -d "${TMPDIR:-/tmp}/hexcli-install.XXXXXX")
    trap 'rm -rf "$TMP"' EXIT INT TERM

    ASSETS="$TMP/assets.json"
    fetch "$ADOPTIUM/assets/latest/17/hotspot?os=$OS&architecture=$ARCH&image_type=jre" "$ASSETS" \
        || { echo "error: could not query the Adoptium API" >&2; exit 1; }
    CHECKSUM=$(json_value "$ASSETS" checksum)
    if [ -z "$CHECKSUM" ]; then
        echo "error: no checksum in the Adoptium response" >&2
        exit 1
    fi

    ARCHIVE="$TMP/temurin-jre.tar.gz"
    fetch "$ADOPTIUM/binary/latest/17/ga/$OS/$ARCH/jre/hotspot/normal/eclipse" "$ARCHIVE" \
        || { echo "error: could not download the Temurin JRE" >&2; exit 1; }

    ACTUAL=$(sha256_of "$ARCHIVE")
    if [ "$ACTUAL" != "$CHECKSUM" ]; then
        echo "error: Java runtime checksum mismatch" >&2
        echo "       expected $CHECKSUM" >&2
        echo "       got      $ACTUAL" >&2
        exit 1
    fi
    echo "Java runtime checksum OK (sha256 $ACTUAL)"

    EXTRACT="$TMP/extract"
    mkdir -p "$EXTRACT"
    tar -xzf "$ARCHIVE" -C "$EXTRACT"
    # Replace any stale runtime only after the new one downloaded and verified.
    rm -rf "$RUNTIME"
    mkdir -p "$RUNTIME"
    if [ -d "$EXTRACT"/*/Contents/Home ]; then
        # macOS bundle layout -> runtime/Contents/Home/bin/java
        mv "$EXTRACT"/*/Contents "$RUNTIME"/
    else
        cp -R "$EXTRACT"/*/. "$RUNTIME"/
    fi
    chmod +x "$RUNTIME/bin/java" 2>/dev/null || chmod +x "$RUNTIME/Contents/Home/bin/java" 2>/dev/null || true

    JAVA=$(runtime_java || true)
    [ -n "$JAVA" ] || { echo "error: the downloaded runtime has no usable bin/java" >&2; exit 1; }
    echo "Java installed: $JAVA ($("$JAVA" -version 2>&1 | head -n 1))"
fi

# ---- 2. EULA ---------------------------------------------------------------

if [ "$ACCEPT" != "1" ]; then
    echo
    echo "Minecraft requires you to accept the EULA to run a server:"
    echo "  https://aka.ms/MinecraftEULA"
    if [ -t 0 ]; then
        printf 'Do you accept the Minecraft EULA? [y/N] '
        read -r ANSWER || ANSWER=""
        case "$ANSWER" in
            y|Y|yes|YES) ACCEPT=1 ;;
            *) echo "EULA not accepted; nothing was installed."; exit 1 ;;
        esac
    else
        echo "error: not running interactively; re-run with -y or set HEXCLI_ACCEPT_EULA=1" >&2
        exit 1
    fi
fi

# ---- 3. run the installer --------------------------------------------------

[ -f "$DIR/hexcli-installer.jar" ] || { echo "error: hexcli-installer.jar is missing next to install.sh" >&2; exit 1; }
[ -f "$DIR/hexcli-client.jar" ] || { echo "error: hexcli-client.jar is missing next to install.sh" >&2; exit 1; }

set -- install --dir "$SERVER_DIR" --client-jar "$DIR/hexcli-client.jar" --java "$JAVA" --accept-eula
if [ -n "$MANIFEST" ]; then
    set -- "$@" --manifest "$MANIFEST"
fi
if [ -n "$MOD_OVERRIDE" ]; then
    set -- "$@" --mod-override "$MOD_OVERRIDE"
fi

echo
echo "running the HexCLI installer..."
"$JAVA" -jar "$DIR/hexcli-installer.jar" "$@"

SERVER_ABS=$(CDPATH= cd -- "$SERVER_DIR" 2>/dev/null && pwd || printf '%s' "$SERVER_DIR")
cat <<EOF

Installation complete.

  server directory : $SERVER_ABS
  start + connect  : cd "$SERVER_ABS" && ./start-and-connect.sh
  server only      : cd "$SERVER_ABS" && ./start-server.sh
  client only      : cd "$SERVER_ABS" && ./connect.sh
  verify files     : "$JAVA" -jar "$DIR/hexcli-installer.jar" verify --dir "$SERVER_ABS"

The first server start downloads the vanilla Minecraft server from Mojang.
EOF
