#!/usr/bin/env bash
# Verifies every Kotlin/Java source file carries the AGPL SPDX header.
# ARCHITECTURE.md §12 / CLAUDE.md house rules.
set -euo pipefail

expected="// SPDX-License-Identifier: AGPL-3.0-or-later"
missing=0

while IFS= read -r file; do
    if ! head -1 "$file" | grep -qF "$expected"; then
        echo "missing SPDX header: $file"
        missing=1
    fi
done < <(
    find app core feature service build-logic \
        \( -name '*.kt' -o -name '*.kts' -o -name '*.java' \) \
        -not -path '*/build/*' 2>/dev/null
    ls build.gradle.kts settings.gradle.kts 2>/dev/null
)

if [ "$missing" -ne 0 ]; then
    echo
    echo "Add '$expected' as the first line of each file listed above."
    exit 1
fi

echo "SPDX headers OK"
