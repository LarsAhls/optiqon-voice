// Stand-alone Gradle build for the synthetic signing-rotation package (Mission L1, B.1).
// It shares the repository's version catalog but is deliberately NOT part of the main
// build: it exists only to produce throw-away APKs for the emulator matrix.
// Build from the repo root with:  ./gradlew -p tools/rotationtest assembleRelease -ProtVersionCode=1
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "rotationtest"
include(":app")
