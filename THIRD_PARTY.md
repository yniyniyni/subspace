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
| [ZXing](https://github.com/zxing/zxing) (`com.google.zxing:core`) | 3.5.3 | Apache-2.0 | **Used for:** Decoding QR codes from camera frames (`QrAnalyzer`, Task 20). **Justification (§10.7):** ARCHITECTURE.md §2 specifies ZXing over ML Kit — ML Kit is proprietary and depends on Google Play Services, which would foreclose the IzzyOnDroid distribution path §14.7 depends on. `zxing:core` only, never `zxing-android-embedded` — that artifact ships its own `CaptureActivity` and theming, and this app is Compose-only. Licence verified from the artifact's own POM (`<license>Apache License, Version 2.0</license>`, fetched directly from Maven Central), not the project README. Attribution + NOTICE. |
| [AndroidX CameraX](https://developer.android.com/jetpack/androidx/releases/camera) (`androidx.camera:camera-core`/`camera-camera2`/`camera-lifecycle`/`camera-view`) | 1.5.0 | Apache-2.0 | **Used for:** Camera preview and the frame stream feeding ZXing (`QrScanScreen`, Task 20). **Justification (§10.7):** the platform Camera2 API requires hand-rolling capture-session and lifecycle management that CameraX already solves correctly across OEMs; hand-rolling it is the kind of small-problem-sized custom code §10.7 warns against adding a dependency to avoid rather than for. AndroidX, not a Play-Services-backed camera API — no proprietary dependency, same reasoning as the ZXing choice above. Licence verified from `camera-core`'s own POM (`<name>The Apache Software License, Version 2.0</name>`, fetched directly from Google's Maven), not a project README. Attribution only. |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.9.0 | Apache-2.0 | Attribution only. JSON for `vmess://` bodies and raw Xray configs. `:core:parser` is pure JVM, so Android's `org.json` is unavailable there. |
| [kaml](https://github.com/charleskorn/kaml) | 0.83.0 | Apache-2.0 | Attribution only. Clash YAML. Neither snakeyaml-engine nor the KMP port below instantiates arbitrary types by default — chosen over snakeyaml 1.x for that reason, since subscription content is attacker-controllable and snakeyaml's default `Constructor` is the CVE-2022-1471 gadget surface. |
| [snakeyaml-engine-kmp](https://github.com/krzema12/snakeyaml-engine-kmp) (`it.krzeminski:snakeyaml-engine-kmp`) | 3.1.1 | Apache-2.0 | Attribution only. **Transitive, via kaml** — the YAML 1.2 engine that actually parses Clash configs. A Kotlin Multiplatform port maintained by a separate party from upstream snakeyaml, so it is its own supply-chain question, not a detail of kaml's. |
| [Okio](https://github.com/square/okio) (`com.squareup.okio:okio`) | 3.14.0 (resolved; kaml's chain declares 3.10.2) | Apache-2.0 | Attribution only. **Transitive, via kaml → snakeyaml-engine-kmp.** I/O primitives for the YAML reader. |
| [UrlEncoder](https://github.com/ethauvin/urlencoder) (`net.thauvin.erik.urlencoder:urlencoder-lib`) | 1.6.0 | Apache-2.0 | Attribution only. **Transitive, via kaml → snakeyaml-engine-kmp.** Not used directly by this project; recorded because it ships in the APK. |
| [AndroidX Room](https://developer.android.com/jetpack/androidx/releases/room) | 2.8.4 | Apache-2.0 | All persistence — profiles, groups **and settings**. ARCHITECTURE.md §3 mandates it and rules out DataStore: Preferences DataStore is not multi-process safe, and this app runs `:main` and `:bg`. Room additionally offers `enableMultiInstanceInvalidation()`, which is the only mechanism that makes a `:bg` write visible to a `:main` query. Justification per ARCHITECTURE.md §10.7. |
| [Roboto Flex](https://github.com/google/fonts/tree/main/ofl/robotoflex), [Roboto Mono](https://github.com/google/fonts/tree/main/ofl/robotomono) | google/fonts commit `2796410152d4f9524b68ed46e69c1b60f8e0f7c3`, files bundled in `core/ui/src/main/res/font/` | SIL Open Font License 1.1 | All app typography; Mono for machine-generated values (addresses, transports, ports, quota, timestamps). Justification (§10.7): neither is an Android system font, and the design system's own token file (`tokens/fonts.css`) loads them from `fonts.googleapis.com` — the Google Fonts *downloadable*-font provider, which routes through Play Services. Play Services is proprietary and §14.7 rules that distribution path out for IzzyOnDroid, the same reason §2 specifies ZXing over ML Kit. Bundling the `.ttf` files (each a single variable font, referenced via Compose `FontVariation` rather than one static file per weight) is the only route that keeps the APK free of that dependency. The design system itself flags the typeface choice as a substitution, not a brand decision: "the source repo ships no typeface ... Ask the team for real brand type if one exists." |
| [Compose Material Icons Core](https://developer.android.com/jetpack/androidx/releases/compose-material) (`androidx.compose.material:material-icons-core`) | 2026.06.01 (via `compose-bom`, no new version introduced) | Apache-2.0 | `art.yniyniyni.subspace.navigation.SubspaceNavHost`'s three top-level `NavItem`s each need an `ImageVector` (Home, List, Settings), `:feature:home`'s "Add server" chip needs one more (`Icons.Default.Add`), and `:core:ui`'s `GroupCard` (Task 18) needs the expand caret and overflow glyphs (`Icons.Default.KeyboardArrowDown`/`MoreVert`), and `:feature:profiles`' Servers screen needs the search glyph (`Icons.Default.Search`). Justification (§10.7): the `-core` artifact only (not `-extended`, which ships several thousand icons the app does not use) — smallest dependency that unlocks first-party Material iconography instead of hand-building `ImageVector.Builder` paths per icon. |

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
| [kotest-assertions-core](https://github.com/kotest/kotest) | 5.9.1 | Apache-2.0 | Test-only. Assertions in every module's unit tests, and in `:core:xray`'s, `:core:ui`'s and (as of Task 18's fix round 1) `:feature:profiles`'s `androidTest` — wired there explicitly because the convention plugin only adds it to `testImplementation`. Assertions only; the kotest *runner* is deliberately not used, so tests stay plain JUnit 4. |
| [kotlinx.coroutines-test](https://github.com/Kotlin/kotlinx.coroutines) | 1.10.2 | Apache-2.0 | Test-only. Supplies the Main dispatcher `viewModelScope` needs off-device. |
| [androidx.test](https://developer.android.com/jetpack/androidx/releases/test) runner + ext-junit | 1.7.0 / 1.3.0 | Apache-2.0 | Test-only. Instrumented tests, which are the only place libXray and tun2socks can actually run (§11). |
| [Room Testing](https://developer.android.com/jetpack/androidx/releases/room) (`androidx.room:room-testing`) | 2.8.4 | Apache-2.0 | Test-only. `Room.inMemoryDatabaseBuilder`, used by `:core:data`'s instrumented `SubspaceDatabaseTest` so the schema, foreign keys and unique indices are proven against a real SQLite engine rather than mocked. |
| [Compose UI Testing](https://developer.android.com/jetpack/androidx/releases/compose-ui) (`androidx.compose.ui:ui-test-junit4`, `:ui-test-manifest`) | 2026.06.01 (via `compose-bom`, already the project's pinned BOM — no new version introduced) | Apache-2.0 | Test-only. First Compose UI instrumented tests in the repo (`:core:ui`'s `ConnectControlTest`). Not recorded as a plain "first-party AndroidX/Compose" skip the way `compose-ui`/`compose-material3`/etc. are (those are the baseline Android/Compose stack, present since scaffolding, and absent from the Runtime dependencies table above) — this pair is recorded because it unlocks a capability the repo didn't have before (Compose UI instrumented testing) the same way `androidx.test` runner/ext-junit and Room Testing were recorded above it for unlocking instrumented DB testing, per this section's own stated rule that every test dependency is a supply-chain question regardless of source set. `ui-test-manifest` is `debugImplementation`-only (its `ComponentActivity` manifest fragment must never reach a release build). |
| [AndroidX Test Espresso](https://developer.android.com/jetpack/androidx/releases/test) (`androidx.test.espresso:espresso-core`) | 3.7.0, pinned above the 3.5.0 `compose-ui-test-junit4` pulls in transitively | Apache-2.0 | Test-only. `ConnectControlTest` failed on every assertion with `NoSuchMethodException: android.hardware.input.InputManager.getInstance` under 3.5.0 on the API 37 test device — that hidden no-arg method is gone on that OS version. 3.7.0 (2025-07-30) replaced the reflective lookup with `getSystemService` per its own release notes; this pin is the fix, not a feature addition. Same pin applied in `:feature:profiles`'s `androidTest` (Task 18 fix round 1, `ServersDialogsTest` — that module's first Compose instrumented test) for the same reason. |

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
