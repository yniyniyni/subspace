#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Fetches pinned native artifacts. The binaries are gitignored (§10.7);
# this script is the committed, reproducible way to obtain them.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

LIBXRAY_VERSION="v26.7.11"
LIBXRAY_COMMIT="294fb37343205b9b0cb7b7b1b423d3d4b60d9998"
LIBXRAY_SHA256="205ab07d998aa488db0361f2d3804efef4c2f8fce1d07dd19c1073b4d844da96"
DEST="core/xray/libs"

# While third_party/libxray-patches/ is non-empty, the official release AAR is
# NOT the one this app can use: without those patches `geoip:`/`geosite:`/`ext:`
# routing rules cannot resolve on Android at all (research §2b — Go is started
# with an empty environment there, so the only route to XRAY_LOCATION_ASSET is
# libXray's own `env` object, which upstream removed in PR #134).
#
# Downloading the stock AAR over a patched one produces an app that builds,
# installs, connects, and silently ignores every geo rule — §10.1's failure mode
# exactly. So refuse, and point at the script that does the right thing.
if compgen -G "third_party/libxray-patches/*.patch" > /dev/null; then
    cat >&2 <<'MSG'
third_party/libxray-patches/ is non-empty, so this project needs a PATCHED
libXray AAR. Fetching the official release here would overwrite it and silently
break geo routing.

Run instead:   ./scripts/build-libxray.sh

When upstream ships the env object again, delete the patch, bump
LIBXRAY_VERSION/LIBXRAY_SHA256 below, and this script becomes correct again.
See third_party/libxray-patches/README.md.
MSG
    exit 1
fi

mkdir -p "$DEST" .native-cache

url="https://github.com/XTLS/libXray/releases/download/${LIBXRAY_VERSION}/libxray-android.zip"
archive=".native-cache/libxray-${LIBXRAY_VERSION}.zip"

if [ ! -f "$archive" ]; then
    echo "Downloading libXray ${LIBXRAY_VERSION}..."
    curl -fL --progress-bar -o "$archive" "$url"
fi

echo "SHA-256: $(shasum -a 256 "$archive" | cut -d' ' -f1)"

actual=$(shasum -a 256 "$archive" | cut -d' ' -f1)
if [ "$actual" != "$LIBXRAY_SHA256" ]; then
    echo "CHECKSUM MISMATCH for $archive" >&2
    echo "  expected: $LIBXRAY_SHA256" >&2
    echo "  actual:   $actual" >&2
    exit 1
fi

unzip -o "$archive" -d .native-cache/libxray
find .native-cache/libxray -name '*.aar' -exec cp {} "$DEST/libxray.aar" \;

echo "Wrote $DEST/libxray.aar"
