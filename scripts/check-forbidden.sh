#!/usr/bin/env bash
# Enforces the ARCHITECTURE.md §12 bans that detekt cannot enforce here:
#
#   - no runBlocking outside tests
#   - no !! (not-null assertion)
#
# Why a shell script and not detekt: both bans map to detekt rules
# (ForbiddenMethodCall, UnsafeCallOnNullableType) that require type resolution.
# Type resolution needs a detektMain/detektDebug-style task with a compile
# classpath. This project's Android modules use AGP's built-in Kotlin support
# rather than the standalone org.jetbrains.kotlin.android plugin, so detekt
# never registers a type-resolution task for them. The plain `detekt` task that
# `check` runs analyses with an empty BindingContext, and both rules silently
# return no findings regardless of source content — a check that cannot fail.
#
# That leaves :service, :app, and every :feature:* module — where most of this
# codebase will live — unguarded. Hence this script, which needs no classpath
# and covers every module uniformly.
#
# GlobalScope (GlobalCoroutineUsage) is NOT here: that rule is a plain
# syntactic check and does fire under the ordinary detekt task.
#
# See config/detekt/detekt.yml for the corresponding note.
set -euo pipefail

found=0

# Strip line comments, block-comment bodies, and string literals before
# matching, so a `!!` inside a message string or a comment is not a violation.
strip_noise() {
    sed -e 's|//.*$||' -e 's|^[[:space:]]*\*.*$||' -e 's|"[^"]*"||g' "$1"
}

while IFS= read -r file; do
    if strip_noise "$file" | grep -qE '\brunBlocking\b'; then
        echo "§12 runBlocking outside tests: $file"
        found=1
    fi
    if strip_noise "$file" | grep -q '!!'; then
        echo "§12 not-null assertion (!!): $file"
        found=1
    fi
done < <(
    find app core feature service \
        -name '*.kt' \
        -not -path '*/build/*' \
        -not -path '*/test/*' \
        -not -path '*/androidTest/*' \
        -not -path '*/testFixtures/*' \
        2>/dev/null
)

if [ "$found" -ne 0 ]; then
    echo
    echo "ARCHITECTURE.md §12 forbids these in production source."
    echo "  runBlocking -> use a suspend function, or move the call into a test source set."
    echo "  !!          -> use ?:, requireNotNull(), or restructure so the type is non-null."
    exit 1
fi

echo "No forbidden constructs in production source"
