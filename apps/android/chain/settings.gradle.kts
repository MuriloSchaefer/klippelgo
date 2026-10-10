// The chain client is its own build so it can be compiled and tested without the Android SDK.
// The app includes it with includeBuild("chain").
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

rootProject.name = "chain"
