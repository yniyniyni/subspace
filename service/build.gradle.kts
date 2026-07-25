// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("subspace.android.library")
    id("subspace.android.hilt")
}

// The tunnel is a git submodule pinned at 2.16.0. An unpinned or absent
// submodule in a VPN client is a supply-chain question, so fail early and
// usefully rather than with a screen of ndk-build errors.
val hevDir = rootProject.file("third_party/hev-socks5-tunnel")
if (!hevDir.resolve("Android.mk").exists()) {
    throw GradleException(
        "third_party/hev-socks5-tunnel is missing or empty.\n" +
            "Run: git submodule update --init --recursive",
    )
}

android {
    namespace = "art.yniyniyni.subspace.service"

    buildFeatures {
        aidl = true
    }

    defaultConfig {
        ndk {
            // Mirrors service/src/main/jni/Application.mk. 32-bit x86 is omitted
            // deliberately — effectively dead on real devices, and it doubles
            // native build time.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        // ndk-build, not CMake: upstream ships its own Android.mk and documents
        // ndk-build as the Android path. It compiles three vendored static
        // libraries with per-ABI flags, and reimplementing that in CMake would
        // be a standing source of silent drift.
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:xray"))
    implementation(project(":core:data"))
}
