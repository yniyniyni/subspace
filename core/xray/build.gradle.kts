// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("subspace.android.library")
}

android {
    namespace = "art.yniyniyni.subspace.core.xray"
}

// The AAR is gitignored (ARCHITECTURE.md §10.7 — a 91 MB binary blob in an AGPL
// tree is a supply-chain and licensing liability). Fail early and usefully on a
// fresh clone rather than with an unresolved-symbol wall in XrayController.
val libXrayAar = file("libs/libxray.aar")
if (!libXrayAar.exists()) {
    throw GradleException(
        "core/xray/libs/libxray.aar is missing.\n" +
            "Run ./scripts/fetch-native.sh from the repo root — it downloads the\n" +
            "pinned libXray release and verifies its SHA-256.",
    )
}

dependencies {
    implementation(project(":core:model"))
    implementation(files("libs/libxray.aar"))
}
