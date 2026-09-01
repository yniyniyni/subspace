// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
plugins {
    id("subspace.android.library")
    id("subspace.android.hilt")
}

android {
    namespace = "space.getsub.core.xray"
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
    implementation(project(":core:parser"))
    implementation(group = "", name = "libxray", ext = "aar")
    // §5.3: every libXray call is slow and runs on Dispatchers.IO.
    implementation(libs.kotlinx.coroutines.core)
    // Tree rewriting for the passthrough config path. Already used by :core:parser
    // and :core:data — a new module edge, not a new dependency (§10.7). The typed
    // generator still emits by hand, because byte-determinism there is a property
    // of its own code rather than of a library's map ordering.
    implementation(libs.kotlinx.serialization.json)
    // ShareLinkFallbackTest uses kotest matchers; the convention plugin only
    // wires kotest-assertions into testImplementation, not androidTestImplementation.
    androidTestImplementation(libs.kotest.assertions)
    // The probes are suspend functions, so their JVM tests need runTest. Already
    // in the catalog and used by :feature:home and :feature:profiles — this is a
    // module that had no suspend surface to test until M4.5, not a new dependency.
    testImplementation(libs.kotlinx.coroutines.test)
}
