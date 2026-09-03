rootProject.name = "singular-server"

// Deliberately independent of the client build: a missing Android SDK must never
// be able to break the backend.
pluginManagement {
    repositories {
        // Swap for your internal Nexus/Artifactory mirror to make builds fully offline.
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
