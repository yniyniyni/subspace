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
