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
    val projectDeps = configurations
        .matching { it.name in setOf("implementation", "api") }
        .flatMap { it.dependencies }
        .filterIsInstance<ProjectDependency>()
        .map { it.path }

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
