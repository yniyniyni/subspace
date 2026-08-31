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
| Persistence | Room — profiles, groups, subscriptions **and settings** | No DataStore; see §3. |
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
:core:network           HTTP: subscription fetch, HWID, User-Agent
:core:parser            Subscription and share-link parsing. Pure, heavily tested.
:core:ui                Design system: theme, tokens, shared Compose components
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
- `:core:ui` depends on `:core:model` only. `:feature:*` may depend on it;
  `:service` and `:core:parser` may not. It exists because `:feature:*` modules
  cannot depend on each other and `:app` sits downstream of them, so shared UI
  has nowhere else to live.
- `:core:network` depends on `:core:model` only. It returns a body and a header
  set; deciding what they mean belongs to `:core:parser`. Only `:core:data` may
  depend on it — `:feature:*`, `:service` and `:app` reach fetching through that
  module's repository, like every other data source.
- `:core:data` may depend on `:core:parser` and `:core:network`. It is the only
  module that may depend on **`:core:network`** — that restriction is the one
  `checkModuleBoundaries` enforces, and the one that matters, because
  `:core:network` is the I/O boundary and everything upstream of it must reach
  fetching through a repository.

  `:core:parser` is deliberately *not* restricted that way: it is a pure,
  side-effect-free library, and `:core:xray`, `:feature:home` and
  `:feature:profiles` legitimately depend on it — a feature module
  canonicalising a user-entered pin against `DirectiveRegistry` is using the
  same validation the data layer uses, not reaching around it. An earlier
  version of this bullet claimed `:core:data` was the only module depending on
  *either*, which was simply false when written: three modules already declared
  `:core:parser` and the checker never enforced it.

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

DNS must not escape. There are **three** levers, not two, and the third is
the one that actually reaches a misbehaving app:

- `VpnService.Builder.addDnsServer(...)` for the TUN interface. This only
  *advertises* a resolver; an app is free to ignore it.
- A `dns` block in the Xray JSON config — the servers the built-in client
  queries, and the per-domain scoping that splits them.
- **A routing rule sending all port-53 traffic to a `dns` outbound** (M6.5).
  `{ "network": "tcp,udp", "port": 53, "outboundTag": "dns-out" }`. This is
  what catches an app that hardcodes a resolver instead of using the
  advertised one, and without it such an app either reaches that resolver or
  gets nothing at all. Verified on hardware: with the hijack in place a query
  addressed to the ISP's own resolver comes back answered by the *configured*
  resolver; with no plan emitted, the same query is silently swallowed.

**These three levers describe a config this app generates, and a `RAW_JSON`
profile running in passthrough (§6) does not get all three.** It carries its
own `dns` and `routing`, so levers 2 and 3 — the config's `dns` block and the
port-53 hijack rule — are whatever that config's author wrote, not ours: a
config that sends its resolver's traffic to a `freedom` outbound will leak,
and nothing here stops it. **Lever 1 still fires, unconditionally, regardless
of passthrough**: `TunnelService.establishTun` calls
`builder.addDnsServerOrFallback(dnsPlan)` on every connect, and in the pure
passthrough branch (no rule set, no custom resolver) `dnsPlan` is null, which
falls back to advertising `DNS_SERVER` (`1.1.1.1`) on the TUN interface to
every app on the device — while the config's own `dns` block governs only
what xray's *internal* client resolves. So a passthrough connection leaves
the device with two DNS opinions at once: the TUN says 1.1.1.1, the config
says whatever it says, and neither is enforced against the other. This is
exactly the lever-inconsistency this section says must not happen; the code
has not been changed to close it, and this paragraph no longer claims that it
has. The guarantee is restored (all three levers coherent again) the moment a
rule set or a DNS resolver is configured, which replaces the config's `dns`
and `routing` blocks wholesale (§6). Verified against the target panel's own
template, not assumed to hold generally: its rules send every DNS query
through the proxy except a vestigial `223.5.5.5:53 → direct`, so that
particular config does not leak *its own* traffic — but the TUN-level
1.1.1.1 advertisement above is independent of what any given config does.
Source: `docs/agent/research/2026-08-25-remnawave-xray-json-and-balancers.md`.

**The hijack is a loop hazard unless something claims the resolver's own
traffic first.** The built-in resolver's query *to a DoU server* is itself
UDP to port 53, so if nothing ahead of the hijack claims it, it matches the
hijack and is fed straight back into the resolver that issued it. What
guarantees that is the **unconditional catch-all** — one rule matching
`inboundTag: ["dns-module"]` with no address filter, emitted on every plan,
targeting `proxy` and never `direct`. The per-resolver rules above it are
conditional (a plan often has neither) and exist to *split* domestic-direct
from remote-proxy, not to provide this guarantee. §6 has the order.

**A `+local` scheme bypasses the routing component, and therefore the
tunnel.** `https+local://`, `h2c+local://`, `tcp+local://`, `quic+local://`
and `localhost` are constructed without the dispatcher, so no routing rule
can pull their queries back — for a remote resolver that is a leak by
construction. Never emit one. Note there is no plain `quic://` at all at the
pinned v26.7.11: DoQ is local-only there, so it cannot be used for a remote
resolver — established by reading the scheme switch at that one tag, and not
claimed beyond it. Sourced in
`docs/agent/research/2026-08-23-xray-dns.md` §2.

If only one lever is set you get a partial leak that works fine on Wi-Fi and
breaks on mobile, or vice versa. Verify externally — a leak-test site, or a
raw query aimed at the ISP's resolver whose answer you can attribute — never
by reasoning about the config.

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

### Two profile kinds, and what each stores

Storage follows provenance. Share links, base64 lists and Clash YAML have a
**bounded, known** field set, so they are stored typed (`kind = TYPED`): typing
them is cheap and it is what keeps a hallucinated Xray key (§10.5) from ever
reaching a config, because a key absent from `Outbound` cannot be written.

A hand-written `config.json` is **unbounded**. It is stored byte-for-byte
(`kind = RAW_JSON`), alongside typed columns extracted from it for display,
search and identity. Extraction alone would be lossy — `XrayJson.kt` pulls out
the VLESS outbound and discards ws path and headers, gRPC service names,
Finalmask blocks, and anything else the model has no field for. That loss is
permanent once the pasted text is gone; storing the bytes makes it reversible.

### Passthrough execution: `RawConfigComposer`

A `RAW_JSON` profile marked eligible (below) runs **as written**: its own
`routing`, `dns` and `outbounds` reach the core, not the typed projection every
other profile kind goes through. This is M7. The obstacle §6 used to record
here — the config carries its own `inbounds`, and the tunnel needs a SOCKS
inbound on a port allocated at connect time (§10.6 forbids a literal); adopting
the config's port collides with other proxy apps, injecting ours means
rewriting `routing`, which is no longer "as written" — is resolved rather than
avoided: **the config's `inbounds` are replaced outright**, not merged with or
adapted to. A shipping libXray client resolves the same constraint the same
way (Appendix C.2, OneXray); that is corroboration, not the reason — the
reasoning stands on §10.6 and on tun2socks dialling a port this app allocated.

`RawConfigComposer` (`:core:xray`) does the rewriting, working on parsed JSON
rather than a typed model precisely so it preserves whatever the config
carries that this design does not name — `burstObservatory`, `reverse`, an
opaque `fakedns` object. Four rewrites apply regardless of which branch below
is taken:

- `inbounds` replaced with the SOCKS + HTTP pair `socksInboundJson`/
  `httpInboundJson` already emit for a typed profile — moved into a shared
  `Inbounds.kt` so both paths call one definition rather than two that can
  drift apart silently.
- `env` set to the resolved geo asset directory — Go cannot see a Java
  `setenv` (§6's `XrayEnv` note above still applies).
- `log` forced to `access: "none"`, `loglevel: "warning"` (§5.6 — the device-
  found logcat leak, one line per destination).
- `stats`/`policy`/`metrics` stripped — a config's own listener is an
  unaudited open port, not this milestone's concern.

**Two branches, chosen from state the app already has, not a persisted
setting:**

- **Pure passthrough** — the app has no routing or DNS opinion of its own (no
  active rule set, and no DNS plan). The config's `routing`, `dns` and
  `outbounds` reach the core byte-identical; nothing of ours is prepended or
  removed. This is the branch §5.2's new carve-out describes.
- **App override** — a rule set is active, or a DNS plan exists (the same
  `routingActive || dnsPlanPresent` `resolveAndStartCore` already computes).
  `routing` and `dns` are **removed** and replaced with the blocks the typed
  generator would emit for the same state; the config's own `outbounds` and
  their `streamSettings` survive untouched, and the app's stock
  `direct`/`block`/`dns-out` outbounds (the same three literals
  `XrayConfigGenerator.overrideBlocks` hands a typed profile's routing) are
  **appended** to them — the substituted `routing` rules name those tags, and
  an outbound they reference has to exist somewhere in the composed config's
  `outbounds` array. `RawConfigComposer.compose` appends unconditionally; what
  makes that safe is `TunnelService.composePassthrough` filtering the append
  against the stored config's own outbound tags first
  (`existingOutboundTags`/`tagOf()`, `TunnelService.kt`) — a config that
  already defines `direct` or `block` itself (common: a `direct` freedom
  outbound is exactly the kind of thing a hand-built config carries) would
  otherwise pick up a duplicate tag alongside its own — a config the core is
  not obliged to accept.
  Deletion rather than merge for `routing`/`dns`: an arbitrary Xray rule array
  has no single reading once a second author's rules are interleaved into it,
  and `RoutingRuleSet`'s three-bucket model cannot express one anyway.

**Sniffing is preserved, not defaulted, in the pure branch.** Traffic arrives
from tun2socks addressed to an IP; without sniffing, a `domain`/`geosite:` rule
in the config's own routing matches nothing, and replacing the inbounds would
silently discard whatever sniffing settings made those rules work. So the
config's own `sniffing` block is carried onto the injected SOCKS inbound
verbatim when it has one; the app's default sniffing settings are used only
when it has none. Not the reverse: injecting a wider `destOverride` than the
config chose (adding `quic` to a config that sniffed only `http`/`tls`, say)
would start matching rules the config's author never exercised, moving traffic
the app was never asked to move — a leak in the other direction, introduced by
us, with no error and no log line. In the override branch this does not apply:
the app's rules are what runs, so the injected inbound takes the app's own
sniffing defaults.

**Eligibility is decided at import, against the real core**, not discovered at
connect. `testXray` runs against the composed form — the same bytes with the
same inbound pair a connect would inject — plus a check that the element
produced exactly one profile or is a `routing.balancers` entry (an
"auto/best server" document, which collapses into one passthrough row rather
than being read as several servers to choose from — reading it as several
would run the same tunnel under every row). A profile failing either check
keeps today's typed-projection behaviour, with the failed check named in the
editor rather than left to guess. A profile that passes and later fails
`testXray` at connect — the stored bytes or the environment changed since
import — fails visibly with `FailureReason.PassthroughRejectedAtConnect`
rather than falling back to the typed projection; a silent fallback would be
two different tunnels behind one tap.

**Three gaps in that import-time check are accepted, not oversights, and all
three lean on the same connect-time backstop:**

- **The periodic subscription-refresh path skips core validation.**
  `SubscriptionRefreshWorker` runs from `:app` via `RefreshScheduler` straight
  into `SubscriptionSyncer`, never through `ProfileSource`. This is a scope
  decision, not a structural one: `:core:data` (where `SubscriptionSyncer`
  lives) genuinely cannot depend on `:core:xray` (§4), but `RefreshScheduler`
  itself lives in `:app`, and `app/build.gradle.kts` already declares
  `:service` — nothing in §4's graph stops `RefreshScheduler` from taking a
  `PassthroughValidator` dependency today. It was left out of M7's scope
  rather than ruled out by the module boundary. A refreshed row still gets the
  full *structural* verdict (`analysePassthrough`, the same balancer collapse
  the first import gets) — only the real-core check is missing.
  `FailureReason.PassthroughRejectedAtConnect` is what makes a core refusal on
  such a row fail visibly at connect with an accurate reason, instead of
  connecting into an untested config.
- **Core validation is skipped entirely when geo assets are not installed.**
  `testXray` genuinely resolves geo files at config-build time — confirmed on
  hardware, not assumed:
  `docs/agent/research/2026-08-11-geo-assets-and-xray-routing.md` §2b records
  `testXray` failing at *build* with `failed to open geosite.dat` when the
  asset location does not resolve. Validating a config carrying `geosite:`/
  `geoip:` rules before the curated geo files download would therefore mark it
  rejected **permanently** for a condition that resolves itself the moment the
  download completes — exactly the durable-wrong-verdict failure §10.4 warns
  against, and routing-heavy configs are exactly what this milestone targets.
  Same backstop: an unvalidated row that the core would in fact refuse fails at
  connect instead of at import.
- **A config with no exact `proxy` tag is not rejected at import, but it now
  fails closed before core startup when an app override applies.**
  `PassthroughAnalysis` still computes `OverrideBlocker.NoProxyTag` without
  persisting it, because the pure branch can run such a config correctly: none
  of the config's own rules needs our conventional tag. The override branch is
  different — every app-generated proxy rule currently names `proxy`.
  `TunnelService.composePassthrough` therefore reuses the analyser's outbound
  tags immediately before composition and returns
  `FailureReason.PassthroughOverrideUnavailable` when that exact target is
  absent. The core is never started, so the previously reproduced failure
  shape — Connected while matching traffic is dropped — is closed.

  This is a guard, not full support for arbitrary tags or balancers. Resolving
  the config's actual server/balancer target remains M7.5; until then, turning
  app routing off and restoring the default DNS setting runs the config's own
  routing unchanged. Import-time `PassthroughValidator` still exercises only
  the pure branch (`override = null`), so this decision deliberately stays at
  connect where the active routing/DNS state is known. Duplicate outbound tags
  remain a separate structural failure that xray-core rejects during the
  connect-time `validate` call.

  Same dead-analysis shape as `overrideBlocker` otherwise, recorded here
  rather than wired for the same closing-milestone reason (a consumer is a UI
  task with its own copy and its own tests): `PassthroughAnalysis.advisories`
  (a `List<PassthroughAdvisory>`) and the `serverOutboundCount`/`outboundTags`
  fields it sits alongside are likewise computed and unit-tested with no
  consumer anywhere in the app. Worth naming specifically because one of the
  conditions it can detect is `SniffingCannotServeOwnRules` — a config whose
  domain/geosite routing rules can never match because its own sniffing
  settings do not surface the destination those rules need. That is not a
  config that fails to connect; it is one that connects, passes traffic, and
  silently ignores its own routing the whole time — §10.1's signature
  failure, already detected by code in this tree, told to nobody. A future
  contributor should be able to find that by reading this paragraph rather
  than by grepping for `PassthroughAdvisory` and wondering why it exists.

**Two more edges live in the `LENIENT` JSON parser itself** (`RawConfigComposer`,
`PassthroughAnalysis`, `XrayRoutingConversion` — each its own `Json { isLenient = true }`
instance, same shape, no shared object), not in the eligibility check above. Both are parser
edges, not design positions — nothing here chose these behaviors on purpose:

- **A non-standard unquoted literal round-trips into invalid JSON.** `isLenient` accepts an
  unquoted scalar on parse (e.g. `mode: auto` rather than `"mode": "auto"`), producing a JSON
  element whose `isString` is `false`; that element's own `toString()` re-emits the literal
  unquoted, so text `RawConfigComposer.render` produces from it can itself fail to parse as JSON,
  even though `compose` returned `ComposeResult.Ok`. This is backstopped, not silent:
  `BoundPassthroughValidator` hands the composed text to `testXray` before an import is accepted,
  so the failure surfaces as `PassthroughRejection.CoreRejected` — "Xray rejected this file"
  (`editor_raw_json_rejected_core_rejected`) — which is true but imprecise about which side
  actually produced the invalid bytes.
- **`//` line comments make a config `NotJson` here.** kotlinx.serialization's lenient mode
  relaxes quoting rules, not comment syntax — no `Json { }` option enables comment parsing — so a
  stored config using them fails to parse in this app before the balancer/eligibility check or
  `testXray` ever runs. Whether xray-core's own JSON loader accepts such comments is **not
  established from anything in this codebase** (§10.5):
  `docs/agent/research/2026-07-27-m2-residuals-for-m3.md` records the claim and flags it
  explicitly as unverified against Xray-core source. If it turns out to be true, a config that
  would run correctly on-device is rejected here first, and the user is told `NotJson` for a file
  the core itself would have accepted.

Related: `ignoreUnknownKeys = true` was present in all three `LENIENT` blocks above but is inert
in every one of them — it governs typed deserialization (`decodeFromString`), and all three call
sites only ever use `parseToJsonElement`, which never consults it. Removed rather than left
looking load-bearing.

It is per-kind, not a migration: `TYPED` profiles generate from the typed form
permanently, and only `RAW_JSON` switches. The typed columns stay either way —
they are what the UI filters and sorts on.

Shape:

```
inbounds:  socks (127.0.0.1, loopback only) [+ optional http]
outbounds: [proxy (from profile), direct (freedom), block (blackhole)
            + dns-out (protocol "dns") when a DNS plan exists]
routing:   [DNS rules, prepended] then rules referencing geoip.dat /
           geosite.dat, then user rules
dns:       servers (+ hosts, queryStrategy and tag "dns-module" only when
           a DNS plan exists; the no-plan form is servers alone)
stats/api: enabled when traffic counters are on
```

The DNS rules, when a plan exists, are prepended in this order — the order
is load-bearing (§5.2):

```
[if the plan has a domestic match]
{ "type": "field", "inboundTag": ["dns-module"], "ip"|"domain": [<domestic>], "outboundTag": "direct" }
[if the plan has a remote match]
{ "type": "field", "inboundTag": ["dns-module"], "ip"|"domain": [<remote>],   "outboundTag": "proxy"  }
[always]
{ "type": "field", "inboundTag": ["dns-module"],                              "outboundTag": "proxy"  }
{ "type": "field", "network": "tcp,udp", "port": 53,                          "outboundTag": "dns-out" }
```

The first two are **conditional** and are the *split*: they claim one named
resolver's own traffic each — matched by address, `ip` for a literal and
`domain` for a DoH endpoint's hostname — and send the domestic one direct and
the remote one through the proxy. A plan frequently has neither, which is why
they cannot be what makes the arrangement loop-safe.

The third is **unconditional**, and it is the one that matters: it claims *all*
remaining DNS-module traffic before the hijack below it can see it (§5.2).
Its target is `proxy`, never `direct` — a resolver query sent `direct` leaves
the tunnel, which is the exact leak the plan exists to prevent. Do not treat
it as a residual case of the two above it; delete it and every plan without a
named profile resolver loops.

`dns-out` is emitted as bare `{ "tag": "dns-out", "protocol": "dns" }`: it
carries no settings, so it touches neither the legacy nor the rewrite
generation of `DNSOutboundConfig` and is stable across upstream's deprecation.

When nothing asks for DNS — no profile block and the app-level setting at its
default — **none of this is emitted**, and the config is byte-identical to the
M1 shape that was proven on hardware. That is deliberate: the default resolver
literal was chosen to make that identity possible.

Rules:

- Generate deterministically. Same profile + same settings ⇒ byte-identical
  JSON. This makes diffing and testing possible.
- Bind inbounds to `127.0.0.1` only. Never `0.0.0.0` — that turns the phone
  into an open proxy on the local network.
- Geo databases are **not bundled in the APK**. `GeoSourceCatalogue`
  (`:core:model`) lists curated sources — v2fly, Loyalsoldier and
  runetfreedom, each publishing its own `geoip.dat`/`geosite.dat` pair (v2fly's
  domain list ships as `dlc.dat` and is installed under the name xray-core
  expects) — plus whatever custom source a user adds. `GeoAssetRepository`
  (`:core:data`) fetches a chosen source **on demand**: it streams the
  download to a staging directory (never a `ByteArray` — the largest catalogue
  entry is 73.7 MB, an easy OOM), validates it with `GeoDataValidator` before
  anything live is touched (an HTTP 200 proves nothing; a captive portal or a
  GitHub error page is valid HTTP and invalid protobuf), then installs
  **atomically**: same-filesystem renames move the `.json` sidecar into place
  first and the `.dat` file last, so a process killed mid-install can only
  ever leave a stale-but-consistent live pair, never a half-written one.
  xray-core is pointed at the install directory through the invoke `env`
  object PR #133 restored upstream (`third_party/libxray-patches/`) —
  `XrayController` builds it from `GeoAssetRepository.geoDirectory()` and
  sends it on every `testXray`/`runXray` call. **`testXray` (import-time
  validation) does not consult this channel at all — measured on a Pixel 8,
  2026-08-31**
  (`docs/agent/research/2026-08-25-m7-device-verification.md` finding F8,
  all five rows calling `XrayController.validate`, i.e. `testXray`; none
  exercise `runXray`): it resolves geo files from the composed config's own
  `env["xray.location.asset"]` alone, never from this invoke envelope, which
  is why `RawConfigComposer.compose` writes that key into the JSON it hands
  `BoundPassthroughValidator` (`service/.../PassthroughValidator.kt`) rather
  than relying on `XrayController`'s `geoAssetDir` to reach it. **`runXray`
  (a running tunnel) is not measured by F8** — that is a separate claim,
  carried by inference rather than by the same evidence: the typed-config
  generation path never writes an `env["xray.location.asset"]` key into its
  own JSON (only `RawConfigComposer`'s passthrough path does), so for a typed
  profile's tunnel the invoke envelope is the only channel available to name
  a geo directory at all, which is why the codebase relies on it there. An
  earlier version of this paragraph claimed the invoke envelope was what
  mattered for both calls alike; believing that for `testXray` produced a
  validator that refused every config carrying a `geosite:`/`geoip:` rule.
  **Not**
  `android.system.Os.setenv`: an earlier version of this design used it, every
  automated test passed, and it does not work — Go's Android shared-library
  entry point starts the runtime with an empty environment, so `os.LookupEnv`
  inside Go can never see anything a Java-side `setenv` writes, in any
  process, at any point. Verified on hardware by `AssetLocationProbeTest`;
  full derivation in
  `docs/agent/research/2026-08-11-geo-assets-and-xray-routing.md` §2b. This is
  still §10.2's category — it looks like wiring that could move without
  consequence, and the wrong mechanism here fails silently (no exception, no
  log line) rather than loudly.

  **The install directory is no longer flat.** M5's single `filesDir/geo`
  root held one `geoip.dat`/`geosite.dat` pair for the whole app, which
  M6 broke: two imported routing profiles can name different upstreams for
  the same filename, and the second would silently overwrite the first's
  data under a name the first still references. So a profile's files live
  in `filesDir/geo/sets/<ruleSetId>/<generation>/`, and
  `XRAY_LOCATION_ASSET` points at the **active** rule set's current
  generation rather than at one shared root — `TunnelService` therefore
  resolves routing *before* constructing `XrayController`, since the
  controller builds its `env` once per instance.

  Generations are what make the swap atomic **by construction** rather than
  by careful ordering. A new generation materialises alongside the live one;
  publishing it is a single Room `UPDATE` that moves the rules and the
  generation number together, so no reader can observe rule columns and the
  live generation disagreeing. Nothing renames a directory and nothing
  deletes the old generation until the new one is live. The user-visible
  half of that promise is §A.3.1's: the core keeps running on the old
  ruleset until every file lands.

  Curated sources keep the flat root: `GeoAssetRepository` is unchanged, and
  a profile that references a filename already installed there reuses it
  rather than re-downloading (a 23–74 MB cellular fetch is not something to
  repeat for a file the device already holds).

  **Why not bundled.** Measured per-file sizes range 2.3–73.7 MB depending on
  source (`docs/agent/research/2026-08-11-geo-assets-and-xray-routing.md`
  §7), against an APK that is otherwise small. Bundling even the smallest
  curated pair would roughly double the install size for every user,
  including the majority who never enable a `geoip:`/`geosite:` rule. Fetching
  on demand means only someone who turns on routing pays that cost, once, for
  the one source they picked — not every install, for sources most users never
  use.
- The `[+ optional http]` inbound above is now used, not merely reserved:
  when a session allocates it a port, `TunnelProxyBinding` (`:app`) publishes
  it through the `TunnelProxyLocator` interface, and `:core:data`'s
  `SubscriptionSyncer` dials subscription fetches through it while the tunnel
  is connected, instead of direct from the device. HTTP rather than a second
  SOCKS inbound: an HTTP `CONNECT` carries the target as `host:port` in
  authority form, so the *proxy* performs DNS resolution — a Java SOCKS client
  may resolve locally first, which would leak the subscription hostname while
  appearing to fetch through the tunnel (§5.2, research §8).
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

`:core:parser` handles four container shapes:

- Base64-encoded newline-separated lists
- `vless://`, `vmess://` (base64 JSON body), `trojan://`, `ss://`, `socks://`
- Clash / Clash.Meta YAML
- Raw Xray JSON

### What actually parses, per container

Five protocols across four containers reads as twenty combinations. It is
sixteen. The gaps are not bugs — nobody has written those branches — but they
are load-bearing enough that guessing from the list above will mislead you:

| | share link | base64 list | Clash YAML | raw Xray JSON |
|---|---|---|---|---|
| vless | ✓ | ✓ | ✓ | ✓ |
| vmess | ✓ | ✓ | ✓ | ✗ |
| trojan | ✓ | ✓ | ✓ | ✗ |
| ss | ✓ | ✓ | ✓ | ✗ |
| socks | ✓ | ✓ | ✓ | ✗ |

The base64 column can never differ from the share-link column: a blob is
decoded and re-fed through detection, which lands on the link-list path.

**A Clash config's `vless` entries are now connectable.** VLESS is the only
protocol `:core:xray` can emit a config for, and it used to be the one cell
Clash lacked — every Clash `vless` entry produced a profile that failed at
connect time with `ProtocolNotSupported`. That gap is closed: `reality-opts`
and `ws-opts` map into `StreamSettings`, so a Clash entry with `type: vless`
now parses into a working profile the same way a `vless://` link does.

**M3 fills two of the six gaps that remained, not all of them:** Clash
`vless` and `socks5`. The Clash column is now complete — every protocol Clash
can express, this parser can turn into a profile. Raw Xray JSON's four
missing cells wait, because filling them would produce profiles that parse
and then fail at connect with `ProtocolNotSupported`; they belong with the
milestone that makes those protocols connectable, and each of those needs its
own device verification (§10.1). SOCKS itself stays in the same position it
was already in: `:core:xray` cannot emit a SOCKS outbound, so a Clash
`socks5` entry parses into a profile that is visibly not-connectable in the
UI, exactly like a `socks://` share link.

`CapabilityMatrixTest` in `:core:parser` pins every cell of this table, so it
fails if one changes without this table changing with it.

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

### Second-opinion fallback — groundwork, not yet wired

`ShareLinkFallback` in `:core:xray` retries **failed lines only** through
libXray's `convertShareLinksToXrayJson` and reparses the resulting Xray JSON.
It lives there rather than in `:core:parser` because it needs the AAR, and §4
requires the parser to stay pure JVM so its tests run without a device.

**Nothing calls it.** It has no production caller anywhere outside
`:core:xray`'s own tests, so no recovery of unparseable lines happens today —
a line `:core:parser` cannot read is simply a failure the user sees. Do not
reason about the parser's behaviour as though this were in the path.

Two things must happen before it is wired to a caller, and both are recorded on
`retry`'s KDoc: it currently **drops `reparsed.failures` on partial recovery**,
so a line expanding into several servers, most of them unreadable, is reported
as a clean success (a §10.4 violation); and its index-mapping guards mean it
declines to do anything at all for three of the four container shapes.

---

## 8. Per-app proxy

Shipped in M5.5: a mode (`Off` / `AllowList` / `DenyList`) plus a package
selection, applied to the TUN interface at `establish()` time.
`PerAppMode`/`PerAppSelection` live in `:core:model`; the mode and package set
are stored through `SettingsRepository` (Room, §3 — no DataStore), and
`PerAppRepository` computes the one effective selection that both the
`:feature:routing` picker and `:service` read, so the UI cannot disagree with
the tunnel. `PerAppResolver` and `builderPlan()` (`:service`) turn that
selection into the branch below, inside `TunnelService.establishTun()`.

**The two modes are mutually exclusive at the platform level, not just by
convention.** `VpnService.Builder` may hold allowed applications or
disallowed ones, never both: calling `addAllowedApplication` after
`addDisallowedApplication` (or the mirror order) throws
`UnsupportedOperationException`. Verified against AOSP
`android/net/VpnService.java` (android-9.0.0_r35) and the current
`developer.android.com/reference/android/net/VpnService.Builder`, both
accessed 2026-08-18. This is why the per-app decision is a sealed type
(`BuilderPlan`) decided once, before `Builder` is touched, rather than a pair
of booleans checked inline — the mistake is then unrepresentable in
`establishTun()` instead of merely undesirable.

| Mode | Calls | Own package |
|---|---|---|
| `Off` | `addDisallowedApplication(ours)` | fatal on `NameNotFoundException`, unchanged from M1 |
| `DenyList` | `addDisallowedApplication(ours)` + each selected | fatal for ours; per-package catch/skip/continue for the rest |
| `AllowList` | `addAllowedApplication(selected)` only | **excluded by omission — never disallowed** |

**In allow-list mode the app's own package is excluded by omission, not by an
explicit call.** `addDisallowedApplication` cannot be called in that branch —
the constraint above — so our own traffic going direct depends entirely on
`PerAppResolver` having already stripped our package out of the selection
before it ever reaches the builder. There is no second mechanism backing this
up in that mode; if the resolver ever stopped filtering it, nothing else in
`establishTun()` would catch the omission.

**A code review caught a Critical here, worth recording because it is not
obvious from the method names.** AOSP's `addAllowedApplication` calls
`verifyApp()` — which can throw `NameNotFoundException` — *before* it lazily
creates the allowed-applications list (`android/net/VpnService.java`,
~806–817). If every package offered to an allow-list connect throws (all
uninstalled since selection), the list is never created at all. A null
allowed-applications list means **no per-app filtering whatsoever**: every
app is tunnelled, including this one — exactly the routing loop §5.1
describes, produced by an allow-list whose contents had quietly gone stale
rather than by a bug in the filtering code. `applyEach()`/`PackageApplication
.nothingApplied` in `PerAppResolver.kt` detect this case (`skipped >=
requested`), and the `Allow` arm in `establishTun()` refuses to call
`builder.establish()` when it holds — logged as `"per-app: allow list empty
at the builder; refusing"` and returned as `TunResult.AllowListEmptied`.

**An empty allow-list is refused, not started.** Whether empty from the
start (`PerAppResolver.resolve()` returns `EmptyAllowList` when nothing
survives stripping the app's own package) or emptied at the builder as above,
the service refuses with `FailureReason.PerAppAllowListEmpty` rather than
producing a tunnel nothing can use — §10.1's "connected, no traffic, no
error" signature, this time built by configuration instead of a bug.

**Per-package `NameNotFoundException` is caught, counted, and skipped** —
one stale entry (uninstalled since it was selected) must never abort the
whole tunnel setup. Only the **count** is logged (`Log.w`, "skipped N
uninstalled package(s)"); §5.6 forbids logging the package name itself,
because a package name identifies an app the user has installed. **Failing
to exclude our own package is the one exception that stays fatal**, in every
mode — see the table above — because that failure builds §5.1's loop by
construction rather than merely narrowing the selection.

**Enumerating installed apps needs `QUERY_ALL_PACKAGES`.** Declared in
`:core:data`'s manifest (next to `InstalledAppsSource`, the one class that
needs it — the same pattern `:service`'s manifest uses for its own
permissions), not in `:app`. That permission requires a Play Store
declaration if this project is ever published there; VPN clients are an
accepted use case, but expect review friction. F-Droid and IzzyOnDroid do
not care (§14.7). A `<queries>` element filtered on a `LAUNCHER` intent was
considered and rejected: it hides apps with no launcher activity that still
use the network, and a user cannot exclude an app the picker never shows — a
silently partial list reads as a bug, not as a policy.

Saving a changed selection while the tunnel is running rebuilds it **once**,
via `reapplyPerApp()` on `ITunnelService` — a deliberate choice over
reconnecting per toggle, which would drop the tunnel repeatedly through a
multi-app edit.

**Status.** The mechanism above is implemented and unit-tested
(`PerAppResolverTest`, `PerAppBuilderPlanTest`), and the picker is reachable:
`PerAppScreen` (`:feature:routing`) is pushed from Settings, covered by
`PerAppViewModelTest` and `PerAppScreenContentTest`. What is still missing is
the only part that counts here — it has not been exercised on a physical
device. §A.2 stays unticked until it has (§10.1).

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

**Disable animations on the test device before any Compose instrumented run.**

```
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
```

Keep the screen **on and unlocked** too: a locked device fails Compose tests
that navigate, and it looks like a routing or back-stack bug rather than a
lock screen.

"On" means awake, not merely unlocked. A device that is unlocked but has
dozed off fails the same way, and worse: every run aborts with `No compose
hierarchies found in the app`, with the animation scales already at 0 and no
lock screen to blame. The message names Compose, so it reads as the screen
never composing — a Hilt injection failure, a crashed activity, a `setContent`
that never ran — and the obvious checks all pass, because the configuration
really is correct. Diagnose it before assuming anything about the code:

```
adb shell dumpsys power | grep mWakefulness
```

Anything other than `mWakefulness=Awake` — `Dozing` is what M5.5's per-app
picker run hit — is the answer, and waking it is one call:

```
adb shell input keyevent KEYCODE_WAKEUP
```

**A one-shot wake is not enough for a suite that takes more than a few
seconds.** M5.5's `:feature:routing` run is 23 tests over ~59s, and a device
woken immediately beforehand dozed off *partway through it*: the first tests
passed and two later ones failed with the message above, which reads exactly
like a real, isolated product bug in whichever class happened to be running
when the screen went dark. Pin it awake for the duration instead:

```
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
```

Unlike the animation scales, none of this is sticky across a reboot, and the
doze timer runs regardless — so a suite that ran green an hour ago can fail on
the next invocation with nothing changed. Check wakefulness on every failing
run, not once.

With animations on, `waitForIdle` never settles and node lookups fail
non-deterministically. This does not look like a configuration problem: it
looks like flaky product code. During M5's verification it produced a full
green run, then scattered failures across `:core:ui`, `:feature:profiles` and
`:feature:routing` — modules that milestone never touched — with a *different*
test failing on each pass, which is exactly the shape of a real race. Three
`settings put` calls turned 268 tests from failing back to green with no code
change. The scales reset when the device reboots, so re-check them rather than
assuming a device that once ran the suite still will.

`:core:data`'s repositories (`ProfileRepository`, `SubscriptionRepository`,
`SubscriptionSyncer`, ...) take their DAO/`SubspaceDatabase` dependencies
through `internal` constructors on purpose — production code reaches them only
through Hilt, never by hand. A test in a *different* module that legitimately
needs a real instance (not a fake) over an in-memory database — introduced by
Task 12's `SubscriptionRefreshWorkerTest` in `:app` — cannot call those
constructors itself (`internal` does not cross a Gradle module boundary) and
must not add a dependency on `:core:network` just to supply one constructor
argument (§4: only `:core:data` may depend on it). `:core:data`'s `testFixtures`
source set (`android { testFixtures { enable = true } }`) is the sanctioned
way out: it compiles with the same access `:core:data`'s own `androidTest`
has, and exposes only the already-public repository/syncer types outward. See
`InMemorySubscriptionStack` in `core/data/src/testFixtures/`.

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
two grounds, but do not go looking for that API.

**Correction, 2026-08-24.** This section previously closed by saying §5.2 is
satisfied by `addDnsServer()` plus the `dns` block and that *"there is no third
lever"*. That was wrong twice over. The absence of a libXray DNS API says
nothing about what the *config* can express, and M6.5 added the lever that
matters most: a routing rule sending port-53 traffic to a `dns` outbound.
There are three, and §5.2 names all of them. What remains true is the narrow
original point — there is no libXray call that sets DNS, so the generated
config is the only place it can be configured.

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

`ping` is worth one note beyond the table, because M4.5 depends on it. It runs
`StartXray`, **not** `RunXray`, so it builds an independent core instance and
never touches the `coreServer` singleton that `runXray`/`stopXray`/`getXrayState`
share. Measuring latency therefore does not disturb a running tunnel, and
`getXrayState` cannot see a measurement in progress. It also means every
measurement needs its own config file and its own free port — §10.6 applies per
measurement, not once per app run — and that a failed ping returns
`success:false` *with* a sentinel `delay` of `10000`/`11000` in `data`, which
`LibXrayInvoke` discards so it can never be rendered as a measurement (§10.1).
Full source: `docs/agent/research/2026-08-10-libxray-ping-semantics.md`.

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

**When a key arrives on both transports, the header wins**, and the body line
is consumed rather than left for the config parser to choke on. M4's device run
settled what was previously a §10.5 guess, in two parts:

- **Remnawave never emits body directives at all.** Every directive is an HTTP
  header (`getUserProfileHeadersInfo`), and none of the five body generators
  — clash, mihomo, singbox, xray-json, xray — writes a `#` line. The conflict
  cannot arise from this panel, which is what bounds the risk here.
- **The implemented rule is header-wins**, verified end to end against a
  response that set `profile-title` and `profile-update-interval` both ways:
  the header value was the one stored *and* the one applied (the group took the
  header's name), and the body lines were stripped from the config text.

What remains unverified is only what a provider that emits *both* intends by
it, since no such provider is known in the target set. Treat this as settled for
Remnawave and as a documented, tested choice elsewhere — not as an upstream fact.

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

  **As of M6 this is true rather than aspirational, for exactly one key.**
  `routing` is the registry's first `Danger.Dangerous` entry with a real
  consumer, and its confirmation is `:feature:routing`'s import review
  sheet. Before anything is written or downloaded, the sheet names the
  profile, says whether it replaces an existing one, gives the entry count
  per bucket and the resulting default route in words, lists the **host**
  of every geo file it would fetch and its size, notes any DNS block that
  is stored but not applied, and says whether it will become active.
  Confirm is deliberately not the default-focused action.

  The sheet is the single funnel for every channel — deeplink, clipboard,
  QR, response header, body line. The two the user initiated are not
  exempt: consenting to tap a link is not consenting to an opaque base64
  blob's contents. `routing-enable: false` passes through it too, since it
  silently disables rules the user chose.

  Two gates sit in front of the sheet, in this order: an unchanged content
  fingerprint is a silent no-op (a subscription re-delivers its `routing`
  header hourly, and a sheet seen hourly is a sheet nobody reads), and only
  then is `LastUpdated` monotonicity checked. Both live in
  `RoutingRepository.decideFor`, so "would this change anything" has one
  definition rather than one per caller.

---

## A.2 Tier 1 — table stakes (MVP)

- [ ] Protocols: VLESS (REALITY, XTLS Vision), VMess, Trojan, Shadowsocks,
      SOCKS5, Hysteria2 — all native to Xray-core now (see §6)
- [ ] Import: manual entry, clipboard, QR camera scan, file, URL
- [ ] Subscription import with auto-update on an interval, plus update on
      app launch
- [ ] Raw JSON config profiles (passthrough mode — §6. An eligible config runs
      as written, own `routing`/`dns` and all, when the app has no routing or
      DNS opinion of its own; the moment a rule set is active or a resolver is
      configured, the app's routing and DNS blocks replace the config's
      wholesale instead, in the same way §5.2 already does for a generated
      config. M7 built this (tasks 1–16, plus a controller-added 7b, on
      `feat/m7-raw-json-passthrough`); left unchecked because the §11 device
      checklist has not run — §10.1)
- [ ] Multi-subscription, multi-profile management, grouping, collapse/expand
- [x] Latency testing with selectable mode: **`tcp` and `proxy-head`**, and a
      configurable check URL.

      Two modes, not four, and both exclusions are load-bearing. **`icmp` is not
      implementable on unrooted Android** — raw sockets require root; M4 recorded
      that and M4.5 kept it. **`proxy` (GET) is not implementable against libXray
      v26.7.11**: `nodep.MeasureDelay` hardcodes `http.NewRequest("HEAD", …)`, and
      `xray.Ping` closes its throwaway instance via `defer` before a caller could
      borrow its SOCKS port to issue a GET itself. M4.5 therefore maps an incoming
      `ping-type: proxy` onto `proxy-head` and labels it "Proxy (HEAD)" in the UI,
      so the alias is visible rather than silent. Revisit if libXray ever
      parameterises the method — source and reasoning in
      `docs/agent/research/2026-08-10-libxray-ping-semantics.md`.
- [x] Server sorting: as-delivered, by ping, alphabetical (plus last-used).
      Resolved **per group**, not per screen: `subscriptions-sort-type` is scoped
      to the subscription that delivered it (§A.1), so a single screen-wide order
      would let one provider rearrange another provider's rows. Precedence is
      user override, then provider, then the screen default, and a group ordered
      by its provider says so on the card.
- [x] Rule-based routing: geoip/geosite, domain, IP; direct/proxy/block sets
- [x] Per-app proxy: off / include-list / bypass-list

      Verified on a Pixel 8 / Android 17, 2026-08-20. Both list modes were
      observed **from the platform's own VPN UID ranges**, not from an app's
      reported exit IP: a deny-list left the selected package and this app
      outside the tunnel with everything else inside, and an allow-list produced
      the exact inverse — `Uids: <{10225-10225, 20225-20225}>`, only the listed
      app inside. §8's own-package rule holds in all three modes, including
      allow-list, where nothing calls `addDisallowedApplication` at all and the
      exclusion is structural.

      Ten of the eleven checklist items passed. **One was not reachable** and is
      recorded as such rather than skipped: saving while the tunnel is still
      `Connecting` cannot be driven by hand, because connect completes in about
      half a second on the test subscription. Its logic is covered by a JVM test,
      and the ViewModel no longer has a `Connected`-only gate — the service
      decides, and it already accepts every `Connecting` stage.

      One pre-existing defect surfaced during verification and is **not** fixed
      here: `android.util.Log` output from the `:bg` process never reaches
      logcat, which silently disables every diagnostic in `TunnelService`,
      including §5.1's protect-failure line. That line is the only signal of the
      §5.1 failure mode, because libXray discards the protect result. It predates
      this milestone. Full evidence:
      `docs/agent/research/2026-08-19-m5.5-device-verification.md`.
- [ ] Traffic counters, live log viewer
- [ ] Always-on VPN, boot autostart, kill switch
- [ ] Material 3, light/dark, RU + EN localization

## A.3 Tier 2 — the actual differentiators

### A.3.1 Routing profiles distributed as deeplinks

**Status: done for rules in M6, verified on hardware 2026-08-22** (Pixel 8 /
Android 17). The fourteen-item checklist in
`docs/agent/specs/2026-08-20-m6-routing-profile-deeplinks-design.md` §11 is
recorded in `docs/agent/research/2026-08-20-m6-device-verification.md`,
including both mandatory exit criteria. Item 14's QR half and three optional
regression rows were unreachable and are explicitly recorded as not run.
The run cost three product defects, none of them visible to a green build —
§10.1 again.

**The DNS half is done in M6.5, verified on hardware 2026-08-24** (Pixel 8 /
Android 17). Ten of the eleven rows in
`docs/agent/specs/2026-08-23-m6.5-routing-profile-dns-design.md` §10 ran on
both Wi-Fi and mobile data, and both mandatory exit criteria — no query
reaching the ISP's resolver on either network, and a demonstrated
domestic/remote split — are recorded in
`docs/agent/research/2026-08-24-m6.5-device-verification.md`. Row 7 (FakeDNS
with sniffing off) is unreachable in this build, because sniffing has no user
setting, and is recorded as not run rather than as a pass. The run cost one
product defect, again invisible to a green build. Raw Xray JSON passthrough
is M7 — built (§6), device checklist not yet run.

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
`"block-proxy-direct"`) and `UseChunkFiles`. Neither is in the published
schema example, and M6 resolved them differently — see
`docs/agent/research/2026-08-20-happ-routing-profiles.md` for sources:

- **`RouteOrder` is honoured.** It is real and it is self-describing:
  `"block-proxy-direct"` states its own meaning, the order the three
  outcome buckets are evaluated in, and getting it wrong is visible
  (rules stop matching in the order the profile asked for). It maps
  directly onto the evaluation order `RoutingRuleSet` already carries.
- **`UseChunkFiles` is stored and ignored.** It is real — Remnawave's own
  HAPP Routing Builder emits it — but it is documented nowhere, and §10.5
  forbids guessing at upstream behaviour. Acting on a flag whose meaning
  we inferred would be exactly the failure that section is about. It is
  round-tripped so a future milestone can honour it without a migration,
  and its presence is logged by key alone.

**Booleans in this format are JSON strings** (`"true"` / `"false"`), not
JSON booleans. A parser that reads them with a strict boolean decoder
silently drops every one of them.

M6 implements this surface for rules; the DNS half (`RemoteDNS*`,
`DomesticDNS*`, `DnsHosts`, `FakeDNS`) was parsed and stored from day one and
is **applied as of M6.5**. A profile's valid DNS block wins wholesale over the
app-level setting; a block this client cannot use is rejected whole rather
than half-applied, and the row then says *"DNS block not understood — using
your DNS setting"* while the profile's routing rules still apply.

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
header on the subscription request. Without it the user simply cannot add or
refresh the subscription — there is no graceful degradation, and this is not an
optional nicety.

What the refusal *looks like* is covered below, and it is not what this
paragraph originally claimed. Remnawave does **not** answer with a 404: it
returns an ordinary **200** carrying an empty body and the marker headers.
Believing the 404 story cost M4 a defect in shipped code — see "Neither
condition arrives as an error status" further down this section, which is the
authoritative version.

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
      device. The panel accepts only `/^[a-zA-Z0-9=-]{10,64}$/`: standard
      base64's `+` and `/`, and base64url's `_`, are therefore not valid wire
      encodings even though they are common hash renderings.
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
| `x-hwid-limit` | **Not a failure signal.** A fixed v2RayTun compatibility marker, set whenever HWID enforcement is engaged — including on successful responses |

`x-hwid-not-supported` and `x-hwid-max-devices-reached` are the only two
signals; the panel makes them mutually exclusive. `x-hwid-limit` must never be
read as "limit reached" despite its name: on one of the panel's two response
paths that assignment sits outside the not-allowed branch, so it rides along on
ordinary successes. M4 shipped a classifier that treated it as a failure and
turned every fetch from such a panel into a spurious "device limit reached";
this table's earlier "duplicate of the above" wording is what it was written
against.

**Neither condition arrives as an error status.** The panel answers a refused
fetch with an ordinary `200` — empty body, or a fallback-remarks template when
`isShowCustomRemarks` is on — plus the marker headers. Classify on the headers
alone, never on the status. M4's first implementation keyed the HWID case off
`404`, which made it unreachable in production; the device run caught it. §10.5
applies to this whole table: it is now checked against the panel source
(`subscription.service.ts`, `checkHwidDeviceLimit`), not inferred.

"Device limit reached — remove a device in your account" and "this
subscription requires HWID, enable it in settings" are different problems
with different fixes. A refusal with no explanation is the worst outcome and is
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

  **Correction, 2026-08-18: this URL is dead, and the documentation is not.**
  `happ.su/main/dev-docs` now 301s to `happ.info`, which 404s — a bare "the
  docs are gone" reading of that chain is wrong and would send the next agent
  looking for a replacement source that already exists. The RU path under
  `www.happ.su` still serves the same content:
  `https://www.happ.su/main/ru/dev-docs/app-management` was the working
  source for §8's directive semantics, confirmed reachable on 2026-08-18.
  Prefer that host for any future fetch from this documentation set, and
  re-verify the `happ.su`/`happ.info` chain before concluding it has
  recovered.
- **INCY** (`https://incy.gitbook.io/docs/docs-en/app-management.en`) — a
  Happ-compatible client's documentation of the same directive surface.
  Useful as a cross-check, but its key set **diverges** from Happ's: it
  documents a separate `per-app-proxy-enable` key with no Happ equivalent,
  and uses mode value `proxy` where Happ's `per-app-proxy-mode` says `on`.
  Where the two disagree, **Happ is authoritative** — `DirectiveRegistry` was
  built against Happ, and Remnawave (the panel most target providers run)
  emits Happ's header set, not INCY's. Accessed 2026-08-18.
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
and `heiher/sockstun` are MIT and may be adapted with attribution; `v2rayNG`,
`LibreXrayVPN` and `OneXray` are GPL-3.0 and must be read for behaviour only.

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
| **OneXray** (`OneXray/OneXray`, GPL-3.0, read-only) | Runs a hand-written Xray config by replacing its `inbounds` outright rather than adopting or merging them — the same resolution M7 reached independently for the same §10.6 port constraint, and pins its geo asset directory into the invoke `env` object the same way. Corroboration, not the source of the design; read for behaviour only. Commit `7ce3f4ec81ed2944d0cf3a0c027e918a4da6841e`, read 2026-08-25. |

## C.3 Architecture and UI

| Project | Take |
|---|---|
| **LibreXrayVPN** (`Ko4Learner`) | Compose + Hilt + MVI + Clean Architecture on Xray. Small enough to read fully in an evening. Stale (late 2025) — use as a skeleton, not a dependency. |
| **Lust** (`envywook`) | Compose + subscriptions + HEV tun2socks. Very new, unproven. Worth a look for how they bridged hev via JNI. |
| **OneXray** (`OneXray/OneXray`, GPL-3.0, read-only) | Flutter/Dart, cross-platform (iOS/macOS/Android/Windows/Linux) on libXray + Xray-core. Its app-level routing override (`XrayRoutingModeFix`) is a mode switch that *deletes* a raw config's `routing`/`dns` rather than merging into them — read for how a shipped client frames that same precedence decision, not for code. |

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

`docs/agent/research/2026-08-25-remnawave-xray-json-and-balancers.md` sources
the `XRAY_JSON` template's array-of-configs shape from the panel's own
generator rather than from an observation on the wire, and records its
`routing.balancers` ("auto/best server") shape and its per-outbound tagging
schemes (`tagPrefix`, `useHostRemarkAsTag`) that M7's eligibility checks (§6)
are written against.

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
