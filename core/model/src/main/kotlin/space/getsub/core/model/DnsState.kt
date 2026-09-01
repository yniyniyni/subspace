// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

/**
 * What a routing profile's DNS block does when the profile is active.
 *
 * This replaces M6's `hasUnappliedDns` marker: DNS is no longer merely stored,
 * so the UI must disclose whether the block is effective or why it is refused.
 */
public enum class DnsState {
    /** The profile carries no DNS block. */
    None,

    /** The block is valid and is applied when this profile is active. */
    Applied,

    /** The block was present but not understood, so the app-level DNS setting applies. */
    Invalid,

    /** The block requests FakeDNS, but sniffing is disabled so FakeDNS is refused. */
    NeedsSniffing,
}
