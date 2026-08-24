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
nested_specs=(
    "src/core cbff465b916832455c1cb02f1f9e25a41062054d"
    "third-part/hev-task-system b1afa0e21fb4ed5a69560e78e54baf0efdebe171"
    "third-part/lwip 2a11c14c7a32887af25a034e82ef18b0b12076ac"
    "third-part/yaml efa36117a8646d26d12b58e05bac472d7854a70d"
)

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
if [[ -L "$output_dir" || ( -e "$output_dir" && ! -d "$output_dir" ) ]]; then
    echo "refusing non-directory generated HEV root: $output_dir" >&2
    exit 6
fi

output_parent="$(dirname "$output_dir")"
lock_path="${output_dir}.prepare.lock"
invocation_token="$$.$RANDOM.$(date +%s)"
lock_claimed=0
lock_claim="${lock_path}.claim.${invocation_token}"
scratch_root=""
published_version=""
active_published=0
active_link_tmp=""
legacy_backup="${output_dir}.legacy.${invocation_token}"
legacy_moved=0
mkdir -p "$output_parent"

release_lock() {
    local recorded_token=""

    if [[ "$lock_claimed" -eq 1 && -f "$lock_path" ]]; then
        read -r recorded_token _ <"$lock_path" || true
        if [[ "$recorded_token" == "$invocation_token" ]]; then
            rm -f "$lock_path"
        fi
    fi
    rm -f "$lock_claim"
}

cleanup() {
    local status=$?

    trap - EXIT INT TERM
    if [[ -n "$active_link_tmp" && -L "$active_link_tmp" ]]; then
        rm -f "$active_link_tmp"
    fi
    if [[ -n "$published_version" && "$active_published" -eq 0 &&
        -d "$published_version" ]]; then
        rm -rf "$published_version"
    fi
    if [[ "$legacy_moved" -eq 1 && -d "$legacy_backup" ]]; then
        if [[ -L "$output_dir/current" ]]; then
            rm -rf "$legacy_backup"
        else
            rm -rf "$output_dir"
            mv "$legacy_backup" "$output_dir"
        fi
    fi
    if [[ -n "$scratch_root" && -d "$scratch_root" ]]; then
        rm -rf "$scratch_root"
    fi
    release_lock
    exit "$status"
}
trap cleanup EXIT INT TERM

printf '%s %s %s\n' "$invocation_token" "$$" "$(date +%s)" >"$lock_claim"
lock_deadline=$((SECONDS + 120))
while ! ln "$lock_claim" "$lock_path" 2>/dev/null; do
    owner_token=""
    owner_pid=""
    if [[ -f "$lock_path" ]]; then
        read -r owner_token owner_pid _ <"$lock_path" || true
    fi

    stale=0
    if [[ -n "$owner_token" && "$owner_pid" =~ ^[0-9]+$ ]]; then
        if ! kill -0 "$owner_pid" 2>/dev/null; then
            stale=1
        fi
    else
        lock_mtime="$(stat -f %m "$lock_path" 2>/dev/null || stat -c %Y "$lock_path" 2>/dev/null || true)"
        now="$(date +%s)"
        if [[ "$lock_mtime" =~ ^[0-9]+$ && $((now - lock_mtime)) -ge 5 ]]; then
            stale=1
        fi
    fi

    if [[ "$stale" -eq 1 ]]; then
        stale_lock="${lock_path}.stale.${invocation_token}"
        if mv "$lock_path" "$stale_lock" 2>/dev/null; then
            rm -f "$stale_lock"
            continue
        fi
    fi

    if [[ "$SECONDS" -ge "$lock_deadline" ]]; then
        echo "timed out waiting for HEV preparation lock: $lock_path" >&2
        exit 7
    fi
    sleep 0.05
done
lock_claimed=1

parent_gitlink="$(
    git -C "$repo_root" ls-files --stage -- third_party/hev-socks5-tunnel |
        awk 'NR == 1 { print $2 }'
)"
if [[ "$parent_gitlink" != "$expected_commit" ]]; then
    echo "parent HEV gitlink must be $expected_commit, found ${parent_gitlink:-missing}" >&2
    exit 8
fi

actual_commit="$(git -C "$source_dir" rev-parse HEAD)"
if [[ "$actual_commit" != "$expected_commit" ]]; then
    echo "hev-socks5-tunnel must be pinned at $expected_commit, found $actual_commit" >&2
    exit 9
fi

dirty="$(
    git -C "$source_dir" status --porcelain=v1 --untracked-files=no \
        --ignore-submodules=untracked
)"
if [[ -n "$dirty" ]]; then
    echo "hev-socks5-tunnel tracked files must match the pinned commit" >&2
    exit 9
fi

gitlink_count="$(
    git -C "$source_dir" ls-files --stage |
        awk '$1 == 160000 { count++ } END { print count + 0 }'
)"
if [[ "$gitlink_count" -ne "${#nested_specs[@]}" ]]; then
    echo "unexpected HEV nested submodule count: $gitlink_count" >&2
    exit 10
fi

for spec in "${nested_specs[@]}"; do
    nested_path="${spec%% *}"
    expected_nested_commit="${spec#* }"
    nested_dir="$source_dir/$nested_path"
    read -r mode gitlink_commit stage gitlink_path <<<"$(
        git -C "$source_dir" ls-files --stage -- "$nested_path"
    )"

    if [[ "$mode" != "160000" || "$stage" != "0" ||
        "$gitlink_path" != "$nested_path" ||
        "$gitlink_commit" != "$expected_nested_commit" ]]; then
        echo "nested HEV gitlink mismatch for $nested_path" >&2
        exit 11
    fi

    actual_nested_commit="$(git -C "$nested_dir" rev-parse HEAD)"
    if [[ "$actual_nested_commit" != "$expected_nested_commit" ]]; then
        echo "nested HEV checkout mismatch for $nested_path: expected $expected_nested_commit, found $actual_nested_commit" >&2
        exit 12
    fi

    nested_dirty="$(git -C "$nested_dir" status --porcelain=v1 --untracked-files=no)"
    if [[ -n "$nested_dirty" ]]; then
        echo "nested HEV tracked files must match $expected_nested_commit: $nested_path" >&2
        exit 13
    fi

    deeper_gitlinks="$(
        git -C "$nested_dir" ls-files --stage |
            awk '$1 == 160000 { count++ } END { print count + 0 }'
    )"
    if [[ "$deeper_gitlinks" -ne 0 ]]; then
        echo "unexpected recursively nested HEV gitlink under $nested_path" >&2
        exit 14
    fi
done

for required in \
    src/hev-main.c \
    src/hev-main.h \
    src/hev-socks5-tunnel.c \
    src/hev-socks5-tunnel.h; do
    if ! git -C "$source_dir" cat-file -e "$expected_commit:$required"; then
        echo "pinned HEV tree is missing tracked file: $required" >&2
        exit 15
    fi
done
for required in \
    "third-part/lwip 2a11c14c7a32887af25a034e82ef18b0b12076ac Android.mk" \
    "third-part/hev-task-system b1afa0e21fb4ed5a69560e78e54baf0efdebe171 Android.mk" \
    "third-part/yaml efa36117a8646d26d12b58e05bac472d7854a70d Android.mk"; do
    read -r nested_path expected_nested_commit required_path <<<"$required"
    if ! git -C "$source_dir/$nested_path" cat-file -e "$expected_nested_commit:$required_path"; then
        echo "pinned nested HEV tree is missing tracked file: $nested_path/$required_path" >&2
        exit 16
    fi
done

scratch_root="$(mktemp -d "$output_parent/.hev-socks5-tunnel.XXXXXX")"
staging_dir="$scratch_root/tree"
expected_before_raw="$scratch_root/expected-before.raw"
expected_before="$scratch_root/expected-before"
expected_after_raw="$scratch_root/expected-after.raw"
expected_after="$scratch_root/expected-after"
actual_manifest="$scratch_root/actual"
expected_changed="$scratch_root/expected-changed"
actual_changed="$scratch_root/actual-changed"
mkdir -p "$staging_dir"

append_index_manifest() {
    local repo="$1"
    local prefix="$2"
    local destination="$3"
    local entry metadata path mode

    while IFS= read -r -d '' entry; do
        metadata="${entry%%$'\t'*}"
        path="${entry#*$'\t'}"
        mode="${metadata%% *}"
        if [[ "$mode" == "160000" ]]; then
            continue
        fi
        printf '%s\t%s%s\0' "$metadata" "$prefix" "$path" >>"$destination"
    done < <(git -C "$repo" ls-files --stage -z)
}

: >"$expected_before_raw"
append_index_manifest "$source_dir" "" "$expected_before_raw"
git -C "$source_dir" checkout-index --all --force --prefix="$staging_dir/"

for spec in "${nested_specs[@]}"; do
    nested_path="${spec%% *}"
    nested_dir="$source_dir/$nested_path"
    mkdir -p "$staging_dir/$nested_path"
    append_index_manifest "$nested_dir" "$nested_path/" "$expected_before_raw"
    git -C "$nested_dir" checkout-index --all --force --prefix="$staging_dir/$nested_path/"
done

LC_ALL=C sort -z "$expected_before_raw" >"$expected_before"

git -C "$staging_dir" init -q
git -C "$staging_dir" add -f -A
git -C "$staging_dir" ls-files --stage -z | LC_ALL=C sort -z >"$actual_manifest"
if ! cmp -s "$expected_before" "$actual_manifest"; then
    echo "generated HEV inventory does not match recursively pinned tracked files" >&2
    exit 17
fi
base_tree="$(git -C "$staging_dir" write-tree)"

git -C "$staging_dir" apply --unidiff-zero --check "$patch_file"
git -C "$staging_dir" apply --unidiff-zero "$patch_file"
git -C "$staging_dir" add -f -A

printf '%s\0' \
    src/hev-main.c \
    src/hev-main.h \
    src/hev-socks5-tunnel.c \
    src/hev-socks5-tunnel.h |
    LC_ALL=C sort -z >"$expected_changed"
git -C "$staging_dir" diff-index --cached --name-only -z "$base_tree" -- |
    LC_ALL=C sort -z >"$actual_changed"
if ! cmp -s "$expected_changed" "$actual_changed"; then
    echo "HEV patch changed files outside the reviewed four-file inventory" >&2
    exit 18
fi

: >"$expected_after_raw"
while IFS= read -r -d '' entry; do
    metadata="${entry%%$'\t'*}"
    path="${entry#*$'\t'}"
    read -r mode object stage <<<"$metadata"
    case "$path" in
    src/hev-main.c)
        object="4531e91976da4c57f596bb20eda5ed9f02747caf"
        ;;
    src/hev-main.h)
        object="ce2d203cb35322d6768c56564b7906abffec424e"
        ;;
    src/hev-socks5-tunnel.c)
        object="48463549d80d216c3e68b34d80e59bed26c7ddf8"
        ;;
    src/hev-socks5-tunnel.h)
        object="4ac2071a03975167510639d653148143dfa25f6c"
        ;;
    esac
    printf '%s %s %s\t%s\0' "$mode" "$object" "$stage" "$path" >>"$expected_after_raw"
done <"$expected_before"
LC_ALL=C sort -z "$expected_after_raw" >"$expected_after"

git -C "$staging_dir" ls-files --stage -z | LC_ALL=C sort -z >"$actual_manifest"
if ! cmp -s "$expected_after" "$actual_manifest"; then
    echo "patched HEV inventory differs from pinned files plus the reviewed four blobs" >&2
    comm -3 \
        <(tr '\0' '\n' <"$expected_after") \
        <(tr '\0' '\n' <"$actual_manifest") |
        awk 'NR <= 40' >&2
    exit 19
fi

rm -rf "$staging_dir/.git"

capture_inventory() {
    local tree="$1"
    local destination="$2"
    local label="$3"
    local metadata_dir="$scratch_root/inventory-$label"

    git init -q "$metadata_dir"
    git --git-dir="$metadata_dir/.git" --work-tree="$tree" add -f -A
    git --git-dir="$metadata_dir/.git" --work-tree="$tree" ls-files --stage -z |
        LC_ALL=C sort -z >"$destination"
}

if [[ -d "$output_dir" && ! -e "$output_dir/current" &&
    ! -L "$output_dir/current" && ! -d "$output_dir/versions" ]]; then
    mv "$output_dir" "$legacy_backup"
    legacy_moved=1
    mkdir -p "$output_dir"
elif [[ ! -e "$output_dir" ]]; then
    mkdir -p "$output_dir"
fi

if [[ -e "$output_dir/current" && ! -L "$output_dir/current" ]]; then
    echo "generated HEV current path must be an atomic symlink" >&2
    exit 20
fi
mkdir -p "$output_dir/versions"

if [[ -L "$output_dir/current" && -d "$output_dir/current" ]]; then
    active_manifest="$scratch_root/active-manifest"
    capture_inventory "$output_dir/current" "$active_manifest" active
    if cmp -s "$expected_after" "$active_manifest"; then
        if [[ "$legacy_moved" -eq 1 ]]; then
            rm -rf "$legacy_backup"
            legacy_moved=0
        fi
        echo "Reused verified recursive HEV inventory $expected_commit with $(basename "$patch_file")"
        exit 0
    fi
fi

manifest_id="$(git hash-object "$expected_after")"
publish_name="${manifest_id}.${invocation_token}"
published_version="$output_dir/versions/$publish_name"
mv "$staging_dir" "$published_version"

active_link_tmp="$output_dir/.current.${invocation_token}"
ln -s "versions/$publish_name" "$active_link_tmp"
if [[ -L "$output_dir/current" ]]; then
    case "$(uname -s)" in
    Darwin)
        mv -fh "$active_link_tmp" "$output_dir/current"
        ;;
    Linux)
        mv -Tf "$active_link_tmp" "$output_dir/current"
        ;;
    *)
        echo "unsupported platform for atomic HEV symlink replacement" >&2
        exit 21
        ;;
    esac
else
    mv "$active_link_tmp" "$output_dir/current"
fi
active_link_tmp=""
active_published=1

if [[ "$legacy_moved" -eq 1 ]]; then
    rm -rf "$legacy_backup"
    legacy_moved=0
fi

echo "Published verified recursive HEV inventory $expected_commit with $(basename "$patch_file")"
