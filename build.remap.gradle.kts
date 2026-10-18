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
        replace("FabricPotionBrewingBuilder", "FabricBrewingRecipeRegistryBuilder")
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
        // 1.21.4 spelled out what the animation belongs to.
        replace("ItemUseAnimation", "UseAnim")
        // 26 gave several entities a package of their own. The classes kept their names.
        replace("import net.minecraft.world.entity.animal.fox.Fox;",
            "import net.minecraft.world.entity.animal.Fox;")
        replace("import net.minecraft.world.entity.animal.panda.Panda;",
            "import net.minecraft.world.entity.animal.Panda;")
        replace("import net.minecraft.world.entity.animal.parrot.Parrot;",
            "import net.minecraft.world.entity.animal.Parrot;")
        replace("import net.minecraft.world.entity.animal.wolf.Wolf;",
            "import net.minecraft.world.entity.animal.Wolf;")
        replace("import net.minecraft.world.entity.projectile.arrow.AbstractArrow;",
            "import net.minecraft.world.entity.projectile.AbstractArrow;")
        replace("import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;",
            "import net.minecraft.world.entity.vehicle.AbstractMinecart;")
        replace("import net.minecraft.world.entity.npc.villager.Villager;",
            "import net.minecraft.world.entity.npc.Villager;")
        replace("import net.minecraft.world.entity.npc.villager.VillagerData;",
            "import net.minecraft.world.entity.npc.VillagerData;")
        replace("import net.minecraft.world.entity.npc.villager.VillagerDataHolder;",
            "import net.minecraft.world.entity.npc.VillagerDataHolder;")
        replace("import net.minecraft.world.entity.npc.villager.VillagerProfession;",
            "import net.minecraft.world.entity.npc.VillagerProfession;")
        replace("import net.minecraft.world.entity.npc.villager.VillagerType;",
            "import net.minecraft.world.entity.npc.VillagerType;")
        // 1.21.2 renamed what a particle answers with its packed sky and block light.
        replace("getLightCoords(", "getLightColor(")
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
 * Mods this one is meant to work beside, installed only for a dev run.
 *
 * Each one reaches a part of the game this mod also touches: the renderers Sodium, Iris and Indium
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
