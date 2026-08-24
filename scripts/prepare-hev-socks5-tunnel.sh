#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later

set -euo pipefail

script_dir="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -P "$script_dir/.." && pwd -P)"
expected_output="$repo_root/service/build/generated/hev-socks5-tunnel"

exec perl \
    "$script_dir/with-hev-prepare-lock.pl" \
    --run \
    "$repo_root" \
    "$expected_output" \
    "$script_dir/prepare-hev-socks5-tunnel-locked.sh" \
    "$@"
