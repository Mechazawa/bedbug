// Minecraft 1.21.11 and earlier ship an obfuscated jar, so Loom remaps against Mojang's names.
plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.17.20"
}

// The source is written against 26.x names. These are the renames Mojang made on the way there.
stonecutter {
    replacements.string(true) {
        replace("Identifier", "ResourceLocation")
        // 26 moved the render types into their own package and renamed two of them: what it calls
        // `entityCutout` is the one that does not cull, and `entityCutoutCull` is the one that does.
        // Every rule below matches a whole expression, so none of them can rewrite another's output.
        replace("import net.minecraft.client.renderer.rendertype.RenderTypes;",
            "import net.minecraft.client.renderer.RenderType;")
        replace("import net.minecraft.client.renderer.rendertype.RenderType;",
            "import net.minecraft.client.renderer.RenderType;")
        replace("RenderTypes.entityCutoutCull(", "RenderType.entityCutout(")
        replace("RenderTypes.entityCutout(", "RenderType.entityCutoutNoCull(")
        replace("RenderTypes.entityTranslucentEmissive(", "RenderType.entityTranslucentEmissive(")
        replace("RenderTypes.entityTranslucent(", "RenderType.entityTranslucent(")
        // Fabric API followed the game's own renames a version later than the game did.
        replace("clientboundPlay()", "playS2C()")
        replace("serverboundPlay()", "playC2S()")
        // Fabric's game tests carried Mojang's annotation until 26, and Mojang's structure
        // attribute has no default, so the empty one Fabric ships has to be named.
        replace("import net.fabricmc.fabric.api.gametest.v1.GameTest;",
            "import net.minecraft.gametest.framework.GameTest;")
        replace("@GameTest()", "@GameTest(template = \"fabric-gametest-api-v1:empty\")")
        // Fabric's annotation names the scene `structure`; Mojang's, which the older targets use,
        // names the same thing `template`.
        replace("@GameTest(structure = ", "@GameTest(template = ")
        // Mojang counts the same limit in ticks under a different name.
        replace("maxTicks = ", "timeoutTicks = ")
    }
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
 * Mods this one is meant to work beside, installed only for `runClient`.
 *
 * A version that has no build of one simply leaves the property empty. None of them is compiled
 * against, so nothing here changes what the mod jar needs.
 */
fun companionMods() = listOf("sodium", "iris", "indium").mapNotNull { name ->
    val version = findProperty("deps.$name") as String?
    if (version.isNullOrBlank()) null else "maven.modrinth:$name:$version"
}

dependencies {
    minecraft("com.mojang:minecraft:${prop("deps.minecraft")}")
    // Yarn stops at 1.21.11, so Mojang's own names are the only set spanning the whole range.
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${prop("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric_api")}")
    // Optional at runtime: the Mod Menu entrypoint is only invoked when Mod Menu is installed.
    modCompileOnly("com.terraformersmc:modmenu:${prop("deps.modmenu")}")
    modLocalRuntime("com.terraformersmc:modmenu:${prop("deps.modmenu")}")
    companionMods().forEach { modLocalRuntime(it) }
}

// The game tests live in the main source set, so there is no second one to create for them, and
// every one of them is a server test.
fabricApi.configureTests {
    createSourceSet = false
    enableClientGameTests = false
}

apply(from = rootProject.file("gradle/mod.gradle.kts"))
