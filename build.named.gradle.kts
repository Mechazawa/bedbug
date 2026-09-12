// From 26.x Mojang ships the jar already named, so Loom skips remapping entirely.
plugins {
    id("net.fabricmc.fabric-loom") version "1.17.20"
}

fun prop(key: String) = property(key) as String

version = "${prop("mod.version")}+${prop("deps.minecraft")}"
group = prop("mod.group")
base.archivesName = prop("mod.id")

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://maven.terraformersmc.com/releases/") { name = "TerraformersMC" }
}

dependencies {
    // No remapping means no "mod" configurations: dependencies go on the plain classpath.
    minecraft("com.mojang:minecraft:${prop("deps.minecraft")}")
    implementation("net.fabricmc:fabric-loader:${prop("deps.fabric_loader")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric_api")}")
    // Optional at runtime: the Mod Menu entrypoint is only invoked when Mod Menu is installed.
    compileOnly("com.terraformersmc:modmenu:${prop("deps.modmenu")}")
}

// The game tests live in the main source set, so there is no second one to create for them, and
// every one of them is a server test.
fabricApi.configureTests {
    createSourceSet = false
    enableClientGameTests = false
}

apply(from = rootProject.file("gradle/mod.gradle.kts"))
