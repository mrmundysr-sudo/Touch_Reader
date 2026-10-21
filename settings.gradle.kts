pluginManagement {
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
        id("com.google.devtools.ksp") version "2.0.21-1.0.28"
    }
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

rootProject.name = "TouchReader"

include(":core:document", ":core:reader", ":core:tts", ":core:audio", ":core:voicepacks", ":core:supertonic", ":core:voicestudio", ":core:voiceconvert", ":core:export")
// The Android app needs the Android SDK. `-PjvmOnly=true` lets the pure-Kotlin
// core modules be built and tested on machines without it.
if (providers.gradleProperty("jvmOnly").orNull != "true") {
    include(":app")
}
