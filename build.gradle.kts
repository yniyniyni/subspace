// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // No kotlin.android alias: AGP 9.3.1 supplies Kotlin support itself and
    // hard-errors if org.jetbrains.kotlin.android is applied. Leaving the
    // alias in the catalog would invite a future agent to apply it and hit
    // that error, so it is deleted here, not just left unused.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
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
            // Android modules under AGP 9 never get a Kotlin-plugin-keyed
            // source set (see the ktlint note below), so the default
            // src/main + src/test source is all detekt would otherwise see.
            // Add the variant-specific dirs explicitly so src/debug,
            // src/release, and src/androidTest are actually analyzed instead
            // of silently skipped.
            source.setFrom(
                "src/main/kotlin",
                "src/main/java",
                "src/test/kotlin",
                "src/test/java",
                "src/debug/kotlin",
                "src/debug/java",
                "src/release/kotlin",
                "src/release/java",
                "src/androidTest/kotlin",
                "src/androidTest/java",
            )
        }

        // ktlint-gradle registers its per-source-set check tasks
        // (ktlintMainSourceSetCheck etc.) from
        // plugins.withId("org.jetbrains.kotlin.android"). AGP 9 supplies
        // Kotlin support itself and hard-errors if that plugin is applied,
        // so on every Android module (:app, :service, :core:data, :core:xray,
        // every :feature:*) that callback never fires and ktlint lints
        // nothing but .gradle.kts scripts there — a check that always
        // passes regardless of formatting. detekt-formatting wraps ktlint's
        // own rules as detekt rules and runs inside the plain `detekt` task,
        // which IS confirmed working (via type-resolution-free analysis) on
        // every module including Android ones, so it is wired here instead
        // of relying on ktlint-gradle's source-set tasks. See
        // THIRD_PARTY.md for licensing (Apache-2.0).
        // rootProject, not the subproject `extensions`: this subprojects{}
        // action runs against each subproject before that subproject's own
        // build script (and its own plugin applications) has evaluated, so
        // VersionCatalogsExtension is not yet guaranteed to exist on the
        // subproject itself. The root project already has it — proven by
        // `libs.plugins.*` working at the top of this very file — so look it
        // up there instead.
        val catalogLibs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies.add("detektPlugins", catalogLibs.findLibrary("detekt-formatting").get())

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
