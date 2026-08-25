// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
plugins {
    id("subspace.android.library")
    id("subspace.android.hilt")
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "art.yniyniyni.subspace.core.data"

    testFixtures {
        enable = true
    }

    // Schema export makes migrations reviewable in the diff instead of
    // discovered on a user's device.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:parser"))
    // implementation, NOT api — deliberately, and this must stay implementation.
    // §4: only :core:data may depend on :core:network, enforced by
    // checkModuleBoundaries — but that task only inspects each module's own
    // *declared* project(...) dependencies. It cannot see a type that leaks in
    // transitively, so an `api` dependency here would make FetchFailure resolvable
    // (and compilable) in every module downstream of :core:data while the check
    // kept reporting the rule as satisfied — the rule would be true on paper and
    // false in practice. Task 13 tried exactly this (SyncResult.Failed.reason
    // used to be FetchFailure directly, so :feature:profiles needed the type on
    // its classpath) and a branch review caught it: switching to `api` was the
    // wrong fix for a real problem one layer down. The actual fix is
    // SubscriptionSyncFailure (core/data/src/main/.../sync/SubscriptionSyncFailure.kt)
    // — SyncResult.Failed now carries that :core:data-owned type instead, translated
    // from FetchFailure once, inside this module, where :core:network is legitimately
    // visible. If a future change makes this fail to resolve again, the fix is
    // another translation type at this boundary, never `api` on this line.
    implementation(project(":core:network"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.room.compiler)

    // The progress registry is plain coroutines and no Room, so its test is a
    // JVM unit test — which needs runTest, absent from the shared library plugin.
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotest.assertions)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    // Task 12: an in-memory SubscriptionRepository builder, shared with :app's
    // androidTest so SubscriptionRefreshWorkerTest can drive a real repository
    // without depending on :core:data's internal DAO/Database types (§4 keeps
    // those internal on purpose — DI-only construction).
    testFixturesImplementation(project(":core:model"))
    testFixturesImplementation(project(":core:network"))
    testFixturesImplementation(libs.room.runtime)
    testFixturesImplementation(libs.room.testing)
}
