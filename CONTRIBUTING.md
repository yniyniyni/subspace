# Contributing to Subspace

Thanks for contributing.  This is a security-sensitive VPN client: a change
can compile and still leak DNS, loop traffic into the tunnel, or leave a broken
connection state.  Read [`ARCHITECTURE.md`](ARCHITECTURE.md), especially
sections 3, 5, and 10, before changing code.  Check
[`THIRD_PARTY.md`](THIRD_PARTY.md) before adapting anything from another
project.

## Before opening a pull request

- Keep each source file’s two-line licence notice:
  `// SPDX-License-Identifier: AGPL-3.0-or-later`, followed by
  `// Additional permission: see Stores Exception in LICENSE.`
- Follow the project constraints: package root
  `art.yniyniyni.subspace`, no `androidx.datastore`, and no config-content
  logging.
- Add focused tests where a change can be tested.  A green build alone does
  not establish that the VPN works; tunnel changes also need the relevant
  device verification described in `ARCHITECTURE.md`.
- Describe the change, its tests, and any dependency or licensing impact in
  the pull request.

## License and Store distribution

Subspace is licensed under **AGPL-3.0-or-later**, with the **Stores Exception**
at the end of [`LICENSE`](LICENSE). The exception lets this project and
qualifying forks distribute through stores including, but not limited to, the
Apple App Store while preserving the AGPL’s source-code freedoms.

By submitting a contribution to this repository, you represent that you have
the right to do so and license your copyrightable contribution under
AGPL-3.0-or-later **and** grant the same Stores Exception in `LICENSE`. You
retain your copyright.
Do not submit code or assets whose license prevents that grant; keep all
required upstream notices and attribution intact.

If you maintain a fork and distribute it through a Store:

- retain the Stores Exception and make the exact Corresponding Source, including
  build scripts, publicly available at no charge;
- provide a clear source link in the store listing, the app’s legal notices,
  or both; and
- verify the store’s current terms, platform requirements, export controls,
  privacy obligations, and third-party licenses yourself.  The exception is a
  copyright permission, not a guarantee that a store will accept the app or
  that a particular distribution arrangement complies with every law.

This document is a contribution policy, not legal advice.  For a commercial
release or a jurisdiction-specific question, consult a qualified lawyer.
