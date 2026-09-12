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
}

dependencies {
    minecraft("com.mojang:minecraft:${prop("deps.minecraft")}")
    // Yarn stops at 1.21.11, so Mojang's own names are the only set spanning the whole range.
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${prop("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric_api")}")
    // Optional at runtime: the Mod Menu entrypoint is only invoked when Mod Menu is installed.
    modCompileOnly("com.terraformersmc:modmenu:${prop("deps.modmenu")}")
}

apply(from = rootProject.file("gradle/mod.gradle.kts"))
