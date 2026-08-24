#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later

set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "usage: $0 SOURCE_DIR OUTPUT_DIR PATCH_FILE" >&2
    exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
source_dir="$1"
output_dir="$2"
patch_file="$3"
expected_source="$repo_root/third_party/hev-socks5-tunnel"
expected_output="$repo_root/service/build/generated/hev-socks5-tunnel"
expected_patch="$repo_root/third_party/hev-patches/0001-nonblocking-pending-stop.patch"
expected_commit="0a05221275a51a884d93328c55fc2fbc9e9b6974"

if [[ "$source_dir" != "$expected_source" ]]; then
    echo "refusing unexpected HEV source path: $source_dir" >&2
    exit 3
fi
if [[ "$output_dir" != "$expected_output" ]]; then
    echo "refusing unexpected generated HEV path: $output_dir" >&2
    exit 4
fi
if [[ "$patch_file" != "$expected_patch" ]]; then
    echo "refusing unexpected HEV patch path: $patch_file" >&2
    exit 5
fi

parent_gitlink="$(
    git -C "$repo_root" ls-files --stage -- third_party/hev-socks5-tunnel |
        awk 'NR == 1 { print $2 }'
)"
if [[ "$parent_gitlink" != "$expected_commit" ]]; then
    echo "parent HEV gitlink must be $expected_commit, found ${parent_gitlink:-missing}" >&2
    exit 6
fi

actual_commit="$(git -C "$source_dir" rev-parse HEAD)"
if [[ "$actual_commit" != "$expected_commit" ]]; then
    echo "hev-socks5-tunnel must be pinned at $expected_commit, found $actual_commit" >&2
    exit 7
fi

dirty="$(git -C "$source_dir" status --porcelain --untracked-files=no)"
if [[ -n "$dirty" ]]; then
    echo "hev-socks5-tunnel must be clean; local edits are not reproducible" >&2
    exit 8
fi

for required in \
    src/hev-main.c \
    src/hev-main.h \
    src/hev-socks5-tunnel.c \
    src/hev-socks5-tunnel.h \
    third-part/lwip/Android.mk \
    third-part/hev-task-system/Android.mk \
    third-part/yaml/Android.mk; do
    if [[ ! -f "$source_dir/$required" ]]; then
        echo "pinned HEV layout is incomplete: missing $required" >&2
        exit 9
    fi
done

# output_dir is checked against one exact build-owned path above before this
# recoverable generated tree is replaced.
rm -rf "$output_dir"
mkdir -p "$output_dir"
cp -R "$source_dir"/. "$output_dir"/
rm -rf "$output_dir/.git"

# The generated directory lives below the parent repository. Stop Git's repo
# discovery at service/build so git apply operates on this copied tree itself.
export GIT_CEILING_DIRECTORIES="$repo_root/service/build"
git -C "$output_dir" apply --unidiff-zero --check "$patch_file"
git -C "$output_dir" apply --unidiff-zero "$patch_file"

check_blob() {
    local path="$1"
    local expected="$2"
    local actual
    actual="$(git hash-object "$output_dir/$path")"
    if [[ "$actual" != "$expected" ]]; then
        echo "HEV patch output mismatch for $path: expected $expected, found $actual" >&2
        exit 10
    fi
}

check_blob src/hev-main.c 4531e91976da4c57f596bb20eda5ed9f02747caf
check_blob src/hev-main.h ce2d203cb35322d6768c56564b7906abffec424e
check_blob src/hev-socks5-tunnel.c 48463549d80d216c3e68b34d80e59bed26c7ddf8
check_blob src/hev-socks5-tunnel.h 4ac2071a03975167510639d653148143dfa25f6c

echo "Prepared verified HEV $expected_commit with $(basename "$patch_file")"
