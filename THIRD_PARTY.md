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
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | v2.16.0 | MIT | Attribution only. |
| [ZXing](https://github.com/zxing/zxing) | see version catalog | Apache-2.0 | Attribution + NOTICE. Replaces ML Kit, which is proprietary and disqualifies the app from F-Droid and IzzyOnDroid. |

Bundled `geoip.dat` / `geosite.dat` originate from
[v2fly/domain-list-community](https://github.com/v2fly/domain-list-community)
(MIT) and [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat)
(GPL-3.0 for the build tooling; the emitted `.dat` files are data). Record the
exact source and release tag in the assets README when they are first added.

---

## Build tooling

| Component | Version pin | License | Obligation |
|---|---|---|---|
| Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) | Gradle 9.6.0 | Apache-2.0 | Attribution + NOTICE. Vendored so a clone builds without a pre-installed Gradle. Not modified. |
| [detekt-formatting](https://github.com/detekt/detekt) | see version catalog (matches `detekt`) | Apache-2.0 | Attribution only. Wraps ktlint's rules as detekt rules; added because ktlint-gradle's own per-source-set check tasks never register on Android modules under AGP 9 (see `build.gradle.kts` and `subspace.android.library.gradle.kts` for why) — this is the actual enforcement of formatting on `:app`, `:service`, `:core:data`, `:core:xray`, and every `:feature:*` module. Justification per ARCHITECTURE.md §10.7. |

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
