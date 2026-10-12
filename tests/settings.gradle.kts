// The end-to-end tests are their own build, so they compile without the Android SDK: they drive the app from
// outside, through adb. Run Gradle with the app's wrapper: `../apps/android/gradlew`.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "klippelgo-tests"
// The same chain and gossip client the app uses. The peer accounts in the tests are built from it.
includeBuild("../apps/android/chain")
