package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.SpawnRuleDefinition;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.resource.AddonSet;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.minecraft.world.entity.EntityType;

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

    public static void register(AddonSet installed) {
        int added = 0;
        for (BedrockPack pack : installed.packs().packs()) {
            if (pack.role() != PackRole.BEHAVIOUR) continue;
            String namespace = installed.namespaceOf(pack);
            for (SpawnRuleDefinition rule : SpawnRuleDefinition.readAll(pack)) {
                if (add(rule, namespace)) added++;
            }
        }
        if (added > 0) McAddon.LOGGER.info("Added {} natural spawns from add-ons.", added);
    }

    private static boolean add(SpawnRuleDefinition rule, String namespace) {
        EntityType<?> type = AddonEntities.typeOf(rule.identifier());
        if (type == null || rule.conditions().isEmpty()) return false;

        int index = 0;
        for (SpawnRuleDefinition.Condition condition : rule.conditions()) {
            BiomeModifications
                .create(McAddon.id("spawn_" + Math.abs(rule.identifier().hashCode()) + "_" + index++))
                .add(ModificationPhase.ADDITIONS, selector(condition, namespace), context ->
                    Platform.addSpawn(context, rule.population(), type, condition.weight(),
                        condition.minimumHerd(), condition.maximumHerd()));
        }
        CONDITIONS.put(type, rule.conditions());
        return true;
    }

    /**
     * The biomes one alternative allows.
     *
     * Java holds its three worlds apart with selectors of its own, so one of its biomes has to be in
     * the world the filter points at before its id is matched. A pack's own biome says which world
     * it belongs to among its tags. None while the rule's add-on is not active, which Fabric asks
     * again for every server.
     */
    private static Predicate<BiomeSelectionContext> selector(SpawnRuleDefinition.Condition condition,
                                                             String namespace) {
        Predicate<BiomeSelectionContext> world = switch (condition.world()) {
            case "nether" -> BiomeSelectors.foundInTheNether();
            case "end" -> BiomeSelectors.foundInTheEnd();
            default -> BiomeSelectors.foundInOverworld();
        };
        return context -> ActiveAddons.isActive(namespace)
            && condition.biomes().allows(Platform.idOf(context.getBiomeKey()).toString(), () -> world.test(context));
    }
}
