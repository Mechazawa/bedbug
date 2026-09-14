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
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
}

/**
 * Mods this one is meant to work beside, installed only for a dev run.
 *
 * Each one reaches a part of the game this mod also touches: the renderers Sodium and Iris
 * replace, the entity, explosion, piston and chest paths Lithium rewrites, the block state storage
 * Ferrite Core repacks, the living entity renderer Entity Texture Features and Entity Model
 * Features both extend, the off-thread chunk loading C2ME introduces, and the dynamic registry
 * entries Lithostitched and Terralith write from packs of their own.
 *
 * A version that has no build of one simply leaves the property empty. None of them is compiled
 * against, so nothing here changes what the mod jar needs.
 */
fun companionMods() = listOf("sodium", "iris", "indium", "lithium", "ferrite-core",
    "entitytexturefeatures", "entity-model-features", "c2me-fabric", "lithostitched", "terralith")
    .mapNotNull { name ->
        val version = findProperty("deps.$name") as String?
        if (version.isNullOrBlank()) null else "maven.modrinth:$name:$version"
    }

dependencies {
    // No remapping means no "mod" configurations: dependencies go on the plain classpath.
    minecraft("com.mojang:minecraft:${prop("deps.minecraft")}")
    implementation("net.fabricmc:fabric-loader:${prop("deps.fabric_loader")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric_api")}")
    // Optional at runtime: the Mod Menu entrypoint is only invoked when Mod Menu is installed.
    compileOnly("com.terraformersmc:modmenu:${prop("deps.modmenu")}")
    localRuntime("com.terraformersmc:modmenu:${prop("deps.modmenu")}")
    companionMods().forEach { localRuntime(it) }
}

// The game tests live in the main source set, so there is no second one to create for them, and
// every one of them is a server test.
fabricApi.configureTests {
    createSourceSet = false
    enableClientGameTests = false
}

apply(from = rootProject.file("gradle/mod.gradle.kts"))
