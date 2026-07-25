# SPDX-License-Identifier: AGPL-3.0-or-later
#
# x86 (32-bit) is omitted deliberately: it is effectively dead on real devices
# and doubles native build time. arm64-v8a is what ships; the other two exist
# for older hardware and emulators.
APP_ABI := arm64-v8a armeabi-v7a x86_64

# Matches minSdk 26 in subspace.android.library.
APP_PLATFORM := android-26

# Pure C, no C++ runtime needed.
APP_STL := none
