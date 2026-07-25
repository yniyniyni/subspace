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

val checkSpdx = tasks.register<Exec>("checkSpdx") {
    group = "verification"
    description = "Verifies AGPL SPDX headers on all source files."
    workingDir = rootDir
    commandLine("./scripts/check-spdx.sh")
}

val checkForbidden = tasks.register<Exec>("checkForbidden") {
    group = "verification"
    description = "Verifies ARCHITECTURE.md §12 bans (no runBlocking outside tests)."
    workingDir = rootDir
    commandLine("./scripts/check-forbidden.sh")
}

subprojects {
    apply(plugin = "subspace.dependency-rules")

    // Nested `include(":core:model")`-style paths auto-vivify phantom parent
    // projects (":core", ":feature") that have no build.gradle.kts and no
    // plugin applied. Only wire static analysis onto real, buildable modules
    // — applying ktlint/detekt to a phantom project with no Kotlin plugin and
    // no source directories has nothing to analyze and nothing to gain.
    if (buildFile.exists()) {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "io.gitlab.arturbosch.detekt")

        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
            buildUponDefaultConfig = true
            parallel = true
        }

        // Root-level verification tasks (SPDX headers, the runBlocking ban)
        // are resolved here at configuration time via the checkSpdx/
        // checkForbidden TaskProviders captured above — never inside a task
        // action — so this stays configuration-cache safe. Every module's
        // `check` must reach these or they are dead weight: a rule nobody
        // runs is worse than no rule.
        tasks.matching { it.name == "check" }.configureEach {
            dependsOn(checkSpdx)
            dependsOn(checkForbidden)
            // detektMain runs detekt WITH type resolution (a BindingContext),
            // which several rules — UnsafeCallOnNullableType among them —
            // silently need to produce any findings at all; the plain
            // `detekt` task (which check already depends on) analyzes with
            // an empty BindingContext and always passes clean regardless of
            // source content. detektMain only exists for modules whose Kotlin
            // compilation the Kotlin Gradle Plugin can see directly, which
            // in this project means the plain-JVM modules (subspace.jvm) —
            // it is never registered for Android modules here, since they
            // use AGP's built-in Kotlin support rather than the standalone
            // org.jetbrains.kotlin.android plugin. tasks.matching finds
            // nothing and this is a no-op on modules without it.
            dependsOn(tasks.matching { it.name == "detektMain" })
        }
    }
}

tasks.register("checkAll") {
    group = "verification"
    description = "Runs every verification task across all modules."
    dependsOn(checkSpdx)
    dependsOn(checkForbidden)
    // Same phantom-project guard as above: only real, buildable modules have
    // a "check" task to depend on.
    dependsOn(subprojects.filter { it.buildFile.exists() }.map { "${it.path}:check" })
}
