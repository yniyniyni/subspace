// SPDX-License-Identifier: AGPL-3.0-or-later
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The vendored libXray AAR, fetched by scripts/fetch-native.sh.
        //
        // It must be a repository rather than implementation(files(...)): AGP
        // refuses to bundle a library AAR that has a direct local .aar file
        // dependency, because the dependency's classes and resources would be
        // silently dropped from the result. That breaks `./gradlew build` on
        // :core:xray, which is a milestone exit criterion.
        //
        // RepositoriesMode.FAIL_ON_PROJECT_REPOS above is why this lives here
        // and not in core/xray/build.gradle.kts.
        flatDir { dirs("core/xray/libs") }
    }
}

rootProject.name = "subspace"

include(":app")
include(":core:model")
include(":core:data")
include(":core:parser")
include(":core:xray")
include(":feature:home")
include(":feature:profiles")
include(":feature:routing")
include(":feature:settings")
include(":service")
