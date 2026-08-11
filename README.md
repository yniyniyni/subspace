// SPDX-License-Identifier: AGPL-3.0-or-later

# Subspace

> **A native Android VPN client for Xray-core. Bring your own server — no bundles, no middlemen, no telemetry.**

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Compose-Material%203-4285F4?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Android](https://img.shields.io/badge/Android-min%2026%20%E2%80%A2%20target%2036-3DDC84?logo=android)](https://developer.android.com)
[![Xray-core](https://img.shields.io/badge/Xray--core-v26.7.11-FF6B00)](https://github.com/XTLS/Xray-core)

Subspace tunnels all device traffic through a **user-supplied Xray-core server** — VLESS/REALITY, VMess, Trojan, Shadowsocks, SOCKS, Hysteria2. It never ships server lists, never phones home, and never shows an ad.

Built for the [Remnawave](https://github.com/remnawave/panel) / Happ ecosystem: subscriptions are a **remote config channel**, not just a server list.

> **⚠️ Under active development — not stable yet.**
> Subspace is under heavy construction. Features are incomplete, the config format may change between updates, and breakage is expected. Don't rely on it as your daily driver just yet — but feedback and contributions are very welcome.

---

## Why another VPN client?

No open-source Android client currently combines all three of:

1. **Native Android** (not Flutter)
2. **Xray-core** (not Clash/mihomo)
3. **Happ/Remnawave header compatibility** (HWID, directives, response rules)

That gap is why Subspace exists.

---

## Features

| Area | Status | Details |
|------|--------|---------|
| **Protocols** | ✅ VLESS · 🚧 VMess/Trojan/SS/SOCKS/Hysteria2 | VLESS connects today; others parse but need config generation |
| **Import** | ✅ | Manual, clipboard, file, QR (ZXing + CameraX) |
| **Subscriptions** | ✅ | Fetch → split directives → validate → persist. Auto-refresh on interval + on launch (WorkManager) |
| **HWID** | ✅ | Hashed `ANDROID_ID`, on by default. Distinct errors for `max-devices-reached` vs `hwid-not-supported` |
| **Latency & sorting** | ✅ | `tcp` + `proxy-head` modes, per-group sort (as-delivered / ping / alphabetical / last-used) |
| **Raw JSON profiles** | ✅ (typed projection) | Stored byte-for-byte; true passthrough deferred (see Architecture) |
| **Routing rules** | 🚧 M5 | geoip/geosite/domain/IP → direct/proxy/block |
| **Per-app proxy** | 🚧 M5 | Allow-list / deny-list via `VpnService` |
| **Tunnel hardening** | 🚧 M7 | Always-on VPN, boot autostart, `NetworkCallback`, kill switch |
| **Censorship resistance** | 🚧 M8 | Fragmentation, noises, fronting, fallback URL, DoH pre-resolution |
| **UI** | ✅ | Jetpack Compose + Material 3, light/dark, MVI |

> 📖 The full feature map is a Happ parity analysis in [`ARCHITECTURE.md` Appendix A](ARCHITECTURE.md#appendix-a-feature-goals-happ-parity-analysis).

### What Subspace will never do

- Bundle or sell servers
- Add analytics, crash reporting, or any network call you didn't initiate
- Show ads, IAP, or accounts
- Generate encrypted `happ://crypt` links (keys would be public in an open repo)

---

## How it works

### Packet path

```
app socket → TUN fd → hev-socks5-tunnel → 127.0.0.1:<socks port>
           → libXray inbound → routing rules → outbound
           → protected socket → network
                                   ↑
                          VpnService.protect(fd) — skip this and you get
                          an infinite loop with no error and rising CPU
```

### Two processes, on purpose

```
┌──────────────────────────────────────────────────────┐
│  :main  — Compose UI · ViewModels · Repositories · Room │
└──────────────────────┬───────────────────────────────┘
                       │  bound service / IPC
┌──────────────────────▼───────────────────────────────┐
│  :bg    — TunnelService : VpnService                  │
│           builds TUN · holds fd · runs libXray        │
│           runs hev-socks5-tunnel on the TUN fd        │
└──────────────────────┬───────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────┐
│  Native — libXray.aar (Go) · hev-socks5-tunnel (C/JNI) │
└──────────────────────────────────────────────────────┘
```

- The Go runtime's heap is isolated — a native crash kills the tunnel, not the app.
- The two processes **do not share memory**. Singletons exist twice. Shared state goes through Room (with `enableMultiInstanceInvalidation()`) or IPC — never a shared object.
- **No DataStore.** Preferences DataStore is not multi-process safe; settings live in Room.

> The authoritative spec is [`ARCHITECTURE.md`](ARCHITECTURE.md) — read §3 and §5 before touching anything. `ARCHITECTURE.md §10` lists every mistake agents routinely make here.

---

## Tech stack

| Layer | Choice |
|-------|--------|
| Language | Kotlin (JVM 17) |
| UI | Jetpack Compose + Material 3, Navigation Compose (type-safe routes) |
| Architecture | MVI — one `State` per screen, unidirectional flow |
| DI | Hilt |
| Persistence | Room (profiles, groups, subscriptions **and settings**) |
| Async | Coroutines + Flow |
| Proxy core | [`XTLS/libXray`](https://github.com/XTLS/libXray) v26.7.11 (MIT) via prebuilt AAR |
| TUN → SOCKS | [`hev-socks5-tunnel`](https://github.com/heiher/hev-socks5-tunnel) v2.16.0 (C, JNI) |
| QR | ZXing `core` + CameraX — no ML Kit (proprietary → blocks F-Droid/IzzyOnDroid) |
| HTTP | OkHttp 5.2.1 (subscription fetch, HWID headers) |
| Scheduling | WorkManager (per-subscription refresh intervals) |

See [`THIRD_PARTY.md`](THIRD_PARTY.md) for every dependency, version pin, license, and justification.

---

## Project structure

```
:app                  Application, DI wiring, navigation host
:core:model           Pure Kotlin data classes — zero Android imports
:core:data            Room, repositories (the only module that may depend on :core:network)
:core:network         HTTP: subscription fetch, HWID, User-Agent
:core:parser          Share-link & subscription parsing — pure JVM, heavily tested
:core:ui              Design system: theme, tokens, shared Compose components
:core:xray            Xray JSON generation, libXray lifecycle wrapper
:feature:home         Connect button, active profile, traffic state
:feature:profiles     Profile list, editor, subscription management
:feature:routing      Rule editor, per-app selection  (M5)
:feature:settings     Preferences, geo updates, logs
:service              TunnelService, tun2socks JNI bridge, notification  (:bg process)
build-logic/          Convention plugins, dependency rules
third_party/hev-socks5-tunnel  git submodule — built from source via NDK/CMake
```

**Boundary rules** (enforced by the build, not by convention):

- `:core:model` and `:core:parser` — no Android dependencies, JVM-testable.
- `:feature:*` modules never depend on each other.
- `:service` depends on `:core:*`, never on `:feature:*`.
- Only `:core:data` may depend on `:core:network`.

---

## Getting started

### Prerequisites

- **Android Studio** Ladybug or newer (AGP 9.3.1)
- **JDK 17**
- **Android SDK** — API 37 (compile), API 36 (target), min 26
- **NDK + CMake** — only if you build `hev-socks5-tunnel` from source (the default)

### Clone & build

```bash
git clone https://github.com/yniyniyni/subspace.git
cd subspace

# submodules (hev-socks5-tunnel C sources)
git submodule update --init --recursive

# fetch the pinned libXray AAR (gitignored — reproducibly downloaded + SHA-256 verified)
./scripts/fetch-native.sh

# build
./gradlew assembleDebug

# install on a connected device / emulator
./gradlew installDebug
```

The APK installs as `art.yniyniyni.subspace` (`0.1.0-alpha01`, `versionCode 1`).

### Verification

```bash
./gradlew check          # ktlint + detekt + unit tests + SPDX + forbidden-pattern checks
./gradlew checkAll       # same, across every module
./scripts/check-spdx.sh      # every source file carries AGPL SPDX header
./scripts/check-forbidden.sh # no !!, no GlobalScope, no runBlocking outside tests
```

> ⚠️ **A green build does not mean a working tunnel.** Most bugs here produce "Connected, but no packets flow" — no compile error, no stack trace. The only real test is traffic on a physical device. See the manual smoke checklist in [`ARCHITECTURE.md §11`](ARCHITECTURE.md#11-testing-strategy).

Manual checklist (run on hardware before any release):

- [ ] Connect → page loads, exit IP changed
- [ ] DNS leak test passes (Wi-Fi **and** mobile data)
- [ ] Toggle Wi-Fi ↔ cellular while connected — traffic resumes
- [ ] Per-app deny-list: excluded app bypasses tunnel
- [ ] Kill app from recents while connected — state stays correct
- [ ] Reboot with always-on — tunnel comes back
- [ ] Screen off 30 min — tunnel still alive
- [ ] Disconnect — no lingering TUN interface

---

## Subscriptions: the key idea

A subscription response carries **directives, not just servers**. The provider can change app behavior remotely — routing rules, intervals, display names, traffic quotas — over two interchangeable transports:

```http
# As HTTP headers
profile-title: My VPN
subscription-userinfo: upload=0; download=2153701362; total=0; expire=1790951622
routing: happ://routing/onadd/eyJOYW1lIjoi...

# Or as #-prefixed body lines
#profile-title: My VPN
vless://70cc48c5-b2f4...
```

Directives are **scoped per subscription**, allow-listed, schema-validated, and treated as hostile input (a URL pasted from Telegram can change DNS and routing). The fetcher is a pipeline: `fetch → split directives from configs → validate → apply scoped mutations → persist → return servers`.

This is the one thing that is expensive to retrofit — it shapes the settings, persistence, and IPC layers from day one. Details in [`ARCHITECTURE.md §A.1`](ARCHITECTURE.md#a1-the-central-idea-the-subscription-is-a-remote-config-channel).

---

## Planned features

Core capabilities on the way:

- **Routing & per-app proxy** — geoip/geosite/domain/IP rules (direct / proxy / block), allow-list & deny-list per app via `VpnService`
- **Routing deeplinks** — `happ://routing/add|onadd|off` shareable routing profiles with atomic geo-file swaps
- **Platform hardening** — always-on VPN, boot autostart, `NetworkCallback` for Wi-Fi ↔ cellular, kill switch, `onRevoke` handling, traffic stats
- **Censorship resistance** — fragmentation, noises, subscription fronting, fallback URL, DoH pre-resolution
- **More protocols** — beyond Xray-core: **AmneziaWG** and **OlcRtc** support is planned alongside the existing VLESS/VMess/Trojan/Shadowsocks/SOCKS/Hysteria2 lineup
- **Polish & release** — RU + EN localization, subscription metadata surface (`profile-title`, `subscription-userinfo`, `announce`…), signing, [IzzyOnDroid](https://apt.izzysoft.de) distribution

---

## Privacy & security

- **No analytics, no crash reporting, no background network calls** you didn't initiate.
- **No config logging** — server addresses, UUIDs, REALITY keys, and subscription URLs are redacted everywhere, including the in-app log viewer (`ARCHITECTURE.md §5.6`).
- **Directives are hostile input** — every key is allow-listed, every value schema-validated, URLs validated before any fetch. Dangerous directives (URL replacement, per-app list mutation) require explicit user confirmation.
- **Distribution:** GitHub Releases + [IzzyOnDroid](https://apt.izzysoft.de). No proprietary dependencies (hence ZXing over ML Kit).

---

## Contributing

Agentic PRs are welcome — if you actually know what you're doing.

Most bugs in a VPN client don't produce a stack trace. They produce "Connected, but no packets flow." Read [`ARCHITECTURE.md`](ARCHITECTURE.md) (§3, §5, §10) before writing any code.

House rules: every file starts with `// SPDX-License-Identifier: AGPL-3.0-or-later`, package root is `art.yniyniyni.subspace`, no `androidx.datastore` (settings live in Room), check [`THIRD_PARTY.md`](THIRD_PARTY.md) before copying from any upstream project — license decides whether you may adapt or only learn.

---

## License

**AGPL-3.0-or-later** — see [`LICENSE`](LICENSE). Every source file carries an SPDX header.

Upstream adaptations keep their original license and are attributed per [`THIRD_PARTY.md`](THIRD_PARTY.md).

---

## Acknowledgments

- [XTLS/Xray-core](https://github.com/XTLS/Xray-core) and [XTLS/libXray](https://github.com/XTLS/libXray) — the proxy core
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — TUN→SOCKS bridge
- [2dust/v2rayNG](https://github.com/2dust/v2rayNG) & [SaeedDev94/Xray](https://github.com/SaeedDev94/Xray) — Android VPN plumbing reference
- [FlClashX](https://github.com/pluralplay/FlClashX) / [Prizrak-Box](https://github.com/legiz-ru/Prizrak-Box) — Remnawave header reference
- [remnawave/panel](https://github.com/remnawave/panel) — the open panel that defines the HWID contract
- [Happ](https://happ.su/main/dev-docs) — whose protocol design this project builds on and deliberately diverges from where noted
