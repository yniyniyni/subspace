#!/usr/bin/env bash
# Verifies no production Kotlin source calls kotlinx.coroutines.runBlocking.
# ARCHITECTURE.md §12: no runBlocking outside tests.
#
# detekt's ForbiddenMethodCall rule cannot enforce this ban here: it requires
# type resolution, which needs a detektMain/detektDebug-style task, and this
# project's Android modules use AGP's built-in Kotlin support rather than the
# standalone org.jetbrains.kotlin.android plugin, so detekt never registers a
# type-resolution task for them. The plain `detekt` task that `check` runs
# has no BindingContext and the rule silently never fires. This script is the
# real enforcement — see config/detekt/detekt.yml for the full explanation.
set -euo pipefail

found=0

while IFS= read -r file; do
    if grep -qE '\brunBlocking\b' "$file"; then
        echo "runBlocking found outside tests: $file"
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
    echo "ARCHITECTURE.md §12: no runBlocking outside tests. Remove it or move the"
    echo "call into a test source set."
    exit 1
fi

echo "No forbidden runBlocking usage in production source"
