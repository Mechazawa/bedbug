package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.SpawnRuleDefinition;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.function.Predicate;

/**
 * Natural spawning for the entities a behaviour pack declares.
 *
 * Bedrock's spawn rules are far more specific than Java's biome spawn lists, so what survives is the
 * weight, the herd size and which of the three worlds the biome filter pointed at. A pack that
 * expected to spawn only on grass at night will spawn more widely than it meant to.
 */
public final class AddonSpawns {
    private AddonSpawns() {
    }

    public static void register(PackSet packs) {
        int added = 0;
        for (BedrockPack pack : packs.packs()) {
            if (pack.role() != PackRole.BEHAVIOUR) continue;
            for (SpawnRuleDefinition rule : SpawnRuleDefinition.readAll(pack)) {
                if (add(rule)) added++;
            }
        }
        if (added > 0) McAddon.LOGGER.info("Added {} natural spawns from add-ons.", added);
    }

    private static boolean add(SpawnRuleDefinition rule) {
        EntityType<?> type = AddonEntities.typeOf(rule.identifier());
        if (type == null) return false;

        BiomeModifications.create(McAddon.id("spawn_" + Math.abs(rule.identifier().hashCode())))
            .add(ModificationPhase.ADDITIONS, selector(rule.world()), context ->
                Platform.addSpawn(context, category(rule.category()), type, rule.weight(),
                    rule.minimumHerd(), rule.maximumHerd()));
        return true;
    }

    private static Predicate<BiomeSelectionContext> selector(String world) {
        return switch (world) {
            case "nether" -> BiomeSelectors.foundInTheNether();
            case "end" -> BiomeSelectors.foundInTheEnd();
            default -> BiomeSelectors.foundInOverworld();
        };
    }

    /** Bedrock's population control names line up with Java's spawn categories. */
    private static MobCategory category(String control) {
        return switch (control) {
            case "monster" -> MobCategory.MONSTER;
            case "water_animal" -> MobCategory.WATER_CREATURE;
            case "ambient" -> MobCategory.AMBIENT;
            case "pillager", "cat", "villager" -> MobCategory.CREATURE;
            default -> MobCategory.CREATURE;
        };
    }
}
