// SPDX-License-Identifier: AGPL-3.0-or-later
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("subspace.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

extensions.configure<LibraryExtension> {
    buildFeatures {
        compose = true
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    val bom = libs.findLibrary("compose-bom").get()
    add("implementation", platform(bom))
    add("androidTestImplementation", platform(bom))
    add("implementation", libs.findLibrary("compose-ui").get())
    add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
    add("implementation", libs.findLibrary("compose-material3").get())
    add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
    add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
}
