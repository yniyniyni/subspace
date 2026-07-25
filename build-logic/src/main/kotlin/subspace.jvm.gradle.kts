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
    add("implementation", libs.findLibrary("kotlinx-coroutines-core").get())
    add("testImplementation", libs.findLibrary("junit").get())
    add("testImplementation", libs.findLibrary("kotest-assertions").get())
    add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
}

// ktlint + detekt are applied uniformly to every real module (including
// :app) from the root build.gradle.kts subprojects{} block, not here — see
// that file for why.
