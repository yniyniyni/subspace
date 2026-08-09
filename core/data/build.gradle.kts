// SPDX-License-Identifier: AGPL-3.0-or-later
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
    // api, not implementation: SyncResult.Failed.reason is FetchFailure, and
    // SyncResult is part of :core:data's own public API (SubscriptionSyncer.sync's
    // return type). Task 13 is the first caller that names FetchFailure directly
    // (mapping it to a UserMessage in :feature:profiles) — with `implementation`,
    // that module's own build.gradle.kts would need its own project(":core:network")
    // edge to resolve the type, which checkModuleBoundaries forbids for every
    // module but this one (§4: "only :core:data may depend on :core:network").
    // `api` here keeps that edge singular — :feature:profiles declares no
    // :core:network dependency of its own, it only sees the type transitively
    // through this module's already-allowed one.
    api(project(":core:network"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.room.compiler)

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
