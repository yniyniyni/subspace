#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
hev_dir="$repo_root/third_party/hev-socks5-tunnel"
lwip_dir="$hev_dir/third-part/lwip"
generated_dir="$repo_root/service/build/generated/hev-socks5-tunnel"
patch_file="$repo_root/third_party/hev-patches/0001-nonblocking-pending-stop.patch"
prepare_script="$script_dir/prepare-hev-socks5-tunnel.sh"
outer_untracked="$hev_dir/src/subspace-untracked-injection.c"
outer_ignored="$hev_dir/src/subspace-ignored-injection.c"
nested_untracked="$lwip_dir/src/core/subspace-untracked-injection.c"
nested_ignored="$lwip_dir/src/core/subspace-ignored-injection.c"
outer_tracked="$hev_dir/src/hev-main.c"
nested_tracked="$lwip_dir/Android.mk"
outer_exclude="$(git -C "$hev_dir" rev-parse --git-path info/exclude)"
nested_exclude="$(git -C "$lwip_dir" rev-parse --git-path info/exclude)"

for injection in \
    "$outer_untracked" \
    "$outer_ignored" \
    "$nested_untracked" \
    "$nested_ignored"; do
    if [[ -e "$injection" ]]; then
        echo "refusing to overwrite existing mutation fixture: $injection" >&2
        exit 2
    fi
done

scratch_dir="$(mktemp -d /tmp/subspace-hev-isolation.XXXXXX)"
cp "$outer_exclude" "$scratch_dir/outer-exclude"
cp "$nested_exclude" "$scratch_dir/nested-exclude"
cp "$outer_tracked" "$scratch_dir/outer-tracked"
cp "$nested_tracked" "$scratch_dir/nested-tracked"

cleanup() {
    local status=$?

    trap - EXIT INT TERM
    rm -f \
        "$outer_untracked" \
        "$outer_ignored" \
        "$nested_untracked" \
        "$nested_ignored"
    cp "$scratch_dir/outer-exclude" "$outer_exclude"
    cp "$scratch_dir/nested-exclude" "$nested_exclude"
    cp "$scratch_dir/outer-tracked" "$outer_tracked"
    cp "$scratch_dir/nested-tracked" "$nested_tracked"
    "$prepare_script" "$hev_dir" "$generated_dir" "$patch_file" >/dev/null 2>&1 || true
    rm -rf "$scratch_dir"
    exit "$status"
}
trap cleanup EXIT INT TERM

printf '\n' >>"$outer_tracked"
if "$prepare_script" "$hev_dir" "$generated_dir" "$patch_file" >"$scratch_dir/outer-tracked.log" 2>&1; then
    echo "preparation accepted a tracked outer HEV modification" >&2
    exit 1
fi
cp "$scratch_dir/outer-tracked" "$outer_tracked"

printf '\n' >>"$nested_tracked"
if "$prepare_script" "$hev_dir" "$generated_dir" "$patch_file" >"$scratch_dir/nested-tracked.log" 2>&1; then
    echo "preparation accepted a tracked nested HEV modification" >&2
    exit 1
fi
cp "$scratch_dir/nested-tracked" "$nested_tracked"

printf 'int subspace_outer_untracked_injection (void) { return 1; }\n' >"$outer_untracked"
printf 'int subspace_outer_ignored_injection (void) { return 2; }\n' >"$outer_ignored"
printf 'int subspace_nested_untracked_injection (void) { return 3; }\n' >"$nested_untracked"
printf 'int subspace_nested_ignored_injection (void) { return 4; }\n' >"$nested_ignored"
printf '\n/src/subspace-ignored-injection.c\n' >>"$outer_exclude"
printf '\n/src/core/subspace-ignored-injection.c\n' >>"$nested_exclude"

git -C "$hev_dir" check-ignore -q src/subspace-ignored-injection.c
git -C "$lwip_dir" check-ignore -q src/core/subspace-ignored-injection.c

"$prepare_script" "$hev_dir" "$generated_dir" "$patch_file"

failed=0
for relative in \
    src/subspace-untracked-injection.c \
    src/subspace-ignored-injection.c \
    third-part/lwip/src/core/subspace-untracked-injection.c \
    third-part/lwip/src/core/subspace-ignored-injection.c; do
    if [[ -e "$generated_dir/$relative" ]]; then
        echo "untracked source entered generated HEV inventory: $relative" >&2
        failed=1
    fi
done

if [[ "$failed" -ne 0 ]]; then
    exit 1
fi

echo "Verified tracked changes fail and untracked/ignored outer/nested C sources cannot enter generated HEV inventory"
