// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Routes.kt's @Serializable route objects/classes need this — Navigation
    // Compose's type-safe routes serialize the route through kotlinx.serialization
    // under the hood, same reason :core:parser and :core:data carry it.
    alias(libs.plugins.kotlin.serialization)
    id("subspace.android.hilt")
}

android {
    namespace = "art.yniyniyni.subspace"
    // See the note in subspace.android.library: compileSdk leads targetSdk on purpose.
    compileSdk = 37

    defaultConfig {
        applicationId = "art.yniyniyni.subspace"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha01"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Both convention plugins (subspace.android.library, subspace.jvm) set
        // this; :app builds its own android {} block directly rather than
        // through subspace.android.library, so it was the one module silently
        // exempt from failing on compiler warnings.
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":feature:home"))
    implementation(project(":feature:profiles"))
    implementation(project(":feature:routing"))
    implementation(project(":feature:settings"))
    implementation(project(":service"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // FloatingNavigationBar's NavItem needs an ImageVector per top-level
    // destination. -core (not -extended) only: Home/List/Settings cover the
    // three top-level destinations without pulling in the several-thousand-icon
    // extended set. THIRD_PARTY.md records this.
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Same gap as the androidTest block below: :app doesn't go through
    // subspace.android.library, so the plain JVM unit-test kit every other
    // Android module gets from that convention plugin isn't present here
    // either. ThemeResolutionTest is this module's first JVM unit test.
    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)

    // :app builds android {} directly rather than through subspace.android.library
    // (see the kotlin {} block's comment above), so none of the androidTest kit
    // that convention plugin wires for every other Android module is present here
    // — it all has to be declared explicitly, same as core/ui/build.gradle.kts
    // does for its own first Compose instrumented tests.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotest.assertions)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    // Pinned above the 3.5.0 compose-ui-test-junit4 pulls in transitively —
    // 3.5.0 fails every assertion on this API 37 test device (see
    // THIRD_PARTY.md / core/ui/build.gradle.kts for the full explanation).
    androidTestImplementation(libs.androidx.test.espresso.core)
}
