# Third-party components and attribution

Subspace is licensed under the GNU Affero General Public License v3.0 or later
(see `LICENSE`). This file records every upstream component the project depends
on or adapts code from, and the obligations that come with each.

Per ARCHITECTURE.md §10.8: **code adapted from a copyleft-licensed client keeps
its original license.** When adapting non-trivial logic, add a comment at the
top of the file naming the upstream project, the file, the commit, and the
license.

---

## Runtime dependencies

| Component | Version pin | License | Obligation |
|---|---|---|---|
| [Xray-core](https://github.com/XTLS/Xray-core) | v26.7.11 | MPL-2.0 | File-level copyleft. GPL/AGPL-compatible. Consumed as a binary through libXray; not modified. |
| [libXray](https://github.com/XTLS/libXray) | v26.7.11 | MIT | Attribution only. |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | 2.16.0 (git submodule, `third_party/`) | MIT | Attribution only. Compiled from source into `libtun2socks.so`. One file, `src/hev-jni.c`, is excluded — it registers JNI natives against upstream's own app class and aborts any other process that loads it. See `service/src/main/jni/Android.mk`. Its vendored dependencies (yaml, lwip, hev-task-system) are built unmodified. |
| [ZXing](https://github.com/zxing/zxing) | see version catalog | Apache-2.0 | Attribution + NOTICE. Replaces ML Kit, which is proprietary and disqualifies the app from F-Droid and IzzyOnDroid. |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.9.0 | Apache-2.0 | Attribution only. JSON for `vmess://` bodies and raw Xray configs. `:core:parser` is pure JVM, so Android's `org.json` is unavailable there. |
| [kaml](https://github.com/charleskorn/kaml) | 0.83.0 | Apache-2.0 | Attribution only. Clash YAML. Neither snakeyaml-engine nor the KMP port below instantiates arbitrary types by default — chosen over snakeyaml 1.x for that reason, since subscription content is attacker-controllable and snakeyaml's default `Constructor` is the CVE-2022-1471 gadget surface. |
| [snakeyaml-engine-kmp](https://github.com/krzema12/snakeyaml-engine-kmp) (`it.krzeminski:snakeyaml-engine-kmp`) | 3.1.1 | Apache-2.0 | Attribution only. **Transitive, via kaml** — the YAML 1.2 engine that actually parses Clash configs. A Kotlin Multiplatform port maintained by a separate party from upstream snakeyaml, so it is its own supply-chain question, not a detail of kaml's. |
| [Okio](https://github.com/square/okio) (`com.squareup.okio:okio`) | 3.14.0 (resolved; kaml's chain declares 3.10.2) | Apache-2.0 | Attribution only. **Transitive, via kaml → snakeyaml-engine-kmp.** I/O primitives for the YAML reader. |
| [UrlEncoder](https://github.com/ethauvin/urlencoder) (`net.thauvin.erik.urlencoder:urlencoder-lib`) | 1.6.0 | Apache-2.0 | Attribution only. **Transitive, via kaml → snakeyaml-engine-kmp.** Not used directly by this project; recorded because it ships in the APK. |

Bundled `geoip.dat` / `geosite.dat` originate from
[v2fly/domain-list-community](https://github.com/v2fly/domain-list-community)
(MIT) and [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat)
(GPL-3.0 for the build tooling; the emitted `.dat` files are data). Record the
exact source and release tag in the assets README when they are first added.

All versions above are the **resolved** coordinates from
`./gradlew :core:parser:dependencies --configuration runtimeClasspath`, and each
licence is the one declared in that artifact's own POM — neither is transcribed
from a project README.

---

## Test dependencies

Not shipped in the APK, so no distribution obligation attaches. Recorded because
ARCHITECTURE.md §10.7 makes every dependency a supply-chain question regardless
of which source set it lands in.

| Component | Version pin | License | Obligation |
|---|---|---|---|
| [JUnit 4](https://github.com/junit-team/junit4) | 4.13.2 | EPL-1.0 | Test-only. Not distributed, so the EPL's reciprocity does not reach the shipped app. |
| [kotest-assertions-core](https://github.com/kotest/kotest) | 5.9.1 | Apache-2.0 | Test-only. Assertions in every module's unit tests, and in `:core:xray`'s `androidTest` — wired there explicitly because the convention plugin only adds it to `testImplementation`. Assertions only; the kotest *runner* is deliberately not used, so tests stay plain JUnit 4. |
| [kotlinx.coroutines-test](https://github.com/Kotlin/kotlinx.coroutines) | 1.10.2 | Apache-2.0 | Test-only. Supplies the Main dispatcher `viewModelScope` needs off-device. |
| [androidx.test](https://developer.android.com/jetpack/androidx/releases/test) runner + ext-junit | 1.7.0 / 1.3.0 | Apache-2.0 | Test-only. Instrumented tests, which are the only place libXray and tun2socks can actually run (§11). |

---

## Build tooling

| Component | Version pin | License | Obligation |
|---|---|---|---|
| Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) | Gradle 9.6.0 | Apache-2.0 | Attribution + NOTICE. Vendored so a clone builds without a pre-installed Gradle. Not modified. |
| [detekt-formatting](https://github.com/detekt/detekt) | see version catalog (matches `detekt`) | Apache-2.0 | Attribution only. Wraps ktlint's rules as detekt rules; added because ktlint-gradle's own per-source-set check tasks never register on Android modules under AGP 9 (see `build.gradle.kts` and `subspace.android.library.gradle.kts` for why) — this is the actual enforcement of formatting on `:app`, `:service`, `:core:data`, `:core:xray`, and every `:feature:*` module. Justification per ARCHITECTURE.md §10.7. |
| [kotlinx-kover](https://github.com/Kotlin/kotlinx-kover) | 0.9.8 | Apache-2.0 | Build-time-only JVM line-coverage measurement; not shipped in the APK. ARCHITECTURE.md §7 and the roadmap set a near-100% coverage exit criterion for `:core:parser`; without coverage tooling, that criterion was unfalsifiable. Justification per ARCHITECTURE.md §10.7. |

M1 vendors an AAR (libXray) and a native library (hev-socks5-tunnel); this
section is the precedent for how those get recorded.

---

## Code adaptation sources

Read these before writing the corresponding subsystem. **Licence determines
whether you may copy or only learn.**

| Project | License | May we copy? |
|---|---|---|
| [SaeedDev94/Xray](https://github.com/SaeedDev94/Xray) | **MIT** | **Yes.** Relicensable into AGPL-3.0 with attribution. Kotlin + Xray-core + hev-socks5-tunnel — the same stack. Primary source for the JNI bridge and service layer. |
| [heiher/sockstun](https://github.com/heiher/sockstun) | **MIT** | **Yes**, with attribution. Reference JNI shim for hev-socks5-tunnel. |
| [2dust/v2rayNG](https://github.com/2dust/v2rayNG) | GPL-3.0 | **No.** Read for behaviour only — reconnect, Doze, and network-transition edge cases. Copied files would remain GPL-3.0; AGPL-3.0 may be combined with GPL-3.0 code but cannot relicense it. |
| [Ko4Learner/LibreXrayVPN](https://github.com/Ko4Learner/LibreXrayVPN) | GPL-3.0 | **No.** Same restriction. Read as an architecture skeleton only. |
| [2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) | LGPL-3.0 | Not used. Rejected in favour of libXray (MIT) — see ARCHITECTURE.md §14. |
| [remnawave/panel](https://github.com/remnawave/panel) | AGPL-3.0 | Specification source for the HWID header contract (§A.4.1), not a code source. |
| [Happ](https://happ.su/main/dev-docs) | Proprietary, docs public | Protocol documentation only. No code. |

---

## AGPL §13 note

The AGPL network-use clause obliges anyone who *modifies* Subspace and offers it
over a network to publish their source. Subspace is a client that makes no
network service available to third parties, so in normal use §13 adds nothing
beyond GPL-3.0. The licence is chosen for consistency with the wider ecosystem
(Remnawave, the panel this project targets for compatibility, is AGPL-3.0).
