// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("subspace.jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)
}

kover {
    reports {
        verify {
            rule {
                // ARCHITECTURE.md §7: near-100% expected.
                // 90 is the failing floor, not the target.
                minBound(90)
            }
        }
    }
}
