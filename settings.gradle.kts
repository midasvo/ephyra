enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "findroid"

include(":app:phone")
include(":app:tv")
include(":core")
include(":data")
include(":player:cast")
include(":player:core")
include(":player:local")
include(":setup")
include(":modes:film")
include(":settings")
include(":baselineprofile")
include(":baselineprofile-tv")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}