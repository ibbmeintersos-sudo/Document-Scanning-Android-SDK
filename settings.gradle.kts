pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://jitpack.io")
    }
}
rootProject.name = "DocumentScannerLibrary"
include(":DocumentScanner", ":app")
