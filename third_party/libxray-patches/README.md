# libXray patches

Patches applied on top of a pristine, commit-verified `XTLS/libXray` checkout
before building the Android AAR. The functional env patch is upstream's own
code, re-applied rather than rewritten. The separate build-tool patch pins an
upstream `@latest` lookup so the resulting toolchain is reproducible.

`scripts/build-libxray.sh` clones the pinned tag, applies every `*.patch` here
in filename order, and builds. Nothing is forked on GitHub; the patch files in
this directory *are* the fork.

---

## `0001-restore-invoke-env.patch`

**What it does.** Restores the `env` object on `LibXrayInvokeRequest` and the
`applyEnv`/`setEnvIfNotEmpty` helpers that apply it with `os.Setenv`. 26 added
lines across `invoke.go` and `invoke_model.go`.

**Why it is needed.** Without it, `geoip:` / `geosite:` / `ext:` routing rules
cannot resolve on Android at all. The chain, in full, with sources in
`docs/agent/research/2026-08-11-geo-assets-and-xray-routing.md` §2b:

1. xray-core finds geo files only via `XRAY_LOCATION_ASSET`, read through Go's
   `os.LookupEnv` (`common/platform/others.go`). Absolute `ext:` paths are
   rejected before that by `filepath.IsLocal` in `getAssetFileLocation`, so
   there is no config-level alternative.
2. Go's Android shared-library entry point (`runtime/rt0_android_arm64.s`)
   starts the runtime with a synthetic argv and an **empty envv**. A
   gomobile-built Go library therefore begins with no environment at all, and
   `android.system.Os.setenv` from Java — which writes libc's `environ` — can
   never be seen by Go, at any point, in any process. Verified on device by
   `AssetLocationProbeTest`.
3. So only a Go-side `os.Setenv` can work, and stock libXray contains none:
   zero `os.Setenv` calls anywhere in `v26.7.11`, `v26.7.28` or `main`, and no
   exported API that reaches one.

**This is upstream's own code.** It was merged as
[PR #133](https://github.com/XTLS/libXray/pull/133) ("Restore invoke env and
desktop wrapper", 2026-07-07) and removed three days later by
[PR #134](https://github.com/XTLS/libXray/pull/134) (2026-07-10) — one day
before `v26.7.11` was tagged — on the rationale *"rely on Xray-core root env
configuration for runtime settings"*, which is unachievable on Android for the
reason above. The patch restores only the env half; #134 also removed a desktop
wrapper, which this project does not want and does not restore.

`OneXray`, the Flutter Xray client, targets this same `env` object
field-for-field (`XrayEnv` in its `pigeon/Model.kt`, merged into the request by
`OneVpnService.patchRuntimeEnv`) and adopted it the same day #133 merged. This
is not a bespoke need.

### When upstream restores it — how to drop this patch

The Kotlin side is written against the **upstream** wire shape, so no app code
changes when this goes away. `XrayEnv` in `:core:xray` serialises exactly
`xray.location.asset` / `xray.location.cert` / `xray.tun.fd`, and stock libXray
silently ignores an unknown `env` key (`encoding/json` drops it), so sending it
is harmless against an unpatched build.

To return to stock, in order:

1. Bump `LIBXRAY_VERSION` in `scripts/fetch-native.sh` to the release that
   carries `env`, and update `LIBXRAY_SHA256`.
2. Delete this patch file.
3. Run `./scripts/fetch-native.sh` — it will pull the official release AAR
   again, and `scripts/build-libxray.sh` stops being needed.
4. Run `:core:xray`'s `AssetLocationProbeTest` on a device. It fails loudly if
   the env object is not honoured, which is exactly the regression to catch.

Nothing else is coupled to the patch. If upstream instead adds a *different*
mechanism (for example `datDir` back on `RunXrayRequest`, as the pre-`invoke`
API had), only `XrayEnv` and its single call site in `XrayController` change.

---

## `0002-pin-gomobile-version.patch`

**What it does.** Replaces libXray's build-time
`golang.org/x/mobile@latest` lookup with the exact pseudo-version
`v0.0.0-20260816165457-f98cc9b3c733` for both `gobind` and `gomobile`.

**Why it is separate.** The env patch must remain the 26-line upstream change
described above. A previous generated patch accidentally captured the temporary
`go.mod`/`go.sum` rewrites performed by libXray's build script, mixing unrelated
dependency upgrades into that functional change. Keeping the tool pin in its
own patch makes both concerns reviewable and stops a rebuild next week from
silently selecting a different compiler tool and transitive dependency graph.

The pin is build-only and is recorded in `THIRD_PARTY.md`. When updating it,
rebuild the AAR, run the real-libXray instrumented tests, and record the tested
version here rather than returning to `@latest`.

### Upstream tracking

File/watch an issue on `XTLS/libXray` making the Android case: `Os.setenv`
cannot reach Go because the runtime is handed an empty envv on Android, so
#134's "rely on Xray-core root env configuration" has no Android
implementation. `AssetLocationProbeTest` is a self-contained reproduction.
