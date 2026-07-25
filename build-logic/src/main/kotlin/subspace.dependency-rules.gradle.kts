// SPDX-License-Identifier: AGPL-3.0-or-later
// Enforces ARCHITECTURE.md §4 module rules at configuration time.
// A violation here is cheap to fix; the same violation discovered in M6 is not.
//
// Rules checked here:
//   - :feature:* modules never depend on each other
//   - :service depends on :core:* but never on :feature:*
//   - :core:* modules never depend on :feature:* modules (not stated as a
//     standalone bullet in §4, but §3's three-layer model — core beneath
//     feature beneath app — clearly forbids the inversion; a :core module
//     pulling in a :feature module would make the dependency graph
//     circular in spirit even where Gradle would still happily resolve it)
// The fourth rule ("zero Android dependencies for :core:model and :core:parser")
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
    // every single Android module.
    //
    // Configuration.isCanBeDeclared (Gradle's role-based configuration
    // model, since 8.2) answers exactly the question this task needs —
    // "can a build script write a dependency into this configuration" —
    // without naming a single configuration. A name-suffix allowlist
    // (endsWith("Implementation")/endsWith("Api")/...) is a proxy for that
    // question and breaks the moment AGP or a plugin invents a new
    // resolvable configuration name ending in one of those suffixes; it
    // also silently misses bucket configurations that don't follow the
    // suffix convention at all, such as ksp/kspDebug/kspRelease (declarable,
    // for `ksp(project(...))`) — those never matched the old suffix set, so
    // a module could route a boundary-violating dependency through `ksp(...)`
    // and this check would never see it. isCanBeDeclared covers both for
    // free: it is role metadata, not string matching.
    //
    // isCanBeDeclared is not, however, false on the AGP-internal
    // *Classpath configurations the comment above warns about
    // (debugUnitTestRuntimeClasspath and friends): confirmed by instrument-
    // ing this task that AGP 9.3.1 still creates them without Gradle's
    // strict role-locking factories, so they default to declarable = true
    // even though nothing in this build ever declares into them directly.
    // Every one of them carries a ProjectDependency back onto this same
    // project (test sources compiling against production code), which is
    // real AGP plumbing, not a boundary violation. Rather than name that
    // (and every future AGP internal configuration like it) explicitly,
    // drop self-references generically: a project depending on itself is
    // never a §4 violation under any of the rules below regardless of which
    // configuration it came from.
    val projectDeps = configurations
        .filter { it.isCanBeDeclared }
        .flatMap { it.dependencies }
        .filterIsInstance<ProjectDependency>()
        .map { it.path }
        .filter { it != path }
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

        if (path.startsWith(":core:")) {
            projectDeps.filter { it.startsWith(":feature:") }.forEach {
                violations += "$path depends on $it — :core:* must never depend on :feature:* (§3/§4)"
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
