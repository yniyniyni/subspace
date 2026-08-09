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
    implementation(project(":core:network"))
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
