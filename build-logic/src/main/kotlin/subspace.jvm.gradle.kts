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

apply(plugin = "org.jlleitschuh.gradle.ktlint")
apply(plugin = "io.gitlab.arturbosch.detekt")

extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
}
