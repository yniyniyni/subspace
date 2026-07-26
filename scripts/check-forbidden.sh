#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
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
cd "$(git rev-parse --show-toplevel)"

found=0

# Strip string literals, THEN line comments, THEN block-comment bodies,
# before matching, so a `!!` inside a message string or a comment is not a
# violation. Order matters: stripping line comments first (the previous
# order) truncates the rest of the line at the first `//` it finds — and a
# `//` inside a string literal (a URL, most obviously) is not a comment.
# `val u = "https://example.com"; val v: String? = null; val w = v!!` would
# have its `!!` silently hidden behind the "comment" that starts at the `//`
# inside the string. Stripping strings first removes that `//` along with
# the rest of the string contents before the comment-stripping pass ever
# runs, so a same-line `!!` after a URL-bearing string is still caught.
strip_noise() {
    sed -e 's|"[^"]*"||g' -e 's|//.*$||' -e 's|^[[:space:]]*\*.*$||' "$1"
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
    # git ls-files, not `find app core feature service`: a hardcoded
    # directory allowlist silently exempts any new top-level module (a file
    # at bg/src/main/kotlin/Bad.kt with a runBlocking and a `!!` used to
    # pass this script simply because "bg" was never in the list). Deriving
    # the file set from the repo covers new modules by default. --others
    # --exclude-standard also catches new, not-yet-`git add`ed files during
    # local development. build/ is already git-ignored so it never appears
    # here; test/androidTest/testFixtures source sets are real, tracked
    # source and are excluded explicitly below since the runBlocking ban is
    # production-only.
    git ls-files --cached --others --exclude-standard -- '*.kt' \
        | grep -vE '(^|/)(test|androidTest|testFixtures)/'
)

if [ "$found" -ne 0 ]; then
    echo
    echo "ARCHITECTURE.md §12 forbids these in production source."
    echo "  runBlocking -> use a suspend function, or move the call into a test source set."
    echo "  !!          -> use ?:, requireNotNull(), or restructure so the type is non-null."
    exit 1
fi

echo "No forbidden constructs in production source"
