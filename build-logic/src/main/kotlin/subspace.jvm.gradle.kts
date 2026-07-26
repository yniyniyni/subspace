// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    // No kotlinx-coroutines-core/-test here: per the M1 spec, neither
    // :core:model nor :core:parser (the only modules using this convention
    // plugin) uses coroutines, and ARCHITECTURE.md §10.7 forbids
    // speculative dependencies. Add it back on the module that actually
    // needs it, not here, if a future milestone changes that.
    add("testImplementation", libs.findLibrary("junit").get())
    add("testImplementation", libs.findLibrary("kotest-assertions").get())
}

// ktlint and detekt are both applied uniformly to every real module
// (including :app) from the root build.gradle.kts subprojects{} block, not
// here — see that file for why. Modules using this convention plugin
// (plain org.jetbrains.kotlin.jvm) are the one place ktlint-gradle's own
// per-source-set check tasks DO register — the plugin ID they key off is
// present here, unlike on Android modules. detekt-formatting (see the root
// script) covers this module too, so formatting is doubly enforced here.
