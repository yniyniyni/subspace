#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.
#
# Builds a patched libXray Android AAR.
#
# Needed only while `third_party/libxray-patches/` is non-empty. The patches
# there are upstream's own code, re-applied; when upstream ships them again,
# delete the patch, bump the pin in fetch-native.sh, and this script becomes
# unnecessary. See third_party/libxray-patches/README.md.
#
# Nothing is forked on GitHub. This clones the pinned upstream tag into a
# scratch directory, applies the patches, and builds — so "moving back to
# upstream" is deleting files, never merging a divergent history.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# Keep in step with scripts/fetch-native.sh. Read from there rather than
# duplicated, so the two can never disagree about which upstream revision this
# project is built against.
LIBXRAY_VERSION="$(grep -E '^LIBXRAY_VERSION=' scripts/fetch-native.sh | cut -d'"' -f2)"
LIBXRAY_COMMIT="$(grep -E '^LIBXRAY_COMMIT=' scripts/fetch-native.sh | cut -d'"' -f2)"
PATCH_DIR="third_party/libxray-patches"
WORK=".native-cache/libxray-src"
DEST="core/xray/libs"

if ! compgen -G "$PATCH_DIR/*.patch" > /dev/null; then
    echo "No patches in $PATCH_DIR — use ./scripts/fetch-native.sh instead." >&2
    exit 1
fi

for tool in go python3 git; do
    command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 1; }
done

: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    # Highest installed NDK. gomobile needs one and does not discover it itself.
    ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
[ -n "$ANDROID_NDK_HOME" ] && [ -d "$ANDROID_NDK_HOME" ] || {
    echo "No Android NDK found. Set ANDROID_NDK_HOME." >&2; exit 1; }
export ANDROID_HOME ANDROID_NDK_HOME
export PATH="$PATH:$(go env GOPATH)/bin"

echo "libXray   $LIBXRAY_VERSION"
echo "NDK       $ANDROID_NDK_HOME"

rm -rf "$WORK"
mkdir -p "$(dirname "$WORK")"
git clone -q --depth 1 --branch "$LIBXRAY_VERSION" https://github.com/XTLS/libXray.git "$WORK"
actual_commit="$(git -C "$WORK" rev-parse HEAD)"
if [ "$actual_commit" != "$LIBXRAY_COMMIT" ]; then
    echo "COMMIT MISMATCH for libXray $LIBXRAY_VERSION" >&2
    echo "  expected: $LIBXRAY_COMMIT" >&2
    echo "  actual:   $actual_commit" >&2
    exit 1
fi

# Applied in filename order, and `git apply` fails loudly on a patch that no
# longer matches — which is the signal that upstream changed the code the patch
# touches, i.e. exactly when a human needs to look.
for patch in "$PATCH_DIR"/*.patch; do
    echo "applying $(basename "$patch")"
    git -C "$WORK" apply "$(cd "$(dirname "$patch")" && pwd)/$(basename "$patch")"
done

( cd "$WORK" && gofmt -l . | tee /dev/stderr | (! grep -q .) && go build ./... && go test ./... )

( cd "$WORK" && python3 build/main.py android )

mkdir -p "$DEST"
find "$WORK" -name 'libXray.aar' -exec cp {} "$DEST/libxray.aar" \;
[ -f "$DEST/libxray.aar" ] || { echo "build produced no AAR" >&2; exit 1; }

echo
echo "Wrote $DEST/libxray.aar"
echo "SHA-256: $(shasum -a 256 "$DEST/libxray.aar" | cut -d' ' -f1)"
echo
echo "This AAR is patched and does NOT match the SHA-256 in fetch-native.sh,"
echo "which pins the official release. That is expected while patches exist."
echo "Verify with: :core:xray's AssetLocationProbeTest, on a device."
