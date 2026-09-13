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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Natural spawning for the entities a behaviour pack declares.
 *
 * A Bedrock rule lists alternatives and Java's biome spawn list holds only a weight and a group
 * size, so each alternative becomes an entry of its own, selected by the biomes its filter names.
 * What the entry cannot carry, the light level and the block below and everything else measured at
 * a single position, is asked of the same conditions again when the game tries a spot.
 */
public final class AddonSpawns {
    private static final Map<EntityType<?>, List<SpawnRuleDefinition.Condition>> CONDITIONS =
        new LinkedHashMap<>();

    private AddonSpawns() {
    }

    /** Empty for an entity no pack wrote a spawn rule for, which is every vanilla one. */
    public static List<SpawnRuleDefinition.Condition> conditionsOf(EntityType<?> type) {
        return CONDITIONS.getOrDefault(type, List.of());
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
        if (type == null || rule.conditions().isEmpty()) return false;

        int index = 0;
        for (SpawnRuleDefinition.Condition condition : rule.conditions()) {
            BiomeModifications
                .create(McAddon.id("spawn_" + Math.abs(rule.identifier().hashCode()) + "_" + index++))
                .add(ModificationPhase.ADDITIONS, selector(condition), context ->
                    Platform.addSpawn(context, category(rule.category()), type, condition.weight(),
                        condition.minimumHerd(), condition.maximumHerd()));
        }
        CONDITIONS.put(type, rule.conditions());
        return true;
    }

    /**
     * The biomes one alternative allows.
     *
     * The world comes first because Java holds the three of them apart with selectors of its own,
     * and the tags are then matched against the id of each biome in that world.
     */
    private static Predicate<BiomeSelectionContext> selector(SpawnRuleDefinition.Condition condition) {
        Predicate<BiomeSelectionContext> world = switch (condition.world()) {
            case "nether" -> BiomeSelectors.foundInTheNether();
            case "end" -> BiomeSelectors.foundInTheEnd();
            default -> BiomeSelectors.foundInOverworld();
        };
        return context -> world.test(context)
            && condition.biomes().matches(Platform.idOf(context.getBiomeKey()).getPath());
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
