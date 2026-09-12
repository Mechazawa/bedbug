pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    // Resolves the JDKs the older targets need, so a machine without Java 17 installed still builds.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.8"
}

rootProject.name = "mcaddon"

stonecutter {
    create(rootProject) {
        for (obfuscated in listOf("1.20.1", "1.21.1")) {
            version(obfuscated, obfuscated).buildscript = "build.remap.gradle.kts"
        }
        for (named in listOf("26.1.2", "26.2")) {
            version(named, named).buildscript = "build.named.gradle.kts"
        }
        vcsVersion = "26.2"
    }
}
