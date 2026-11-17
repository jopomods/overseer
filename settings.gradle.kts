pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases")
    }
}

rootProject.name = "overseer"

include("core")
include("web")
include("platform:forge-1.20.1")
