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
rootProject.name = "OpenHamShackRemote"
include(":app")
// OpenHamShack Icom's screens, from stationflow-tv (git subtree, history
// kept): the kind and the core it is built on.
include(":core-candidates", ":kind-stationflow")
