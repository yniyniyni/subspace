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
fixture_lock_runner="$fixture_repo/scripts/HevPrepareLock.java"
fixture_patch="$fixture_repo/third_party/hev-patches/0001-nonblocking-pending-stop.patch"
fixture_output="$fixture_repo/service/build/generated/hev-socks5-tunnel"
expected_main_blob="4531e91976da4c57f596bb20eda5ed9f02747caf"
valid_version_name="8e1052ec103a981107e8c0e2a2001ece5012321e.1.2.3"
nested_specs=(
    "src/core cbff465b916832455c1cb02f1f9e25a41062054d"
    "third-part/hev-task-system b1afa0e21fb4ed5a69560e78e54baf0efdebe171"
    "third-part/lwip 2a11c14c7a32887af25a034e82ef18b0b12076ac"
    "third-part/yaml efa36117a8646d26d12b58e05bac472d7854a70d"
)
runner_holder_pid=""
runner_contender_pid=""

cleanup() {
    local status=$?

    trap - EXIT INT TERM
    if [[ -n "$runner_holder_pid" ]] && kill -0 "$runner_holder_pid" 2>/dev/null; then
        kill -TERM "$runner_holder_pid" 2>/dev/null || true
        wait "$runner_holder_pid" 2>/dev/null || true
    fi
    if [[ -n "$runner_contender_pid" ]] && kill -0 "$runner_contender_pid" 2>/dev/null; then
        kill -TERM "$runner_contender_pid" 2>/dev/null || true
        wait "$runner_contender_pid" 2>/dev/null || true
    fi
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

clear_fixture_output() {
    rm -rf "$fixture_output"
    rm -f "${fixture_output}.prepare.lock"
}

reset_external_sentinel() {
    external_dir="$scratch_root/external-sentinel"
    rm -rf "$external_dir"
    mkdir -p "$external_dir"
    printf '%s\n' "must remain unchanged" >"$external_dir/sentinel"
    external_sentinel_blob="$(git hash-object "$external_dir/sentinel")"
}

assert_external_sentinel_unchanged() {
    current_sentinel_blob="$(git hash-object "$external_dir/sentinel" 2>/dev/null || true)"
    if [[ "$current_sentinel_blob" != "$external_sentinel_blob" ||
        "$(find "$external_dir" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')" != "1" ]]; then
        echo "malformed generated root changed an external sentinel" >&2
        exit 1
    fi
}

assert_no_scoped_debris() {
    if find "$(dirname "$fixture_output")" -maxdepth 1 \
        \( -name '.hev-socks5-tunnel.*' \
        -o -name 'hev-socks5-tunnel.previous*' \
        -o -name 'hev-socks5-tunnel.legacy.*' \
        -o -name 'hev-socks5-tunnel.prepare.lock.*' \) \
        -print -quit | grep -q .; then
        echo "preparation left scoped staging or publication debris" >&2
        exit 1
    fi
}

expect_malformed_output_rejected() {
    local label="$1"

    if "$fixture_prepare" "$fixture_hev" "$fixture_output" "$fixture_patch" \
        >"$scratch_root/malformed-$label.log" 2>&1; then
        echo "preparation accepted malformed generated root: $label" >&2
        exit 1
    fi
    assert_external_sentinel_unchanged
    assert_no_scoped_debris
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
cp "$script_dir/HevPrepareLock.java" "$fixture_lock_runner"

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

# Generated-root validation must reject every path that could escape through a
# symlink or select an unreviewed active tree. All fixtures remain disposable.
clear_fixture_output
reset_external_sentinel
ln -s "$external_dir" "$fixture_output"
expect_malformed_output_rejected output-root-symlink

clear_fixture_output
reset_external_sentinel
mkdir -p "$fixture_output"
ln -s "$external_dir" "$fixture_output/versions"
expect_malformed_output_rejected versions-symlink

clear_fixture_output
reset_external_sentinel
mkdir -p "$fixture_output/versions"
printf '%s\n' invalid >"$fixture_output/current"
expect_malformed_output_rejected current-regular-file

clear_fixture_output
reset_external_sentinel
mkdir -p "$fixture_output/versions"
ln -s "$external_dir" "$fixture_output/current"
expect_malformed_output_rejected current-absolute-target

clear_fixture_output
reset_external_sentinel
mkdir -p "$fixture_output/versions"
ln -s ../../../../../external-sentinel "$fixture_output/current"
expect_malformed_output_rejected current-parent-target

clear_fixture_output
reset_external_sentinel
mkdir -p "$fixture_output/versions/$valid_version_name/nested"
ln -s "versions/$valid_version_name/nested" "$fixture_output/current"
expect_malformed_output_rejected current-nested-target

clear_fixture_output
reset_external_sentinel
mkdir -p "$fixture_output/versions/not-a-validated-name"
ln -s versions/not-a-validated-name "$fixture_output/current"
expect_malformed_output_rejected current-invalid-name

clear_fixture_output
reset_external_sentinel
mkdir -p "$fixture_output/versions"
ln -s "$external_dir" "$fixture_output/versions/$valid_version_name"
ln -s "versions/$valid_version_name" "$fixture_output/current"
expect_malformed_output_rejected current-symlinked-version

clear_fixture_output
reset_external_sentinel
mkdir -p "$fixture_output/versions"
ln -s "versions/$valid_version_name" "$fixture_output/current"
expect_malformed_output_rejected current-dangling-version

clear_fixture_output
reset_external_sentinel
mkdir -p "$fixture_output/versions/$valid_version_name"
printf '%s\n' unreviewed >"$fixture_output/versions/$valid_version_name/unreviewed.c"
ln -s "versions/$valid_version_name" "$fixture_output/current"
expect_malformed_output_rejected current-unreviewed-inventory

clear_fixture_output
"$fixture_prepare" "$fixture_hev" "$fixture_output" "$fixture_patch"

# A malformed coordination path must fail closed without renaming or unlinking
# a symlink that points outside the generated-output scope.
clear_fixture_output
external_lock_sentinel="$scratch_root/external-lock-sentinel"
printf '%s\n' "must remain unchanged" >"$external_lock_sentinel"
external_lock_blob="$(git hash-object "$external_lock_sentinel")"
touch -t 200001010000 "$external_lock_sentinel"
lock_path="${fixture_output}.prepare.lock"
ln -s "$external_lock_sentinel" "$lock_path"
if "$fixture_prepare" "$fixture_hev" "$fixture_output" "$fixture_patch" \
    >"$scratch_root/malformed-lock.log" 2>&1; then
    echo "preparation accepted a symlinked lock path" >&2
    exit 1
fi
if [[ ! -L "$lock_path" ||
    "$(git hash-object "$external_lock_sentinel")" != "$external_lock_blob" ||
    -e "$fixture_output" ]]; then
    echo "malformed lock handling changed external or generated state" >&2
    exit 1
fi
rm -f "$lock_path"

mkdir "$lock_path"
if "$fixture_prepare" "$fixture_hev" "$fixture_output" "$fixture_patch" \
    >"$scratch_root/malformed-lock-directory.log" 2>&1; then
    echo "preparation accepted a directory as its lock path" >&2
    exit 1
fi
if [[ ! -d "$lock_path" || -L "$lock_path" || -e "$fixture_output" ]]; then
    echo "malformed lock directory handling changed generated state" >&2
    exit 1
fi
rmdir "$lock_path"
"$fixture_prepare" "$fixture_hev" "$fixture_output" "$fixture_patch"

# The OS lock must serialize contenders, terminate the holder's child on a
# normal process termination, and become immediately acquirable afterward.
runner_lock="$scratch_root/runner-lock"
holder_ready="$scratch_root/holder-ready"
holder_child_pid="$scratch_root/holder-child-pid"
contender_acquired="$scratch_root/contender-acquired"
# shellcheck disable=SC2016 # Positional parameters expand in the child shell.
java "$fixture_lock_runner" "$runner_lock" /bin/sh -c \
    'printf "%s\n" "$$" >"$2"; : >"$1"; trap "exit 143" TERM; while :; do sleep 1; done' \
    hev-lock-holder "$holder_ready" "$holder_child_pid" \
    >"$scratch_root/holder.log" 2>&1 &
runner_holder_pid=$!
for _ in {1..200}; do
    [[ -e "$holder_ready" ]] && break
    sleep 0.05
done
if [[ ! -e "$holder_ready" ]]; then
    echo "OS lock holder did not become ready" >&2
    exit 1
fi

# shellcheck disable=SC2016 # Positional parameter expands in the child shell.
java "$fixture_lock_runner" "$runner_lock" /bin/sh -c ': >"$1"' \
    hev-lock-contender "$contender_acquired" \
    >"$scratch_root/contender.log" 2>&1 &
runner_contender_pid=$!
for _ in {1..10}; do
    if [[ -e "$contender_acquired" ]]; then
        echo "OS lock admitted a contender while the holder was live" >&2
        exit 1
    fi
    sleep 0.05
done

kill -TERM "$runner_holder_pid"
if wait "$runner_holder_pid"; then
    echo "terminated OS lock holder reported success" >&2
    exit 1
fi
runner_holder_pid=""
for _ in {1..200}; do
    ! kill -0 "$runner_contender_pid" 2>/dev/null && break
    sleep 0.05
done
if kill -0 "$runner_contender_pid" 2>/dev/null; then
    echo "OS lock was not released after holder termination" >&2
    exit 1
fi
wait "$runner_contender_pid"
runner_contender_pid=""
holder_child="$(cat "$holder_child_pid")"
if [[ ! -e "$contender_acquired" ]]; then
    echo "holder termination did not recover the OS lock cleanly" >&2
    exit 1
fi
if [[ "$holder_child" =~ ^[0-9]+$ ]] && kill -0 "$holder_child" 2>/dev/null; then
    echo "OS lock runner left its terminated child alive" >&2
    exit 1
fi

# A hard crash bypasses shutdown hooks. The kernel must nevertheless release
# the lock immediately; this test child observes its original parent vanish and
# then exits without leaving a process behind.
crashed_holder_lock="$scratch_root/crashed-holder-lock"
crashed_holder_ready="$scratch_root/crashed-holder-ready"
crashed_holder_child_pid="$scratch_root/crashed-holder-child-pid"
crashed_holder_acquired="$scratch_root/crashed-holder-acquired"
# shellcheck disable=SC2016 # Positional parameters expand in the child shell.
java "$fixture_lock_runner" "$crashed_holder_lock" /bin/sh -c \
    'parent=$PPID; printf "%s\n" "$$" >"$2"; : >"$1"; while kill -0 "$parent" 2>/dev/null; do :; done' \
    hev-lock-crashed-holder "$crashed_holder_ready" "$crashed_holder_child_pid" \
    >"$scratch_root/crashed-holder.log" 2>&1 &
runner_holder_pid=$!
for _ in {1..200}; do
    [[ -e "$crashed_holder_ready" ]] && break
    sleep 0.05
done
if [[ ! -e "$crashed_holder_ready" ]]; then
    echo "crashed OS lock holder did not become ready" >&2
    exit 1
fi

# shellcheck disable=SC2016 # Positional parameter expands in the child shell.
java "$fixture_lock_runner" "$crashed_holder_lock" /bin/sh -c ': >"$1"' \
    hev-lock-crashed-holder-contender "$crashed_holder_acquired" \
    >"$scratch_root/crashed-holder-contender.log" 2>&1 &
runner_contender_pid=$!
sleep 0.25
if [[ -e "$crashed_holder_acquired" ]]; then
    echo "OS lock admitted a contender before its holder crashed" >&2
    exit 1
fi

kill -KILL "$runner_holder_pid"
if wait "$runner_holder_pid" 2>/dev/null; then
    echo "crashed OS lock holder reported success" >&2
    exit 1
fi
runner_holder_pid=""
for _ in {1..200}; do
    ! kill -0 "$runner_contender_pid" 2>/dev/null && break
    sleep 0.05
done
if kill -0 "$runner_contender_pid" 2>/dev/null; then
    echo "OS lock was not released after holder crash" >&2
    exit 1
fi
wait "$runner_contender_pid"
runner_contender_pid=""
crashed_holder_child="$(cat "$crashed_holder_child_pid")"
for _ in {1..200}; do
    ! kill -0 "$crashed_holder_child" 2>/dev/null && break
    sleep 0.05
done
if [[ ! -e "$crashed_holder_acquired" ]]; then
    echo "holder crash did not recover the lock" >&2
    exit 1
fi
if [[ "$crashed_holder_child" =~ ^[0-9]+$ ]] &&
    kill -0 "$crashed_holder_child" 2>/dev/null; then
    echo "crashed OS lock holder left its child alive" >&2
    exit 1
fi

# An abnormal child-command death must propagate as failure and release the
# lock for the very next invocation without owner metadata or stale recovery.
crash_lock="$scratch_root/crash-lock"
crash_recovered="$scratch_root/crash-recovered"
if java "$fixture_lock_runner" "$crash_lock" /bin/sh -c 'kill -ABRT $$' \
    >"$scratch_root/crash.log" 2>&1; then
    echo "crashed command under OS lock reported success" >&2
    exit 1
fi
# shellcheck disable=SC2016 # Positional parameter expands in the child shell.
java "$fixture_lock_runner" "$crash_lock" /bin/sh -c ': >"$1"' \
    hev-lock-crash-recovery "$crash_recovered"
if [[ ! -e "$crash_recovered" ]]; then
    echo "OS lock was not immediately reusable after command crash" >&2
    exit 1
fi

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
if [[ ! -f "$lock_path" || -L "$lock_path" ||
    -e "$fixture_output/current/current" ]]; then
    echo "preparation lock is malformed or active tree is nested" >&2
    exit 1
fi
assert_no_scoped_debris

snapshot_live_metadata "$scratch_root/live-metadata-after"
if ! cmp -s "$scratch_root/live-metadata-before" "$scratch_root/live-metadata-after"; then
    echo "isolated HEV test changed live repository Git metadata" >&2
    exit 1
fi

echo "Verified isolated tracked/non-tracked mutations and concurrent atomic HEV publication"
