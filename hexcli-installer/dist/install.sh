#!/bin/sh
# HexCLI one-click installer - Linux / macOS bootstrap.
#
# Downloads a private Temurin JRE 17 into ./runtime (never touching the system Java or PATH),
# then runs hexcli-installer.jar to download and verify the Fabric server launcher and mods.
#
# Usage:
#   ./install.sh [-y] [--dir <server dir>] [--java <java|JAVA_HOME dir>] [--mirror <name>] ...
#
#   -y, --yes, --accept-eula   accept https://aka.ms/MinecraftEULA without asking
#   --dir <dir>                server directory (default: server, next to this script)
#   --manifest <file|url>      use a different installer manifest
#   --mod-override name=path   use a local jar for one mod (still hash-checked)
#
# Java (first match wins; must be Java 17+):
#   --java <path>              a java executable or a JDK/JRE home directory
#   HEXCLI_JAVA=<path>         same as --java
#   JAVA_HOME                  used when it holds Java 17+ (otherwise ignored)
#   ./runtime                  the private runtime from an earlier run
#   (otherwise)                download Temurin JRE 17 into ./runtime (sha256-checked)
#
# Download source:
#   --mirror <name>            official (default) | bmclapi (aliases cn, mcim): Modrinth via
#                              mod.mcimirror.top, Fabric meta/maven via BMCLAPI. Official URLs are
#                              still tried if the mirror fails. (--download-source is an alias)
#   --url-rewrite from=to      custom mirror: replace URL prefix "from" with "to" (repeatable)
#   --github-proxy <url>       prefix GitHub downloads (MieLib jar and the Temurin JRE),
#                              e.g. https://ghfast.top/
#   --adoptium-api <url>       Adoptium API base for the JRE (default https://api.adoptium.net/v3)
#   --no-fallback              never fall back to the official URLs
#   Every mod is sha512-checked against the manifest, whatever the source.
#
# Environment:
#   HEXCLI_ACCEPT_EULA=1       same as -y
#   HEXCLI_MIRROR=<name>       same as --mirror
#   HEXCLI_URL_REWRITE=a=b;c=d same as --url-rewrite (';'-separated)
#   HEXCLI_GITHUB_PROXY=<url>  same as --github-proxy
#   HEXCLI_ADOPTIUM_API=<url>  same as --adoptium-api
#   HEXCLI_MIELIB_JAR=<path>   shorthand for --mod-override mielib=<path>
#   HEXCLI_PROGRESS=0          hide download progress bars
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
MIRROR=${HEXCLI_MIRROR:-}
URL_REWRITES=""
GITHUB_PROXY=${HEXCLI_GITHUB_PROXY:-}
NO_FALLBACK=0
NL='
'

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
        --mirror|--download-source)
            [ $# -ge 2 ] || { echo "error: $1 needs a value (official or bmclapi)" >&2; exit 2; }
            MIRROR=$2; shift ;;
        --mirror=*|--download-source=*) MIRROR=${1#*=} ;;
        --url-rewrite)
            [ $# -ge 2 ] || { echo "error: --url-rewrite needs from=to" >&2; exit 2; }
            URL_REWRITES="$URL_REWRITES$2$NL"; shift ;;
        --url-rewrite=*) URL_REWRITES="$URL_REWRITES${1#*=}$NL" ;;
        --github-proxy)
            [ $# -ge 2 ] || { echo "error: --github-proxy needs a URL" >&2; exit 2; }
            GITHUB_PROXY=$2; shift ;;
        --github-proxy=*) GITHUB_PROXY=${1#*=} ;;
        --adoptium-api)
            [ $# -ge 2 ] || { echo "error: --adoptium-api needs a URL" >&2; exit 2; }
            ADOPTIUM=$2; shift ;;
        --adoptium-api=*) ADOPTIUM=${1#*=} ;;
        --no-fallback) NO_FALLBACK=1 ;;
        -h|--help)
            sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *)
            echo "error: unknown option '$1' (try --help)" >&2
            exit 2 ;;
    esac
    shift
done

# ---- helpers ---------------------------------------------------------------

fetch() { # fetch <url> <output file> - quiet (small API responses)
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --retry 3 --connect-timeout 30 -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$2" "$1"
    else
        echo "error: need curl or wget to download the Java runtime" >&2
        return 1
    fi
}

fetch_progress() { # fetch_progress <url> <output file> - with a progress bar on a terminal
    # curl / wget draw their bar on stderr; stay quiet when nobody can see it.
    if [ "${HEXCLI_PROGRESS:-}" = "0" ] || { [ ! -t 2 ] && [ "${HEXCLI_PROGRESS:-}" != "1" ]; }; then
        fetch "$1" "$2"
        return
    fi
    if command -v curl >/dev/null 2>&1; then
        curl -fL --progress-bar --retry 3 --connect-timeout 30 -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        # --show-progress needs wget 1.16+; older wget falls back to its default meter.
        if wget --help 2>&1 | grep -q -- '--show-progress'; then
            wget -q --show-progress -O "$2" "$1"
        else
            wget -O "$2" "$1"
        fi
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

# Java 17+? Accepts a java executable or a JDK/JRE home directory; prints the executable.
usable_java() { # usable_java <path> -> java executable on stdout, or return 1
    candidate=$1
    if [ -d "$candidate" ]; then
        for sub in bin/java Contents/Home/bin/java; do
            if [ -x "$candidate/$sub" ]; then
                candidate="$candidate/$sub"
                break
            fi
        done
    fi
    [ -f "$candidate" ] && [ -x "$candidate" ] || return 1
    major=$(java_major "$candidate" || true)
    [ -n "$major" ] && [ "$major" -ge 17 ] 2>/dev/null || return 1
    # Absolute path, so java-path.txt keeps working from the server directory.
    case "$candidate" in
        /*) ;;
        *) candidate="$(CDPATH= cd -- "$(dirname -- "$candidate")" && pwd)/$(basename -- "$candidate")" ;;
    esac
    printf '%s\n' "$candidate"
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
    if ! JAVA=$(usable_java "$JAVA_OVERRIDE"); then
        if [ -e "$JAVA_OVERRIDE" ]; then
            echo "error: --java/HEXCLI_JAVA is not a Java 17+ executable or home: $JAVA_OVERRIDE" >&2
            echo "       ($("$JAVA_OVERRIDE" -version 2>&1 | head -n 1 || true))" >&2
        else
            echo "error: --java/HEXCLI_JAVA does not exist: $JAVA_OVERRIDE" >&2
        fi
        exit 1
    fi
    echo "using Java from --java/HEXCLI_JAVA: $JAVA ($("$JAVA" -version 2>&1 | head -n 1))"
fi
if [ -z "$JAVA" ] && [ -n "${JAVA_HOME:-}" ]; then
    if JAVA=$(usable_java "$JAVA_HOME"); then
        echo "using Java from JAVA_HOME: $JAVA ($("$JAVA" -version 2>&1 | head -n 1))"
    else
        JAVA=""
        echo "JAVA_HOME ($JAVA_HOME) is not Java 17+; ignoring it"
    fi
fi
if [ -z "$JAVA" ]; then
    JAVA=$(runtime_java || true)
    [ -z "$JAVA" ] || echo "using the private runtime: $JAVA"
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
    # Use the "package" block (macOS responses list the .pkg installer first).
    PACKAGE="$TMP/package.json"
    sed -n '/"package"/,$p' "$ASSETS" > "$PACKAGE"
    CHECKSUM=$(json_value "$PACKAGE" checksum)
    LINK=$(json_value "$PACKAGE" link)
    if [ -z "$CHECKSUM" ]; then
        echo "error: no checksum in the Adoptium response" >&2
        exit 1
    fi

    # Candidate URLs: GitHub proxy (if any), then the Adoptium redirect.
    ARCHIVE="$TMP/temurin-jre.tar.gz"
    URLS=""
    if [ -n "$GITHUB_PROXY" ] && [ -n "$LINK" ]; then
        case "$LINK" in
            https://github.com/*) URLS="${GITHUB_PROXY%/}/$LINK$NL" ;;
        esac
    fi
    if [ -z "$URLS" ] || [ "$NO_FALLBACK" != "1" ]; then
        URLS="$URLS$ADOPTIUM/binary/latest/17/ga/$OS/$ARCH/jre/hotspot/normal/eclipse$NL"
    fi
    case "$(printf '%s' "${MIRROR:-}" | tr 'A-Z' 'a-z')" in
        bmclapi|cn|china|mcim)
            [ -n "$GITHUB_PROXY" ] || [ -n "${HEXCLI_ADOPTIUM_API:-}" ] || \
                echo "note: BMCLAPI has no Java mirror; for a faster JRE download use --github-proxy <url>, --adoptium-api <url> or --java" ;;
    esac
    GOT=0
    OLD_IFS=$IFS; IFS=$NL
    for URL in $URLS; do
        IFS=$OLD_IFS
        echo "downloading $URL"
        if fetch_progress "$URL" "$ARCHIVE" && [ "$(sha256_of "$ARCHIVE")" = "$CHECKSUM" ]; then
            GOT=1
            break
        fi
        echo "  failed or checksum mismatch; trying the next source" >&2
    done
    IFS=$OLD_IFS
    [ "$GOT" = "1" ] || { echo "error: could not download a verified Temurin JRE" >&2; exit 1; }

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
if [ -n "$MIRROR" ]; then
    set -- "$@" --mirror "$MIRROR"
fi
if [ -n "$GITHUB_PROXY" ]; then
    set -- "$@" --github-proxy "$GITHUB_PROXY"
fi
if [ "$NO_FALLBACK" = "1" ]; then
    set -- "$@" --no-fallback
fi
OLD_IFS=$IFS; IFS=$NL
for REWRITE in $URL_REWRITES; do
    set -- "$@" --url-rewrite "$REWRITE"
done
IFS=$OLD_IFS

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
