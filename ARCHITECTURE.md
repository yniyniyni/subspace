# ARCHITECTURE.md

Architecture guide for coding agents working on this repository.

**Read this file completely before writing any code.** This project has a
narrow correctness surface: most bugs here do not produce compile errors or
stack traces — they produce "connected, but no packets flow", DNS leaks, or a
battery drain that only shows up after six hours. Standard agent heuristics
(make it compile, make the test green) do not catch those.

---

## 1. What this is

A native Android VPN client that tunnels device traffic through a
user-supplied Xray-core server (VLESS/REALITY, VMess, Trojan, Shadowsocks,
SOCKS, Hysteria2).

**Bring-your-own-server.** This app never ships, sells, or bundles servers.
It is a configuration and connection tool.

### Target feature set

Short version: subscription management, rule-based routing, per-app proxy,
latency testing, traffic counters, always-on VPN, Material 3 UI.

**The full goal list is Appendix A.** It is a feature-by-feature analysis of
Happ, which is the reference product for this project. Read it before
planning any milestone — several of its features imply architecture
decisions that are expensive to retrofit, in particular the
subscription-as-remote-config channel (§A.1).

### Non-goals

Do not add these without explicit instruction:

- iOS, macOS, Windows, Linux, or any Kotlin Multiplatform target
- Any bundled or free server list
- Analytics, crash reporting to third parties, or any network call not
  initiated by the user
- Ads, IAP, accounts

---

## 2. Stack

| Layer | Choice | Notes |
|---|---|---|
| Language | Kotlin | JVM target 17 |
| UI | Jetpack Compose + Material 3 | No XML layouts. No Fragments. |
| Architecture | MVI, unidirectional data flow | One `State` data class per screen |
| DI | Hilt | |
| Persistence | Room (profiles, subscriptions) + DataStore Preferences (settings) | |
| Async | Coroutines + Flow | No RxJava, no callbacks in new code |
| Proxy core | `XTLS/libXray` as an AAR, pinned v26.7.11 | MIT. Prebuilt AAR from the release; see §14. |
| TUN → SOCKS | `hev-socks5-tunnel` via JNI | C library |
| QR scan | ZXing + CameraX | **Not ML Kit.** ML Kit is proprietary and disqualifies the app from F-Droid and IzzyOnDroid. |
| Nav | Navigation Compose, type-safe routes | |

---

## 3. The three-layer mental model

Get this right and most of the codebase follows from it.

```
┌─────────────────────────────────────────────────────────────┐
│  App process (:main)                                        │
│  Compose UI ── ViewModels ── Repositories ── Room/DataStore  │
└──────────────────────────┬──────────────────────────────────┘
                           │  AIDL / Messenger / bound service
┌──────────────────────────▼──────────────────────────────────┐
│  VPN process (:bg)                                          │
│  TunnelService : VpnService                                 │
│    ├── builds the TUN interface, holds the fd               │
│    ├── starts libXray with a generated JSON config          │
│    └── starts hev-socks5-tunnel on the TUN fd               │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│  Native                                                     │
│  libXray.aar (Go)   hev-socks5-tunnel (C, JNI)              │
└─────────────────────────────────────────────────────────────┘
```

### Packet path

```
app socket → TUN fd → hev-socks5-tunnel → 127.0.0.1:<socks port>
           → libXray inbound → routing rules → outbound
           → protected socket → network
```

The last hop is where the classic fatal bug lives. See §5.1.

### Why two processes

`TunnelService` runs in `android:process=":bg"`. This is deliberate:

- The Go runtime inside libXray holds significant heap. Isolating it keeps
  the UI process light and lets the system kill one without the other.
- A native crash in tun2socks kills the tunnel, not the app.
- It forces a clean IPC boundary instead of shared mutable singletons.

**Consequence agents routinely get wrong:** the two processes do **not**
share memory. Hilt singletons, `object` declarations, static fields, and
in-memory caches exist twice. Any state that must be consistent goes through
Room, DataStore, or IPC — never through a shared object reference.

**And the persistence layer needs explicit multi-process configuration:**

- **Room** requires `enableMultiInstanceInvalidation()` on the builder.
  Without it, a write in `:bg` does not invalidate the query cache in
  `:main`, so the UI keeps serving stale rows with no error anywhere.
- **Preferences DataStore is not multi-process safe.** The standard artifact
  explicitly assumes a single process; concurrent access from two can
  corrupt the file.

  **DECIDED: there is no DataStore in this project. Settings live in Room.**
  Of the three options — multi-process DataStore, Room-for-everything, or
  single-process DataStore plus IPC — only the second removes the hazard
  instead of managing it. One storage engine, one invalidation mechanism,
  one place to reason about concurrency. Settings are a `settings` table
  read through the same repository layer as everything else.

  Do not add `androidx.datastore` to the version catalog. If you think you
  need it, you want a Room table.

This is the concrete tax for the two-process split. It is worth paying for
the crash isolation, but it is not free, and it will not surface as a
compile error.

---

## 4. Module layout

```
:app                    Application, DI wiring, navigation host
:core:model             Pure Kotlin data classes. No Android imports.
:core:data              Room, DataStore, repositories
:core:parser            Subscription and share-link parsing. Pure, heavily tested.
:core:xray              Config JSON generation, libXray lifecycle wrapper
:feature:home           Connect button, active profile, traffic counters
:feature:profiles       Profile list, editor, subscription management
:feature:routing        Rule editor, per-app selection
:feature:settings       Preferences, geo file updates, logs
:service                TunnelService, tun2socks JNI bridge, notification
```

Rules:

- `:core:model` and `:core:parser` must have zero Android dependencies. They
  are unit-testable on the JVM and that is the point.
- `:feature:*` modules never depend on each other.
- `:service` depends on `:core:*` but never on `:feature:*`.

---

## 5. Critical invariants

Violating any of these produces a broken tunnel that still compiles and
still says "Connected".

### 5.1 Protect the outbound socket

Every socket libXray opens to the remote server **must** be passed to
`VpnService.protect(fd)`. Without it, the outbound packet is routed back
into the TUN interface and you get an infinite loop: no traffic, no error,
rising CPU.

libXray exposes a protector callback. Wire it to the live `VpnService`
instance. When the service is recreated, the protector must be re-wired —
a stale reference silently stops protecting.

**If you are debugging "connects but nothing loads", check this first.**

### 5.2 Route DNS through the tunnel

DNS must not escape. Configure it in both places:

- `VpnService.Builder.addDnsServer(...)` for the TUN interface
- A `dns` block in the Xray JSON config

If only one is set you get a partial leak that works fine on Wi-Fi and
breaks on mobile, or vice versa. Verify with a leak-test site, not by
reasoning about the config.

### 5.3 Never block the main thread

Config generation, geo file parsing, subscription fetch, and libXray
start/stop are all slow. All of them go on `Dispatchers.IO`. The connect
button must remain responsive during the whole start sequence.

### 5.4 Handle `onRevoke()`

The system calls `VpnService.onRevoke()` when another VPN app takes over or
the user revokes permission. Tear down cleanly: stop libXray, stop
tun2socks, close the fd, update persisted state, cancel the notification.
Leaking the fd here wedges the VPN subsystem until reboot.

### 5.5 One source of truth for connection state

Connection state lives in the service and is published to the UI over IPC.
The UI never infers it from a local boolean. After process death the UI must
rebind and re-read actual state — an app that shows "Disconnected" while the
tunnel is up is worse than one that crashes.

### 5.6 Do not log config contents

Server addresses, UUIDs, REALITY keys, and subscription URLs are secrets.
Redact them in every log path, including crash output. The in-app log viewer
redacts too.

---

## 6. Xray config generation

The user's stored profile is **not** an Xray config. `:core:xray` generates
the JSON at connect time.

Shape:

```
inbounds:  socks (127.0.0.1, loopback only) [+ optional http]
outbounds: [proxy (from profile), direct (freedom), block (blackhole)]
routing:   rules referencing geoip.dat / geosite.dat, then user rules
dns:       servers + per-domain overrides
stats/api: enabled when traffic counters are on
```

Rules:

- Generate deterministically. Same profile + same settings ⇒ byte-identical
  JSON. This makes diffing and testing possible.
- Bind inbounds to `127.0.0.1` only. Never `0.0.0.0` — that turns the phone
  into an open proxy on the local network.
- Ship `geoip.dat` and `geosite.dat` in assets. Copy to internal storage on
  first run, and support replacing them from a URL.
- Validate before starting. A malformed config makes libXray fail in a way
  that is hard to attribute; catch it early and surface a real error.

### Protocol quirks

**Hysteria2 is native to Xray-core** as of v26.3.27 — no second core needed.
But its config does not follow the shape of the other protocols, and this is
a place agents reliably get it wrong:

- The config is **split across two blocks**: protocol-level settings
  (`version`, address/port, users) and transport-level
  `streamSettings.hysteriaSettings`. Every other protocol in Xray keeps its
  settings in one place. Do not "normalize" this.
- `version` must be `2`. Hysteria v1 is fully removed from Xray-core and any
  other value is a hard startup failure, not a fallback.
- `congestion`, `brutalUp`/`brutalDown` (formerly `up`/`down`), and `udpHop`
  have **moved into Finalmask's `quicParams`**. The legacy location still
  parses but emits a deprecation warning and is scheduled for removal. Target
  the new location; treat the old one as import-compatibility only.
- `udpIdleTimeout` outside the 2–600 range is a startup error.
- Port hopping: the inbound should listen on a single port with other ports
  forwarded via iptables. Client-side hopping is configured in `quicParams`.
- Note that Xray's Hysteria2 layout differs from both sing-box (single flat
  block, bandwidth as plain int Mbps) and mihomo (single block, `ports` range
  + `hop-interval`). The subscription parser must translate, not copy.

Hysteria2 support in Xray is **newer and less battle-tested** than VLESS or
Trojan. There are open upstream issues around inbound responsiveness and
Salamander obfuscation. Treat Hysteria2 failures as possibly-upstream before
assuming a bug in this codebase, and check the Xray-core issue tracker.

---

## 7. Subscription and share-link parsing

`:core:parser` handles:

- Base64-encoded newline-separated lists
- `vless://`, `vmess://` (base64 JSON body), `trojan://`, `ss://`, `socks://`
- Clash / Clash.Meta YAML
- Raw Xray JSON

Requirements:

- **Never throw on malformed input.** Return a result type. One bad line in
  a 200-line subscription must not lose the other 199.
- Real-world subscriptions violate their own specs constantly: missing
  padding, URL-safe vs standard base64, percent-encoded fragments, duplicate
  query keys, non-UTF-8 bytes. Handle all of it.
- Every new format quirk gets a regression test with the actual offending
  string as a fixture.
- This module is the one place where near-100% unit coverage is achievable
  and expected.

---

## 8. Per-app proxy

Two modes, mutually exclusive:

- **Deny-list** — `addDisallowedApplication()` for selected packages
- **Allow-list** — `addAllowedApplication()` for selected packages

Notes:

- Enumerating installed apps needs `QUERY_ALL_PACKAGES`. That permission
  requires a Play Store declaration; VPN clients are an accepted use case,
  but expect review friction. F-Droid does not care.
- Both calls throw `NameNotFoundException` if a package was uninstalled
  since selection. Catch per-package, skip, continue. Do not let one stale
  entry abort the whole tunnel setup.
- The app's own package must never be routed through itself.

---

## 9. Service lifecycle and Android platform tax

This is the least portable, most version-dependent part of the codebase.

- **Foreground service** with an ongoing notification is mandatory for the
  whole duration of the tunnel.
- **`foregroundServiceType`** — VERIFY against current docs for your
  `targetSdk`. Android 14 tightened FGS type requirements and the correct
  declaration for VPN apps has changed across versions. Do not guess; check
  the current developer documentation and the actual behavior on device.
- **Battery optimization** — prompt the user to exempt the app, or the
  tunnel dies in Doze. Prompt once, respect refusal.
- **Boot start** — `RECEIVE_BOOT_COMPLETED` plus a receiver, gated behind a
  user setting, and only meaningful together with always-on VPN.
- **Network changes** — register a `NetworkCallback`. On Wi-Fi ↔ cellular
  transitions, the underlying network changes and the tunnel needs
  re-establishing or at minimum a re-protect. Test this by physically
  toggling Wi-Fi, repeatedly, not by unit test.

---

## 10. What agents get wrong here

Read this section twice.

1. **Assuming a green build means a working tunnel.** It does not. Nothing
   in this project is validated by compilation. The only real test is
   traffic flowing on a physical device.

2. **"Simplifying" the JNI bridge or the protector callback.** These look
   like boilerplate. They are load-bearing. Do not refactor them for
   elegance. If you must change them, change one thing and verify on device.

3. **Sharing state across the process boundary.** See §3. Every few weeks
   someone adds a Hilt singleton and expects the service to see it.

4. **Catching exceptions broadly around the start sequence.** A swallowed
   failure here produces the worst possible state: UI says connected,
   nothing works, no log line. Fail loudly and specifically.

5. **Inventing config fields.** Xray's JSON schema is large and agents
   confidently hallucinate plausible keys. If you are not certain a field
   exists, check the Xray-core documentation. An unknown key can be silently
   ignored or can reject the whole config.

6. **Hardcoding ports.** Allocate the local SOCKS port dynamically or make
   it configurable. A fixed port collides with other proxy apps.

7. **Adding a dependency to solve a small problem.** Every dependency in a
   VPN client is attack surface and a supply-chain question. Prefer stdlib.
   Any new dependency needs justification in the PR description.

8. **Touching licensing.** Code adapted from GPL-licensed clients keeps its
   license. Attribute upstream in a comment when adapting non-trivial logic.

---

## 11. Testing strategy

| What | How |
|---|---|
| Parsers, config generation, routing rule mapping | JVM unit tests. Expected to be thorough. |
| Repositories, Room | Instrumented tests |
| Compose screens | Compose UI tests for state rendering |
| Tunnel, DNS, per-app, network transitions | **Manual, on device, every time** |

Manual smoke checklist before any release:

- [ ] Connect, load a page, verify exit IP changed
- [ ] DNS leak test passes
- [ ] Toggle Wi-Fi ↔ cellular while connected, traffic resumes
- [ ] Per-app deny-list: excluded app bypasses tunnel
- [ ] Kill the app from recents while connected, state stays correct
- [ ] Reboot with always-on enabled, tunnel comes back
- [ ] Screen off 30 min, tunnel still alive
- [ ] Disconnect, verify no lingering TUN interface

---

## 12. Conventions

- Package root: `art.yniyniyni.subspace`
- Application ID: `art.yniyniyni.subspace`
- License: AGPL-3.0-or-later. Every source file carries an SPDX header:
  `// SPDX-License-Identifier: AGPL-3.0-or-later`
- `ktlint` + `detekt`, enforced in CI
- Public APIs in `:core:*` get KDoc; feature internals do not need it
- Commits: Conventional Commits (`feat:`, `fix:`, `refactor:`)
- Strings live in `strings.xml` from the first commit; no hardcoded UI text
- No `!!`. No `GlobalScope`. No `runBlocking` outside tests.

---

## 13. Glossary

| Term | Meaning |
|---|---|
| TUN | Virtual network interface; the OS hands us raw IP packets on an fd |
| tun2socks | Translates raw IP packets into SOCKS connections |
| REALITY | Xray TLS-camouflage transport; masquerades as a real site |
| XTLS Vision | Xray flow-control mode reducing TLS-in-TLS overhead |
| geosite/geoip | Compiled domain and IP databases used by routing rules |
| Subscription | Remote URL returning a list of server configs |
| Protector | Callback marking a socket to bypass the VPN route |

---

## 14. Decisions (formerly open questions)

Resolved 2026-07-25. Each entry records the answer, the reason, and where the
answer came from, so that a later agent can tell a decision from a guess. Full
working notes: `docs/agent/research/2026-07-25-upstream-survey.md`.

### 14.1 `foregroundServiceType` — RESOLVED

**`systemExempted`**, with `android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED`
in the manifest and `ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED`
passed to `startForeground()`.

VPN apps are an explicitly enumerated qualifying criterion for this type. There
is no dedicated `vpn` type, and `specialUse` is **not** the right answer — a
non-qualifying type throws `ForegroundServiceTypeNotAllowedException` at
runtime, not at build time, which is exactly the failure mode §10.1 warns
about. Source: Android developer docs, "Foreground service types" and
"Foreground service types are required".

If the app is ever published on Google Play, this type must additionally be
declared and justified in Play Console under Policy → App content.

**Android lint will flag this and it is wrong.** `ForegroundServicePermission`
asserts that `systemExempted` also requires `SCHEDULE_EXACT_ALARM` or
`USE_EXACT_ALARM`. That encodes only the *alarm* branch of the type's qualifying
criteria; the VPN branch is a runtime condition lint cannot see. Suppress it with
`tools:ignore="ForegroundServicePermission"` on the `<service>` element, as Brave
and Orbot do. Do **not** silence it by requesting an exact-alarm permission the
app does not use.

### 14.2 libXray vs AndroidLibXrayLite — RESOLVED

**`XTLS/libXray`, pinned to v26.7.11.** Two reasons, in order:

1. **Licensing.** libXray is MIT. AndroidLibXrayLite is LGPL-3.0. For an
   AGPL-3.0 project the MIT dependency is unambiguously cleaner.
2. **It ships the §5.1 socket-protect hook as first-class API**, and
   `getFreePorts`, which satisfies §10.6 without hand-rolling port allocation.

**Correction, 2026-07-26.** An earlier revision of this section also cited
`SetDNS`/`ResetDNS` as a reason. **Those do not exist in v26.7.11** — there is
not one DNS reference in the shipped Go source. The decision stands on its other
two grounds, but do not go looking for that API. §5.2 is satisfied by
`VpnService.Builder.addDnsServer()` plus the `dns` block in the generated config,
and there is no third lever.

The real API surface is a single JSON entry point, not the object-oriented form
that earlier drafts of this document and the M1 plan assumed:

```java
String  LibXray.invoke(String requestJson)          // every operation
void    LibXray.registerDialerController(DialerController)   // §5.1
void    LibXray.registerListenerController(DialerController)
void    LibXray.registerProcessFinder(ProcessFinder)         // per-app, M5

interface DialerController { boolean protectFd(long fd); }
```

Methods dispatched through `invoke`: `getFreePorts`,
`convertShareLinksToXrayJson`, `convertXrayJsonToShareLinks`, `countGeoData`,
`ping`, `testXray`, `runXray`, `runXrayFromJson`, `stopXray`, `xrayVersion`,
`getXrayState`.

Note `testXray` — use it to satisfy the "validate before starting" rule in §6.
It takes a **file path**, not a config string, so the generated config must be
written to disk before it can be validated.

**Full verbatim signatures, and every place reality differed from the plan, are
in `docs/agent/research/libxray-api.md`. Read it before writing `:core:xray`.**

**Do not mix the two libraries.** They export different symbols and both
initialise a Go runtime.

### 14.3 Xray-core version floor — RESOLVED

**Pin v26.7.11**, which is the core version libXray v26.7.11 is built against.

Caveat worth knowing: upstream Xray-core tags v26.7.11 as a *prerelease*; the
newest release marked stable is v26.3.27. We follow libXray's pin rather than
upstream's stable marker, because mismatching the wrapper against the core is a
worse failure than tracking a prerelease. Revisit whenever libXray bumps.

Hysteria2 inbound landed in v26.3.27, so v26.7.11 clears the §6 floor.

### 14.4 Traffic stats source — RESOLVED

**Xray's stats API**, not per-UID `TrafficStats`.

Stats are collected per inbound/outbound tag, which means the numbers line up
with the routing rules the user configured — proxied traffic is
distinguishable from direct traffic. `TrafficStats` counts bytes per UID at
the OS level and cannot see through the tunnel, so it can report a total but
never a breakdown.

Cost of this choice: `stats` and `api` blocks must be present in the generated
config whenever counters are enabled, which makes the config non-identical
between counters-on and counters-off. That is fine — §6 requires determinism
for *the same settings*, and the counter toggle is a setting.

### 14.5 tun2socks implementation — RESOLVED for now

**`hev-socks5-tunnel` v2.16.0**, built from source via CMake/NDK.

Xray's own TUN (`xray-tun-enable`, §A.3.4) stays on the roadmap as an
additional option in a later milestone, not as a replacement. Building the
external tunnel first keeps the packet path in §3 explicit and debuggable.

v2.16.0 ships prebuilt Android binaries for all four ABIs, which is a useful
fallback, but the source build is preferred so the tunnel is reproducible.

### 14.6 Finalmask exposure — DEFERRED, not open

Leave Finalmask (`header-custom`, `Sudoku`, `fragment`, `noise`) to raw-JSON
profiles for now. Revisit in the censorship-resistance milestone, where it is
evaluated alongside the §A.3.3 fragmentation and noise directives rather than
in isolation — they overlap, and picking a UI for both at once avoids shipping
two competing controls for the same job.

### 14.7 Distribution target — RESOLVED

**GitHub Releases + IzzyOnDroid.** Google Play remains possible later and
nothing in the design should foreclose it.

F-Droid's *main* repository builds every artifact from source on its own
infrastructure, which would mean building the libXray AAR and its Go toolchain
there. That is a large, separate piece of work. IzzyOnDroid accepts APKs from
GitHub releases and is where the closest comparable project
(`SaeedDev94/Xray`) ships. Treat F-Droid main as a stretch goal that requires
the from-source libXray build path first.

The practical consequence today: **no proprietary dependencies**, which is why
§2 specifies ZXing rather than ML Kit.

---

# Appendix A: Feature goals (Happ parity analysis)

Happ is the reference product. Its developer documentation is public at
`happ.su/main/dev-docs` and is the source for this appendix. Read the
original docs before implementing any item here — this is a summary, not a
spec.

Goals are tiered. Tier 1 is the MVP. Tier 2 is what actually makes Happ
different from every other Xray client. Tier 3 is deliberately contested —
read §A.5 before building any of it.

---

## A.1 The central idea: the subscription is a remote config channel

This is the single most important architectural insight to take from Happ,
and the one thing that must be designed in from the start rather than
retrofitted.

A Happ subscription response carries **directives, not just server lists**.
The provider can change app behavior remotely. Directives arrive over two
interchangeable transports:

**As HTTP response headers:**

```
HTTP/2 200
content-type: application/json
profile-title: Name VPN
profile-update-interval: 1
subscription-userinfo: upload=0; download=2153701362; total=0; expire=1790951622
routing: happ://routing/onadd/eyJOYW1lIjoi...
```

**Or as `#`-prefixed lines in the subscription body:**

```
#profile-title: Name VPN
#profile-update-interval: 1
#subscription-userinfo: upload=0; download=2153701362; total=0; expire=1790951622
happ://routing/onadd/eyJOYW1lIjoi...
vless://70cc48c5-b2f4...
vmess://zkIAU1JitkI...
```

Same key set, two transports. Both must be supported. Boolean directives use
`true` or `1` to enable; **any other non-empty value disables** (`0`,
`false`, anything).

### Architectural consequences

- The subscription fetcher is **not** a parser that returns a server list.
  It is a pipeline: fetch → split directives from configs → validate
  directives → apply scoped mutations → persist → return servers.
- Directives are **scoped to the subscription that delivered them**, not
  global. A directive from subscription A must not silently mutate
  subscription B's behavior. Happ made this mistake early and later moved to
  per-subscription isolated rule sets with independent lifecycles; start
  there.
- Some directives change settings the user can also change in the UI. You
  need a precedence model (provider vs user) and it must be visible to the
  user. Decide this before writing the settings layer.
- Deletion must cascade: deleting a subscription deletes its routing
  profiles and their cached geo files.

### Security: treat directives as hostile input

A subscription URL is often pasted from a Telegram channel. The directive
channel lets whoever controls that URL change the per-app proxy list, the
User-Agent, DNS, routing rules, and the subscription URL itself.

Mandatory rules:

- **Allow-list the directive keys.** Unknown keys are ignored and logged,
  never passed through to any config.
- **Validate every value against a schema** before it reaches storage. Range
  checks on ints, URL validation on URLs, length caps on strings.
- **Never let a directive trigger a network call to an arbitrary host**
  without validation. `new-url`, `fallback-url`, `Geoipurl` and `Geositeurl`
  are all attacker-controlled URLs in the threat model.
- Directives that are dangerous by nature (URL replacement, per-app list
  mutation) get **explicit user confirmation** in this project, even though
  Happ applies them silently. This is a deliberate divergence — see §A.5.

---

## A.2 Tier 1 — table stakes (MVP)

- [ ] Protocols: VLESS (REALITY, XTLS Vision), VMess, Trojan, Shadowsocks,
      SOCKS5, Hysteria2 — all native to Xray-core now (see §6)
- [ ] Import: manual entry, clipboard, QR camera scan, file, URL
- [ ] Subscription import with auto-update on an interval, plus update on
      app launch
- [ ] Raw JSON config profiles (passthrough mode — the config runs as
      written, app-level routing rules are **not** applied to it)
- [ ] Multi-subscription, multi-profile management, grouping, collapse/expand
- [ ] Latency testing with selectable mode: `proxy` (GET), `proxy-head`,
      `tcp`, and a configurable check URL. **`icmp` is not implementable on
      unrooted Android** — raw sockets require root. Either omit the mode or
      shell out to `/system/bin/ping` and parse it, which is fragile across
      OEMs. Recommendation: ship `tcp` and `proxy`, drop `icmp`.
- [ ] Server sorting: as-delivered, by ping, alphabetical
- [ ] Rule-based routing: geoip/geosite, domain, IP; direct/proxy/block sets
- [ ] Per-app proxy: off / include-list / bypass-list
- [ ] Traffic counters, live log viewer
- [ ] Always-on VPN, boot autostart, kill switch
- [ ] Material 3, light/dark, RU + EN localization

## A.3 Tier 2 — the actual differentiators

### A.3.1 Routing profiles distributed as deeplinks

This is what the user asked about specifically and it is the strongest idea
in Happ. Routing configuration is a **shareable artifact**, not something
each user hand-builds. Community-maintained rule sets (RU/BY whitelists,
ad blocking, service-specific routing) are distributed as a single link.

Link forms:

```
happ://routing/add/{base64}     add profile; first one activates after geo files download
happ://routing/onadd/{base64}   add and activate immediately, overriding any active profile
happ://routing/off              disable routing globally
```

`{base64}` is a base64-encoded JSON profile. Delivery: clipboard, deeplink,
QR, HTTP `routing:` header, or bare in the subscription body.

Profile JSON schema (from the official docs, plus fields observed in
community profiles):

```json
{
  "Name": "China",
  "GlobalProxy": "true",
  "RemoteDNSType": "DoH",
  "RemoteDNSDomain": "https://cloudflare-dns.com/dns-query",
  "RemoteDNSIP": "1.1.1.1",
  "DomesticDNSType": "DoU",
  "DomesticDNSDomain": "",
  "DomesticDNSIP": "8.8.8.8",
  "Geoipurl":   "https://.../geoip.dat",
  "Geositeurl": "https://.../geosite.dat",
  "LastUpdated": "",
  "DnsHosts": { "cloudflare-dns.com": "1.1.1.1" },
  "DirectSites": ["geosite:cn"],
  "DirectIp":    ["geoip:cn", "10.0.0.0/8", "192.168.0.0/16"],
  "ProxySites":  ["geosite:cn"],
  "ProxyIp":     ["geoip:amazon"],
  "BlockSites":  ["geosite:ads"],
  "BlockIp":     ["geoip:ads"],
  "DomainStrategy": "IPIfNonMatch",
  "FakeDNS": "false"
}
```

Community profiles additionally use `RouteOrder` (e.g.
`"block-proxy-direct"`) and `UseChunkFiles`. VERIFY these against current
docs — they are not in the published schema example.

**Lifecycle rules worth copying verbatim** — these are well designed:

- Importing a profile whose name already exists is an **update**, not a
  duplicate.
- On update, geo files download **in the background while the old rules stay
  live**. The core keeps running on the old ruleset. Only when all files
  land do you atomically swap rules and files together. No window where
  routing is half-applied.
- Geo file downloads have a hard timeout (Happ uses 3 minutes). On failure,
  the profile is flagged in the UI with a persistent error marker that
  clears when the download succeeds or the profile is deleted.
- Geo files refresh at most once a week regardless of how often the profile
  itself updates.
- `LastUpdated` is a unix timestamp; only accept an update if it is newer
  than the stored value.

### A.3.2 Subscription metadata surface

- [ ] `profile-title` — display name, plain or base64, max 25 chars
- [ ] `subscription-userinfo` — `upload`, `download`, `total`, `expire` in
      one semicolon-separated header. Drives the traffic/expiry status bar.
- [ ] `announce` / `sub-info-*` — provider announcements with optional
      button and link, colors, max 200 chars
- [ ] `sub-expire` — automatic "expires in N days" notice, shown from 3 days
      out; expiry message takes priority over the info block
- [ ] `support-url`, `profile-web-page-url` — support and account buttons
- [ ] `serverDescription` — per-server caption, base64, appended after
      `title` with a `?` separator in the share link, max 30 chars
- [ ] Flag emoji rendering from server names

### A.3.3 Censorship-resistance features

These are the ones that matter most in practice and are worth prioritizing
above cosmetic parity:

- [ ] **Fragmentation**: `fragmentation-packets` (e.g. `tlshello`),
      `-length`, `-interval`, `-maxsplit`
- [ ] **Noises**: `noises-packet-type` (array/str/hex/base64), `-packet`,
      `-delay`, `-rand`, `-rand-range`
- [ ] **Subscription fronting**: connect to `visa.com` while sending
      `Host: mydomain.com`, via URL params `resolve-address` and `host`
- [ ] **Fallback URL**: switch to a backup subscription URL if the primary
      returns 300–599 or does not respond within the timeout
- [ ] **`new-url` / `new-domain`**: provider-initiated migration when the
      primary domain gets blocked
- [ ] **Domain pre-resolution**: resolve the server domain over a specified
      DoH server before connecting, pick the lowest-latency IP
- [ ] **User-Agent override** for subscription fetch and for geo file
      downloads (`safari-mac`, `chrome-win`, `chrome-android`, ...)
- [ ] **Configurable request timeout** (Happ: 5–15s, default 9)
- [ ] Consider exposing Xray's **Finalmask** (§6) — newer than anything in
      this list and arguably more important now

### A.3.4 Tunnel and core tuning

- [ ] `xray-tun-enable` — Xray's own TUN vs external tun2socks
- [ ] `xray-tun-mtu` — 68–65535
- [ ] `sniffing-enable` — protocol/SNI detection, on by default
- [ ] `mux-enable`, `mux-tcp-connections`, `mux-xudp-connections`, `mux-quic`
- [ ] `exclude-routes` — subnets that bypass the tunnel entirely
- [ ] `block-bind-to-tunnel-enable` — reject sockets explicitly bound to the
      tun interface (`curl --interface tun0`). Happ notes this only works
      with their BadVPN tunnel, not with Xray TUN.
- [ ] Trimmed geo files — pass the core only the tag fragments actually
      referenced by active rules, instead of the whole database. **Expensive**:
      requires parsing and re-emitting the `.dat` protobuf. Late milestone.
- ~~`hide-vpn-icon`~~ — **cut.** The excluded-route trick is a NetworkExtension
      behavior. On Android the VPN key notification is enforced by the system
      for any app holding a `VpnService` and cannot be suppressed. Do not
      promise this.
- [ ] Local SOCKS/HTTP inbound with auth modes: `auto`, `manual`,
      `from-json`, `disable`

### A.3.5 Behavior automation

- [ ] `subscription-autoconnect` + `-type`: `lastused` / `lowestdelay` /
      `random`
- [ ] `subscription-ping-onopen-enabled` — auto-test the server list on open
- [ ] Wi-Fi/mobile server filtering by name marker ("only WiFi" /
      "only Mobile"), with an opt-out
- [ ] Subscription pinning

## A.4 Tier 3 — provider ecosystem compatibility

Happ's header format has become the de facto standard for the whole
subscription-panel ecosystem. Remnawave — an open-source (AGPL-3) panel that
a large share of providers run — states plainly in its own documentation
that the header standard is the one offered by Happ, and consumes those
headers directly.

The practical consequence: **this is a protocol-compatibility problem, not a
product-philosophy problem.** A client that does not speak the protocol does
not work with those providers at all. Split the surface accordingly.

### A.4.1 HWID headers — required, build in Tier 1

When a provider enables the device limit, the client **must** send an HWID
header on the subscription request. Remnawave returns **404** when the
header is missing — the user simply cannot add or refresh the subscription.
There is no graceful degradation. This is not an optional nicety.

Headers sent on the subscription request:

```
x-hwid:          <stable unique device identifier>   REQUIRED
x-device-os:     Android                              optional
x-ver-os:        14                                   optional
x-device-model:  Pixel 8                              optional
x-app-version:   <version>                            optional
user-agent:      <see A.4.2>
```

Only `x-hwid` is required; the rest exist so the provider's device list is
human-readable in the panel.

Requirements:

- [ ] Generate a **stable, app-scoped** HWID. Never a hardware serial, IMEI,
      MAC, or advertising ID. On Android, `Settings.Secure.ANDROID_ID` is the
      right primitive: it is scoped to app-signing-key + user + device, and
      it **survives reinstall**. A freshly-generated UUID persisted to disk
      does not survive reinstall, which silently burns a slot from the
      user's device limit every time they reinstall — a support nightmare.
      Hash the value before sending so the raw platform ID never leaves the
      device.
- [ ] Send it by default. Happ sends by default; Throne ships it as a
      toggle disabled by default and consequently breaks against
      limit-enabled providers out of the box.
- [ ] Expose a user-visible toggle anyway (see §A.5), with a clear
      explanation that disabling it will break some subscriptions.
- [ ] Show the user their own HWID somewhere in settings. Providers ask for
      it during support, and it is their identifier.

**Response headers (Remnawave panel v2.7.5+).** Parse these and turn them
into distinct, actionable UI states rather than a generic fetch failure:

| Header | Meaning |
|---|---|
| `x-hwid-active` | Always `true` when the device limit is on |
| `x-hwid-not-supported` | `true` when the limit is on but the client sent no `x-hwid` |
| `x-hwid-max-devices-reached` | `true` when the user is at their device cap |
| `x-hwid-limit` | Duplicate of the above, kept for v2RayTun compatibility |

"Device limit reached — remove a device in your account" and "this
subscription requires HWID, enable it in settings" are different problems
with different fixes. A 404 with no explanation is the worst outcome and is
exactly what the user gets today from most clients.

Remnawave can also return a **provider ID** in response headers, letting a
client enable provider-specific behavior. Same concept as Happ's Provider
ID gating.

### A.4.2 User-Agent — also load-bearing

Remnawave supports **response rules**: the panel matches on request headers
(notably `user-agent`) and serves a different subscription format per
client. An unrecognized UA can therefore yield the wrong format entirely —
a JSON array where the client expected a base64 line list, or vice versa.

- [ ] Send a well-formed, versioned UA: `<AppName>/<version>`
- [ ] Make the UA overridable per-subscription (Happ exposes
      `change-user-agent` for exactly this reason)
- [ ] The parser must not assume a format from the UA it sent. Sniff the
      actual response body: base64 blob, plain line list, JSON, or YAML.
- [ ] Longer term, getting the client into Remnawave's recognized client
      list (`src/data/clients.ts` in the panel repo, and the subscription
      page's `app-config.json`) is the real path to ecosystem support. It is
      an open-source repo; this is a PR, not a business negotiation.

### A.4.3 The contested subset

These are the ones worth thinking about, because — unlike HWID — **none of
them affect whether a subscription loads.**

- Encrypted subscription links (`happ://crypt4/`, `crypt5/`) — RSA-4096,
  keys embedded in the app so the user cannot read their own subscription URL
- HWID-bound links — subscription pinned to one device, verified locally
- Limited links — central install counter at a Happ-operated endpoint
- `subscription-always-hwid-enable` — prevent the user from turning HWID off
- `hide-settings` — block the user from viewing or editing server configs
- `manual-block-user-agent` — block the user from changing their own UA
- JSON-subscription restricted mode: routing toggle force-locked, profile
  name/URLs/geo blocks unmodifiable, no manual creation or copying
- Provider ID gating for the advanced directive set

---

## A.5 Where this project draws the line

The dividing question is **not** "is this feature user-hostile?" It is:

> Does omitting this break the subscription, or only inconvenience the
> provider?

That gives a clean split.

**Omitting breaks the subscription → implement it.** HWID transmission,
every metadata and behavior directive in §A.3, correct UA handling,
fragmentation and fronting parameters. All of it. The provider's
infrastructure is counting on the client to speak this protocol, and the
person who suffers from a partial implementation is the user, who paid for a
subscription that now 404s.

**Omitting only inconveniences the provider → skip it.** `hide-settings`,
`manual-block-user-agent`, force-locked routing, `subscription-always-hwid-enable`.
A subscription with these flags set loads and works identically whether or
not the client honors them. They exist to restrict what the person holding
the device is allowed to look at. Ignoring them costs zero compatibility,
which makes this a free choice rather than a trade-off.

Two items sit outside that split for technical reasons:

**Encrypted links cannot work in an open-source client.** The security
premise is that RSA keys are embedded in a binary the user cannot inspect.
In an open repository the keys are in the source tree. Implement decryption
for **import compatibility** — a user handed a `crypt4` link should be able
to add it — but do not build a generator and do not pretend the contents are
hidden from the device owner.

**Limited links need infrastructure this project does not have.** Happ's
install counter runs on their servers: the client hashes the link domain,
calls a central endpoint with the device HWID, and is refused past the
limit. Reproducing it means operating that service and becoming a party to
cross-provider device tracking. Remnawave's panel-side HWID limit achieves
the same goal without a third party, and it is the one this project
supports.

### Summary

| Feature | Decision | Why |
|---|---|---|
| Tier 1 and Tier 2 in full | Build | Baseline function |
| `x-hwid` + device headers | Build, on by default | 404 without it |
| Response-header feedback (limit reached, HWID missing) | Build | Otherwise the user sees an unexplained failure |
| UA handling + response-rule awareness | Build | Wrong UA yields wrong format |
| HWID user toggle | Build, on by default, clearly labelled | Disclosure without breakage |
| Encrypted link import | Build | Compatibility |
| Encrypted link generation | Skip | Meaningless in open source |
| `hide-settings`, `manual-block-user-agent`, forced HWID, locked routing | Skip | Costs no compatibility |
| Happ limited-links central check | Skip | Needs a tracking backend |

Providers who require the skipped items will notice no difference in
whether their subscriptions work. That is the point of drawing the line
here rather than somewhere else.

---

# Appendix B: Sources

- Happ developer documentation — `happ.su/main/dev-docs`
  (`app-management`, `routing`, `hwid-links`, `limited-links`,
  `crypto-link`, `provider-id`, `examples-of-links-and-parameters`).
  Markdown versions are available by appending `.md` to any page URL, and
  `happ.su/main/llms.txt` is a full index.
- Community routing profiles: `github.com/hydraponique/roscomvpn-routing`,
  `github.com/demontmk/happ-routing` — real-world examples of the deeplink
  profile format.
- Xray-core release notes and issue tracker — `github.com/XTLS/Xray-core`.

Happ's documentation describes Happ's behavior, not a standard. Where this
project diverges (§A.5), the divergence is intentional and should stay
documented rather than being "fixed" toward parity.

---

# Appendix C: Prior art — what to take from which project

No open-source client currently combines all three of: native Android,
Xray-core, and Happ/Remnawave header support. Remnawave's own list of
HWID-capable clients contains no mainstream open-source Android Xray client
— the Android entries on it are Clash/mihomo-based Flutter forks. **That gap
is this project's reason to exist.** It also means the pieces have to be
assembled from several sources.

**Before you copy anything from any project below, check `THIRD_PARTY.md`.**
License determines whether a project may be copied from or only learned from,
and the distinction is not visible in the code. The short version: `SaeedDev94/Xray`
and `heiher/sockstun` are MIT and may be adapted with attribution; `v2rayNG`
and `LibreXrayVPN` are GPL-3.0 and must be read for behaviour only.

## C.1 Header protocol and HWID

| Project | License / stack | Take |
|---|---|---|
| **FlClashX** (`pluralplay/FlClashX`) | FlClash fork, Flutter, Android + desktop | Closest working reference for HWID + Remnawave headers on Android. Read the subscription-fetch path. |
| **Prizrak-Box** (`legiz-ru/Prizrak-Box`) | Pandora-Box fork, Android | The other Android client in the ecosystem. Its author also maintains `legiz-ru/my-remnawave`, which documents the Response Rules templating setup from the panel side — read that first, it explains *why* the client does what it does. |
| **Throne** (`throneproj/Throne`) | GPL-3.0, Qt + sing-box, desktop | HWID implementation landed as a discrete PR — small, readable diff. Note their choice to default it **off**, and that this breaks limit-enabled providers out of the box. Do the opposite. |
| **Koala Clash**, **DeskBox** | Clash Verge Rev / desktop | Desktop only. Skim for header handling, nothing else. |

## C.2 Android VPN plumbing

| Project | Take |
|---|---|
| **v2rayNG** (`2dust`) | The reference for `VpnService` + `protect()` + tun2socks on Android, and for every reconnect / Doze / network-transition edge case. Legacy XML UI — take the service layer, not the UI. |
| **SaeedDev94/Xray** | The most mature 100%-Kotlin Xray client. Cleaner and far smaller than v2rayNG. Good first read. |
| **XrayFA** (`Q7DF1`) | Actively maintained, Hysteria2 support already in. Check how they wire the newer protocols. |
| **yaxc** (`derundevu`) | Closest to RU-specific needs: tun0 defense, antifilter.download subnets, per-app split, SOCKS auth. |
| **hev-socks5-tunnel** | The TUN→SOCKS library itself. Not a client — a dependency. |

## C.3 Architecture and UI

| Project | Take |
|---|---|
| **LibreXrayVPN** (`Ko4Learner`) | Compose + Hilt + MVI + Clean Architecture on Xray. Small enough to read fully in an evening. Stale (late 2025) — use as a skeleton, not a dependency. |
| **Lust** (`envywook`) | Compose + subscriptions + HEV tun2socks. Very new, unproven. Worth a look for how they bridged hev via JNI. |

## C.4 The panel side

`remnawave/panel` is AGPL-3 and open. This is the actual specification, more
reliable than any client's interpretation of it:

- `docs/features/hwid-device-limit.md` — the header contract
- `src/data/clients.ts` — the recognized-client registry. Getting this
  project added is a PR against an open repo.
- Response Rules — the panel matches on request headers and serves different
  subscription formats per client. Understand this before debugging any
  "wrong format" bug.
- `remnawave/subscription-page` → `frontend/public/assets/app-config.json` —
  the client list shown to end users on the subscription page.

---

# Appendix D: Cut list

Items considered and deliberately dropped. Recorded so nobody re-adds them
in six months.

| Item | Why cut |
|---|---|
| `hide-vpn-icon` | Not achievable on Android; the system enforces the VPN indicator |
| `icmp` ping mode | Raw sockets need root |
| Encrypted link **generation** | Keys are in the public source tree; security theater |
| Happ limited-links central check | Needs a device-tracking backend this project will not run |
| `hide-settings`, `manual-block-user-agent`, forced HWID, locked routing | Zero compatibility cost to omit (§A.5) |
| Desktop-only directives (`tun-mode`, `tun-type`, `custom-tunnel-config`, `proxy-enable`) | Android-only project |
| iOS-only directives (`include-all-networks`, `exclude-apns`, themes, `proxy-ping-timeout`) | Android-only project |
| `no-limit-enabled` / RAM ceiling tuning | Exists because of the iOS NetworkExtension memory limit; no Android equivalent |
| `block-bind-to-tunnel-enable` | Happ documents it as working only with their BadVPN tunnel, not Xray TUN. Revisit only if a concrete need appears. |
| Kotlin Multiplatform | Out of scope by decision; see §1 non-goals |
