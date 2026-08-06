// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("subspace.android.library")
    id("subspace.android.hilt")
}

// The tunnel is a git submodule pinned at 2.16.0. An unpinned or absent
// submodule in a VPN client is a supply-chain question, so fail early and
// usefully rather than with a screen of ndk-build errors.
val hevDir = rootProject.file("third_party/hev-socks5-tunnel")
// Checks a NESTED submodule, not the outer one. `git submodule update --init`
// without --recursive leaves the outer Android.mk present and the vendored
// dependency trees empty, so guarding on the outer file would pass and hand the
// developer exactly the raw ndk-build error this guard exists to prevent.
if (!hevDir.resolve("third-part/lwip/Android.mk").exists()) {
    throw GradleException(
        "third_party/hev-socks5-tunnel is missing or incompletely checked out.\n" +
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

    // TerminalOutcomeTest drives a deliberately-delayed persistence write against a
    // concurrent teardown, which needs runTest's scheduler to be deterministic rather
    // than a real-clock sleep. The convention plugin puts this on androidTest only;
    // :feature:home already adds it to the JVM test set the same way.
    testImplementation(libs.kotlinx.coroutines.test)
}
