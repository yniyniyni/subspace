// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
plugins {
    id("subspace.jvm")
}

// M8.5 spec §3.2, Task 21: a hand-run micro-benchmark for Redaction.kt's
// per-pattern cost. Deliberately not a dependency of `test` or `check` — it
// is a plain `main()` (no `@Test`), run only via
// `./gradlew :core:model:redactionBenchmark`, so it never slows the normal
// suite.
tasks.register<JavaExec>("redactionBenchmark") {
    group = "verification"
    description = "Measures redact()'s per-pattern CPU cost by hand. Not part of check or test."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("space.getsub.core.model.RedactionBenchmarkKt")
}
