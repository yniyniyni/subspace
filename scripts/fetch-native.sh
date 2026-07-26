#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Fetches pinned native artifacts. The binaries are gitignored (§10.7);
# this script is the committed, reproducible way to obtain them.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

LIBXRAY_VERSION="v26.7.11"
LIBXRAY_SHA256="205ab07d998aa488db0361f2d3804efef4c2f8fce1d07dd19c1073b4d844da96"
DEST="core/xray/libs"

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
