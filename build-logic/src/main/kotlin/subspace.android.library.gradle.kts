// SPDX-License-Identifier: AGPL-3.0-or-later
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.library")
}

extensions.configure<LibraryExtension> {
    // compileSdk is deliberately ahead of targetSdk (36, set in :app).
    // androidx.lifecycle 2.11 refuses to be consumed below 37. compileSdk only
    // governs which APIs are visible at build time and has no runtime effect, so
    // ARCHITECTURE.md §9/§14.1's foregroundServiceType analysis — which depends on
    // targetSdk — is unaffected. Do not "align" these two numbers.
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // No consumerProguardFiles here: AGP fails the build if the named file is
        // absent, and no module currently ships consumer rules. Add it per-module
        // when one genuinely needs them — :core:xray likely will, once the libXray
        // AAR arrives in M1 and its Go-generated classes need keep rules.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

// defaultConfig above names an instrumentation runner. Without these
// dependencies that setting is inert — androidTest source does not compile at
// all, so a module can look instrumented-test-ready and have no way to run one.
// ARCHITECTURE.md §11 puts repositories and Room on instrumented tests, so every
// Android library module needs this, not just the one that discovered it.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    add("androidTestImplementation", libs.findLibrary("junit").get())
    add("androidTestImplementation", libs.findLibrary("androidx-test-runner").get())
    add("androidTestImplementation", libs.findLibrary("androidx-test-ext-junit").get())
}

// ktlint and detekt are both applied uniformly to every real module
// (including :app) from the root build.gradle.kts subprojects{} block, not
// here — see that file for why. But "applied" is not "enforced": on Android
// modules (this convention plugin's whole purpose), ktlint-gradle's own
// per-source-set check tasks never register, because they are wired from
// plugins.withId("org.jetbrains.kotlin.android"), a plugin AGP 9 hard-errors
// on. Formatting is actually enforced here via detekt-formatting, which runs
// inside the plain `detekt` task. Do not assume `ktlintCheck` lints Kotlin
// sources on modules using this plugin — it does not.
