// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

// Per-pattern CPU profile of redactEveryPattern, M8.5 spec §3.2, Task 21.
//
// This is **not** a JUnit test and is never run by `:core:model:test` or
// `check` — there is no `@Test` here, so the JUnit runner never discovers
// this file. It is a plain `main()`, run by hand via the dedicated Gradle
// task `:core:model:redactionBenchmark` (see `core/model/build.gradle.kts`),
// which is not wired into `check` either. This keeps the normal test suite's
// running time untouched, per the task's "benchmarks must not slow the
// normal test suite" rule.
//
// It isolates the cost of each pattern's *pass* — the exact closure
// `redactEveryPattern` runs when that pattern's gate is true — on a line
// shaped like Task 13's row 7 flood (docs/agent/research/2026-09-26-m8.5-row7-release.md):
// a short Go/xray diagnostic with two colons but no actual IPv6 address, no
// dots, no hyphens and no destination keyword, so 7 of the 9 non-trivial
// passes are skipped by their gates and only IPV6_PATTERN and
// BARE_HOST_PREFIX_PATTERN still run on every line. A second, "varied"
// corpus line exercises the patterns the flood line's gates skip, so the
// profile also shows what a pattern costs when its gate actually lets it
// run against real match content, not only against a gate-passing miss.
//
// Only *relative* shares and the dominant pattern matter here — this is a
// JVM, not the on-device ICU engine the real regression was measured on
// (Task 13's device numbers are the authority for the actual percentage).
// The device re-measure in the Task 21 report is what decides pass/fail.

/** The exact synthetic warning line Task 13 drove its flood with. */
private const val FLOOD_LINE =
    "app/proxyman/inbound: connection ends > proxy/http: failed to read http request > malformed HTTP request " +
        "\"ZZZZ BAD\""

/** A line that trips every gate's content check, to profile a pattern actually matching. */
private const val VARIED_LINE =
    "failed to dial tcp 203.0.113.44:443: vless://11111111-2222-3333-4444-555555555555@host.example:443 " +
        "lookup vpnserver: no such host address=cdn.example.com pbk=Zm9vYmFyYmF6cXV4cXV1eGNvcmdlZ3JhdWx0 " +
        "server 2001:db8::1 refused"

private const val WARMUP_ITERATIONS = 20_000
private const val MEASURED_ITERATIONS = 300_000
private const val NANOS_PER_MS = 1_000_000.0

/** One named, timeable pass: the exact code `redactEveryPattern` runs when its gate is true. */
private data class Pass(
    val name: String,
    val apply: (String) -> String,
)

private val PASSES =
    listOf(
        Pass("URL_PATTERN") { s -> s.replace(URL_PATTERN, SENTINEL) },
        Pass("UUID_PATTERN") { s -> s.replace(UUID_PATTERN, SENTINEL) },
        Pass("IPV4_PATTERN") { s -> s.replace(IPV4_PATTERN, SENTINEL) },
        Pass("IPV6_PATTERN (whole-string regex, old)") { s ->
            s.replace(IPV6_PATTERN) { match -> if (isIpv6Address(match.value)) SENTINEL else match.value }
        },
        Pass("IPV6_PATTERN (candidate scan, new)") { s -> redactIpv6Candidates(s) },
        Pass("HOSTNAME_PATTERN") { s -> s.replace(HOSTNAME_PATTERN, SENTINEL) },
        Pass("BASE64_BLOB_PATTERN") { s -> s.replace(BASE64_BLOB_PATTERN, SENTINEL) },
        Pass("KEYED_HOST_PATTERN") { s ->
            s.replace(KEYED_HOST_PATTERN) { match -> replaceTail(match, KEYED_VALUE_GROUP) }
        },
        Pass("BARE_HOST_PREFIX_PATTERN") { s ->
            s.replace(BARE_HOST_PREFIX_PATTERN) { match -> replaceHead(match, BARE_TOKEN_GROUP) }
        },
        Pass("LABELLED_HOST_PATTERN") { s ->
            s.replace(LABELLED_HOST_PATTERN) { match -> replaceTail(match, LABELLED_TOKEN_GROUP) }
        },
    )

private fun timeNanosPerCall(
    line: String,
    apply: (String) -> String,
): Double {
    repeat(WARMUP_ITERATIONS) { apply(line) }
    val start = System.nanoTime()
    repeat(MEASURED_ITERATIONS) { apply(line) }
    val elapsed = System.nanoTime() - start
    return elapsed.toDouble() / MEASURED_ITERATIONS
}

private fun profile(
    label: String,
    line: String,
) {
    println("== $label ==")
    println("line: $line")
    val results = PASSES.map { pass -> pass.name to timeNanosPerCall(line, pass.apply) }
    val total = results.sumOf { it.second }
    results.forEach { (name, nanos) ->
        val share = if (total > 0) 100.0 * nanos / total else 0.0
        println("%-34s %10.0f ns/call  %6.2f%% of the ten-pass sum".format(name, nanos, share))
    }
    println("sum of all passes: %.4f ms/call".format(total / NANOS_PER_MS))
    println()
}

/** Times the whole gated pipeline end to end, for the "ms per line" figure Task 13 reports in. */
private fun profilePipeline(
    label: String,
    line: String,
) {
    val nanos = timeNanosPerCall(line, ::redactEveryPattern)
    println("$label full redactEveryPattern(): %.4f ms/call".format(nanos / NANOS_PER_MS))
    println()
}

fun main() {
    println("Redaction per-pattern benchmark (M8.5 spec §3.2, Task 21). JVM, not device — see KDoc.")
    println()
    profile("row 7 flood line (two colons, no match content)", FLOOD_LINE)
    profilePipeline("row 7 flood line", FLOOD_LINE)
    profile("varied line (every gate's content present)", VARIED_LINE)
    profilePipeline("varied line", VARIED_LINE)
}
