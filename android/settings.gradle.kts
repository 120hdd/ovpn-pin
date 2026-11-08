// Relay for Android: a native Compose app over the Go core in mobile/core.
//
// Its own project rather than a module of mobile/app, which is the Flutter
// shell this replaces. Nothing here loads a page: every screen is Compose and
// the orb is drawn with OpenGL ES.

pluginManagement {
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

rootProject.name = "relay-android"
include(":app")
