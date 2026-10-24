pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("org.jetbrains.kotlin.")) {
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
            }
        }
    }
    repositories {
        providers.environmentVariable("MSS_RECOVERED_MAVEN").orNull?.let { recovered ->
            maven { url = uri(recovered) }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        providers.environmentVariable("MSS_RECOVERED_MAVEN").orNull?.let { recovered ->
            maven { url = uri(recovered) }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "MobileStreamingStudio"
include(":app")
include(":core-model")
include(":core-data")
include(":streaming")
