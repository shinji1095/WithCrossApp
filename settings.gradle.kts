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
        // ★ GStreamer 公式 Maven（AAR の配布元）
        maven("https://gstreamer.freedesktop.org/data/pkg/android/")
        // ★ Nordic は使わない（コメントアウト／削除）
        // maven("https://maven.nordicsemi.com/")
    }
}

plugins {
    id("com.android.application") version "8.8.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.dagger.hilt.android") version "2.56.2" apply false
}

rootProject.name = "WithCrossDemo"
include(":app")
