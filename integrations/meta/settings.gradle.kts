pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "sight-meta-adapter"
include(":adapter")
// The adapter and phone app compile the same detector and deterministic core sources.
include(":core", ":perception")
project(":core").projectDir = file("../../core")
project(":perception").projectDir = file("../../perception")
gradle.beforeProject {
    if (path == ":core" || path == ":perception") {
        layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("isolated-build/${name}"))
    }
}
