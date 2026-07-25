// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    id("subspace.dependency-rules") apply false
}

subprojects {
    apply(plugin = "subspace.dependency-rules")
}

val checkSpdx = tasks.register<Exec>("checkSpdx") {
    group = "verification"
    description = "Verifies AGPL SPDX headers on all source files."
    workingDir = rootDir
    commandLine("./scripts/check-spdx.sh")
}

tasks.register("checkAll") {
    group = "verification"
    description = "Runs every verification task across all modules."
    dependsOn(checkSpdx)
    // Nested `include(":core:model")`-style paths auto-vivify phantom parent
    // projects (":core", ":feature") that have no build.gradle.kts and no
    // plugin applied, so they never gain a "check" task. Only depend on
    // subprojects that are real, buildable modules.
    dependsOn(subprojects.filter { it.buildFile.exists() }.map { "${it.path}:check" })
}
