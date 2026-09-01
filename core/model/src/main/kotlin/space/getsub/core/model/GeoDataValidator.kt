// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import java.io.File

/**
 * Which of the two geo database shapes a file holds.
 *
 * [wireValue] is what libXray's `countGeoData` expects as its `geoType`. There is
 * **no way to infer this from the bytes** — both are protobuf, and feeding a
 * `GeoIPList` to the site parser fails in a way indistinguishable from
 * corruption. Every source therefore declares it (spec §4.1).
 */
public enum class GeoDataKind(
    public val wireValue: String,
) {
    DOMAIN("domain"),
    IP("ip"),
}

/** The outcome of checking a staged geo file before installing it. */
public enum class GeoValidation {
    /** Parsed as the declared kind. Safe to install. */
    Valid,

    /** Absent, empty, or unreadable. */
    Unreadable,

    /** Present and readable, but not a geo database of the declared kind. */
    NotGeoData,
}

/**
 * Checks that a downloaded `.dat` really is a geo database before it is installed.
 *
 * An interface in `:core:model` rather than a class in `:core:xray` because
 * `:core:data` orchestrates the install and **must not depend on `:core:xray`**
 * (§4). `java.io.File` is JVM rather than Android, so this module stays free of
 * Android imports.
 *
 * The implementation is `LibXrayGeoDataValidator` in `:core:xray`, bound by Hilt
 * in `:app`.
 */
public interface GeoDataValidator {
    /**
     * Validates `<datDir>/<name>.dat` as [kind].
     *
     * On success the implementation may also write `<datDir>/<name>.json` — the
     * code listing the rule editor reads. Callers must therefore pass a **staging**
     * directory, never the live one.
     */
    public suspend fun validate(
        datDir: File,
        name: String,
        kind: GeoDataKind,
    ): GeoValidation
}
