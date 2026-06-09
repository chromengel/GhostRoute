// Standalone JVM tool (NOT part of the Android app build) that imports the OSM
// extract into a GraphHopper graph using the exact graphhopper-core version the
// app loads. Kept separate so the Android build stays clean.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "graphtool"
