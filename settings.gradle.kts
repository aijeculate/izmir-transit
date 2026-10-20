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

rootProject.name = "izmir-transit"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":core:model")
include(":core:database")
include(":core:data")
include(":core:ui")
include(":core:location")
include(":feature:linemap")
include(":feature:stop")
include(":feature:home")
include(":feature:timetable")
include(":feature:settings")
include(":app")
include(":tools:importer")
