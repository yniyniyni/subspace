#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Verifies every Kotlin/Java source file carries the AGPL SPDX header.
# ARCHITECTURE.md §12 / CLAUDE.md house rules.
#
# The file list comes from `git ls-files`/`git ls-files --others` rather
# than a hardcoded `find app core feature service build-logic` allowlist:
# a hardcoded directory list silently exempts any new top-level module (a
# file at bg/src/main/kotlin/Bad.kt with no SPDX header used to pass this
# script simply because "bg" was never in the list). Deriving the file set
# from the repo means a new module is covered automatically, and the
# failure mode of this script going wrong is now "lints too much"
# (harmless — a build-logic or root script file was always in scope anyway)
# rather than "silently lints nothing" if a directory ever goes missing.
#
# --others --exclude-standard is included alongside --cached so this also
# catches new, not-yet-`git add`ed files during local development, not just
# files already in the index.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

expected="// SPDX-License-Identifier: AGPL-3.0-or-later"
missing=0

while IFS= read -r file; do
    [ -z "$file" ] && continue
    if ! head -1 "$file" | grep -qF "$expected"; then
        echo "missing SPDX header: $file"
        missing=1
    fi
done < <(
    git ls-files --cached --others --exclude-standard -- '*.kt' '*.kts' '*.java'
)

if [ "$missing" -ne 0 ]; then
    echo
    echo "Add '$expected' as the first line of each file listed above."
    exit 1
fi

echo "SPDX headers OK"
