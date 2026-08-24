#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
live_hev="$repo_root/third_party/hev-socks5-tunnel"
scratch_root="$(mktemp -d /tmp/subspace-hev-isolation.XXXXXX)"
fixture_repo="$scratch_root/repo"
fixture_hev="$fixture_repo/third_party/hev-socks5-tunnel"
fixture_lwip="$fixture_hev/third-part/lwip"
fixture_prepare="$fixture_repo/scripts/prepare-hev-socks5-tunnel.sh"
fixture_patch="$fixture_repo/third_party/hev-patches/0001-nonblocking-pending-stop.patch"
fixture_output="$fixture_repo/service/build/generated/hev-socks5-tunnel"
expected_main_blob="4531e91976da4c57f596bb20eda5ed9f02747caf"
nested_specs=(
    "src/core cbff465b916832455c1cb02f1f9e25a41062054d"
    "third-part/hev-task-system b1afa0e21fb4ed5a69560e78e54baf0efdebe171"
    "third-part/lwip 2a11c14c7a32887af25a034e82ef18b0b12076ac"
    "third-part/yaml efa36117a8646d26d12b58e05bac472d7854a70d"
)

cleanup() {
    local status=$?

    trap - EXIT INT TERM
    rm -rf "$scratch_root"
    exit "$status"
}
trap cleanup EXIT INT TERM

snapshot_live_metadata() {
    local destination="$1"
    local repository git_dir metadata

    : >"$destination"
    for repository in "$repo_root" "$live_hev"; do
        git_dir="$(git -C "$repository" rev-parse --absolute-git-dir)"
        for metadata in HEAD index info/exclude config; do
            if [[ -f "$git_dir/$metadata" ]]; then
                printf '%s %s\n' "$git_dir/$metadata" "$(git hash-object "$git_dir/$metadata")" >>"$destination"
            fi
        done
    done
    for spec in "${nested_specs[@]}"; do
        nested_path="${spec%% *}"
        git_dir="$(git -C "$live_hev/$nested_path" rev-parse --absolute-git-dir)"
        for metadata in HEAD index info/exclude config; do
            if [[ -f "$git_dir/$metadata" ]]; then
                printf '%s %s\n' "$git_dir/$metadata" "$(git hash-object "$git_dir/$metadata")" >>"$destination"
            fi
        done
    done
    LC_ALL=C sort -o "$destination" "$destination"
}

clone_at() {
    local source="$1"
    local destination="$2"
    local commit="$3"

    mkdir -p "$(dirname "$destination")"
    git clone --quiet --shared --no-checkout "$source" "$destination"
    git -C "$destination" checkout --quiet --detach "$commit"
}

snapshot_live_metadata "$scratch_root/live-metadata-before"
parent_commit="$(git -C "$repo_root" rev-parse HEAD)"
clone_at "$repo_root" "$fixture_repo" "$parent_commit"
clone_at "$live_hev" "$fixture_hev" 0a05221275a51a884d93328c55fc2fbc9e9b6974
for spec in "${nested_specs[@]}"; do
    nested_path="${spec%% *}"
    nested_commit="${spec#* }"
    clone_at "$live_hev/$nested_path" "$fixture_hev/$nested_path" "$nested_commit"
done

# Exercise the working implementation while every repository and Git metadata
# path it can mutate belongs to this disposable fixture.
cp "$script_dir/prepare-hev-socks5-tunnel.sh" "$fixture_prepare"

outer_tracked="$fixture_hev/src/hev-main.c"
nested_tracked="$fixture_lwip/Android.mk"
cp "$outer_tracked" "$scratch_root/outer-tracked"
cp "$nested_tracked" "$scratch_root/nested-tracked"

printf '\n' >>"$outer_tracked"
if "$fixture_prepare" "$fixture_hev" "$fixture_output" "$fixture_patch" >"$scratch_root/outer-tracked.log" 2>&1; then
    echo "preparation accepted a tracked outer HEV modification" >&2
    exit 1
fi
cp "$scratch_root/outer-tracked" "$outer_tracked"

printf '\n' >>"$nested_tracked"
if "$fixture_prepare" "$fixture_hev" "$fixture_output" "$fixture_patch" >"$scratch_root/nested-tracked.log" 2>&1; then
    echo "preparation accepted a tracked nested HEV modification" >&2
    exit 1
fi
cp "$scratch_root/nested-tracked" "$nested_tracked"

outer_untracked="$fixture_hev/src/subspace-untracked-injection.c"
outer_ignored="$fixture_hev/src/subspace-ignored-injection.c"
nested_untracked="$fixture_lwip/src/core/subspace-untracked-injection.c"
nested_ignored="$fixture_lwip/src/core/subspace-ignored-injection.c"
outer_exclude="$(git -C "$fixture_hev" rev-parse --absolute-git-dir)/info/exclude"
nested_exclude="$(git -C "$fixture_lwip" rev-parse --absolute-git-dir)/info/exclude"

printf 'int subspace_outer_untracked_injection (void) { return 1; }\n' >"$outer_untracked"
printf 'int subspace_outer_ignored_injection (void) { return 2; }\n' >"$outer_ignored"
printf 'int subspace_nested_untracked_injection (void) { return 3; }\n' >"$nested_untracked"
printf 'int subspace_nested_ignored_injection (void) { return 4; }\n' >"$nested_ignored"
printf '\n/src/subspace-ignored-injection.c\n' >>"$outer_exclude"
printf '\n/src/core/subspace-ignored-injection.c\n' >>"$nested_exclude"
git -C "$fixture_hev" check-ignore -q src/subspace-ignored-injection.c
git -C "$fixture_lwip" check-ignore -q src/core/subspace-ignored-injection.c

"$fixture_prepare" "$fixture_hev" "$fixture_output" "$fixture_patch"
if [[ -L "$fixture_output/current" ]]; then
    mutation_tree="$fixture_output/current"
else
    mutation_tree="$fixture_output"
fi
for relative in \
    src/subspace-untracked-injection.c \
    src/subspace-ignored-injection.c \
    third-part/lwip/src/core/subspace-untracked-injection.c \
    third-part/lwip/src/core/subspace-ignored-injection.c; do
    if [[ -e "$mutation_tree/$relative" ]]; then
        echo "non-tracked source entered generated HEV inventory: $relative" >&2
        exit 1
    fi
done

# A dead well-formed owner must not permanently wedge preparation.
lock_path="${fixture_output}.prepare.lock"
printf '%s %s %s\n' stale-test-owner 99999999 "$(date +%s)" >"$lock_path"
"$fixture_prepare" "$fixture_hev" "$fixture_output" "$fixture_patch"

reader_stop="$scratch_root/reader.stop"
reader_failure="$scratch_root/reader.failure"
(
    while [[ ! -e "$reader_stop" ]]; do
        if [[ ! -L "$fixture_output/current" ||
            ! -f "$fixture_output/current/src/hev-main.c" ||
            -e "$fixture_output/current/current" ]]; then
            printf '%s\n' "active HEV tree disappeared or nested during concurrent publication" >"$reader_failure"
            exit 0
        fi
        if [[ "$(git hash-object "$fixture_output/current/src/hev-main.c")" != "$expected_main_blob" ]]; then
            printf '%s\n' "active HEV tree exposed a corrupted reviewed source" >"$reader_failure"
            exit 0
        fi
        sleep 0.01
    done
) &
reader_pid=$!

prepare_pids=()
for iteration in {1..8}; do
    "$fixture_prepare" "$fixture_hev" "$fixture_output" "$fixture_patch" \
        >"$scratch_root/concurrent-$iteration.log" 2>&1 &
    prepare_pids+=("$!")
done

prepare_failed=0
for prepare_pid in "${prepare_pids[@]}"; do
    if ! wait "$prepare_pid"; then
        prepare_failed=1
    fi
done
touch "$reader_stop"
wait "$reader_pid"

if [[ "$prepare_failed" -ne 0 ]]; then
    echo "one or more concurrent HEV preparations failed" >&2
    exit 1
fi
if [[ -e "$reader_failure" ]]; then
    cat "$reader_failure" >&2
    exit 1
fi

"$fixture_prepare" "$fixture_hev" "$fixture_output" "$fixture_patch"
if [[ ! -L "$fixture_output/current" ||
    "$(git hash-object "$fixture_output/current/src/hev-main.c")" != "$expected_main_blob" ]]; then
    echo "final concurrent HEV inventory is missing or corrupt" >&2
    exit 1
fi
if [[ -e "$lock_path" || -e "$fixture_output/current/current" ]]; then
    echo "preparation left a lock or nested active tree" >&2
    exit 1
fi
if find "$(dirname "$fixture_output")" -maxdepth 1 \
    \( -name '.hev-socks5-tunnel.*' \
    -o -name 'hev-socks5-tunnel.previous*' \
    -o -name 'hev-socks5-tunnel.legacy.*' \
    -o -name 'hev-socks5-tunnel.prepare.lock*' \) \
    -print -quit | grep -q .; then
    echo "preparation left a staging or backup path" >&2
    exit 1
fi

snapshot_live_metadata "$scratch_root/live-metadata-after"
if ! cmp -s "$scratch_root/live-metadata-before" "$scratch_root/live-metadata-after"; then
    echo "isolated HEV test changed live repository Git metadata" >&2
    exit 1
fi

echo "Verified isolated tracked/non-tracked mutations and concurrent atomic HEV publication"
