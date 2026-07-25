// SPDX-License-Identifier: AGPL-3.0-or-later
// Enforces ARCHITECTURE.md §4 module rules at configuration time.
// A violation here is cheap to fix; the same violation discovered in M6 is not.
//
// Only two of the three §4 rules are checked here:
//   - :feature:* modules never depend on each other
//   - :service depends on :core:* but never on :feature:*
// The third rule ("zero Android dependencies for :core:model and :core:parser")
// needs no check: those modules apply subspace.jvm, the plain Kotlin/JVM plugin,
// so an Android import fails to compile. That is a stronger guarantee than a
// task here could provide, and it is why those modules use a different
// convention plugin instead of an Android one with a rule bolted on.

val moduleBoundaries = tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Verifies ARCHITECTURE.md §4 module dependency rules."

    val path = project.path
    // Inspect every *declarable* configuration's dependencies, not just
    // implementation/api. debugImplementation, releaseImplementation,
    // testImplementation, androidTestImplementation, testFixturesImplementation,
    // compileOnly, and runtimeOnly are separate Configuration objects that do
    // not populate implementation.dependencies, so an allowlist of the two
    // base names misses them.
    //
    // We deliberately do NOT iterate *all* configurations: AGP wires its
    // internal test-classpath configurations (e.g.
    // debugAndroidTestCompileClasspath, debugUnitTestRuntimeClasspath) with a
    // ProjectDependency back onto this same project, so a project's test
    // sources can compile against its own production code. That is AGP
    // plumbing, not something anyone declared, and iterating every
    // configuration reports it as a spurious self "boundary violation" on
    // every single Android module. Matching on the declarable-configuration
    // name pattern (base name or *Implementation/*Api/*CompileOnly/*RuntimeOnly
    // suffix) captures every configuration a build script can actually write
    // a dependency into, across all variants, source sets, and testFixtures,
    // while skipping AGP's *Classpath resolvable configurations.
    val declarableConfigNames = setOf("implementation", "api", "compileOnly", "runtimeOnly")
    val projectDeps = configurations
        .filter { c ->
            c.name in declarableConfigNames ||
                c.name.endsWith("Implementation") ||
                c.name.endsWith("Api") ||
                c.name.endsWith("CompileOnly") ||
                c.name.endsWith("RuntimeOnly")
        }
        .flatMap { it.dependencies }
        .filterIsInstance<ProjectDependency>()
        .map { it.path }
        .distinct()

    doLast {
        val violations = mutableListOf<String>()

        if (path.startsWith(":feature:")) {
            projectDeps.filter { it.startsWith(":feature:") }.forEach {
                violations += "$path depends on $it — :feature:* modules must never depend on each other (§4)"
            }
        }

        if (path == ":service") {
            projectDeps.filter { it.startsWith(":feature:") }.forEach {
                violations += "$path depends on $it — :service must never depend on :feature:* (§4)"
            }
        }

        if (violations.isNotEmpty()) {
            error("Module boundary violations:\n" + violations.joinToString("\n") { "  - $it" })
        }
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(moduleBoundaries)
}
