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
        val login = providers.environmentVariable("ONEUI_MAVEN_USER")
        val token = providers.environmentVariable("ONEUI_MAVEN_TOKEN")
        mapOf(
            "oneui-design" to "io.github.tribalfs",
            "sesl-androidx" to "sesl.androidx.*",
            "sesl-material-components-android" to "sesl.com.google.android.material",
        ).forEach { (repository, artifactGroups) ->
            maven {
                name = "GitHubPackages-$repository"
                url = uri("https://maven.pkg.github.com/tribalfs/$repository")
                credentials {
                    username = login.orNull
                    password = token.orNull
                }
                content { includeGroupByRegex(artifactGroups.replace(".", "\\.").replace("*", ".*")) }
            }
        }
    }
}

rootProject.name = "PikPak-Droid"
include(":app")
