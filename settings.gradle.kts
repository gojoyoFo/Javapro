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
        maven { url = uri("https://jitpack.io") }

        maven { url = uri("https://dl.startapp.com/maven2") }
        
    }
}

rootProject.name = "JavaPro"

include(":app")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
