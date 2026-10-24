package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.BiomeDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.FeatureDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.FeatureRuleDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.PackBiomes;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.fabricmc.fabric.api.biome.v1.NetherBiomes;
import net.fabricmc.fabric.api.biome.v1.TheEndBiomes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.List;
import java.util.function.Predicate;

/**
 * Where the worldgen a behaviour pack writes ends up once the pack is converted.
 *
 * The entries themselves are files in the converted data pack. What no file can say is which biomes
 * a feature rule reaches, because a Java biome lists the features it carries and nothing a data pack
 * writes adds one from the other side. Fabric's biome modification API answers exactly that: a
 * predicate over every biome the game builds, and a callback that writes into the one it matched. A
 * rule's Bedrock biome filter becomes that predicate, which is the same shape a spawn rule already
 * takes here.
 *
 * A biome a pack adds reaches the nether and the end through calls of the same kind, each of which
 * puts one more entry in the list that world already searches. The overworld has no such call and no
 * such list, so an overworld biome is registered and then reported.
 */
public final class AddonWorldgen {
    /** The weight an end biome is offered at, which Bedrock states nowhere. */
    private static final double END_WEIGHT = 1.0;

    private AddonWorldgen() {
    }

    /**
     * Reads the worldgen of every add-on again, to report it and to place what it declares.
     *
     * The converted packs are what is walked rather than the Bedrock packs, because the namespace a
     * pack's own content was written under is decided by the conversion, and a registry key that
     * named a different one would point at no file.
     */
    public static void register(List<ConvertedPack> packs) {
        int biomes = 0;
        int rules = 0;

        // Every pack's biomes are named before any rule is read, because a rule may select on a tag
        // only a biome of another pack carries.
        if (Platform.convertsWorldgen()) packs.forEach(AddonWorldgen::declareBiomes);

        for (ConvertedPack converted : packs) {
            for (BedrockPack pack : converted.behaviours()) {
                List<BiomeDefinition> declared = BiomeDefinition.readAll(pack);
                List<FeatureDefinition> features = FeatureDefinition.readAll(pack);
                List<FeatureRuleDefinition> attached = FeatureRuleDefinition.readAll(pack);

                if (!Platform.convertsWorldgen()) {
                    tooNew("biomes", declared.size());
                    tooNew("features", features.size());
                    tooNew("feature_rules", attached.size());
                    continue;
                }

                declared.forEach(BiomeDefinition::report);
                features.forEach(FeatureDefinition::report);
                declared.forEach(biome -> place(biome, converted.namespace()));
                attached.forEach(rule -> attach(rule, converted.namespace()));
                biomes += declared.size();
                rules += attached.size();
            }
        }

        if (biomes > 0 || rules > 0) {
            McAddon.LOGGER.info("Added {} biomes and {} feature rules from add-ons.", biomes, rules);
        }
    }

    private static void declareBiomes(ConvertedPack converted) {
        for (BedrockPack pack : converted.behaviours()) {
            for (BiomeDefinition biome : BiomeDefinition.readAll(pack)) {
                Names.Identifier id = Names.split(biome.identifier(), converted.namespace());
                PackBiomes.declare(id.namespace() + ":" + id.path(), biome.tags());
            }
        }
    }

    /**
     * Names a directory this version will not read, with the version that would.
     *
     * 1.20.1 marks every worldgen entry from a pack of its own as experimental and then asks the
     * player whether to open the world at all, every time. A pack shipping worldgen is a pack too
     * new for that version, and saying so is better than a question the player cannot settle.
     */
    private static void tooNew(String directory, int count) {
        for (int index = 0; index < count; index++) {
            AddonEntities.unsupported("behaviour pack " + directory + " (needs 1.21 or newer)");
        }
    }

    /**
     * Puts one biome where its own tags say it belongs.
     *
     * The nether and the end each take a biome through a call of its own, which adds the biome to
     * the list that world already searches and leaves everything else in it alone. The overworld has
     * no such call. The only way to put a biome into its search is to state the whole of the
     * overworld's own generator in a `dimension` entry, which replaces the noise settings a world
     * was created with as well, so a world made amplified or large biomes would read the default
     * ones instead for as long as the pack is installed. That is a worse thing to do to somebody's
     * world than to say the biome is not placed, so it is said instead.
     */
    private static void place(BiomeDefinition biome, String namespace) {
        Names.Identifier id = Names.split(biome.identifier(), namespace);
        ResourceKey<Biome> key =
            ResourceKey.create(Registries.BIOME, Platform.id(id.namespace(), id.path()));
        float[][] bands = biome.climate();

        switch (biome.world()) {
            case "nether" -> NetherBiomes.addNetherBiome(key, Climate.parameters(
                band(bands[0]), band(bands[1]), band(bands[2]), band(bands[3]),
                band(bands[4]), band(bands[5]), bands[6][0]));
            case "end" -> TheEndBiomes.addHighlandsBiome(key, END_WEIGHT);
            default -> AddonEntities.unsupported(
                "biome in the overworld, which is read and registered but never generated");
        }
    }

    private static Climate.Parameter band(float[] bounds) {
        return Climate.Parameter.span(bounds[0], bounds[1]);
    }

    /**
     * Hangs one rule's placed feature on every biome its filter accepts.
     *
     * The filter is Bedrock's own, answered against each biome's Java id, and the pass the rule
     * names decides which of Java's eleven decoration steps the feature runs in.
     */
    private static void attach(FeatureRuleDefinition rule, String namespace) {
        Names.Identifier id = Names.split(rule.identifier(), namespace);
        ResourceKey<PlacedFeature> feature = ResourceKey.create(Registries.PLACED_FEATURE,
            Platform.id(id.namespace(), id.path()));
        GenerationStep.Decoration step = step(rule.decorationStep());

        BiomeModifications.create(McAddon.id("feature_" + id.namespace() + "_" + id.path()))
            .add(ModificationPhase.ADDITIONS, selector(rule, namespace),
                context -> context.getGenerationSettings().addFeature(step, feature));
    }

    private static GenerationStep.Decoration step(String named) {
        for (GenerationStep.Decoration step : GenerationStep.Decoration.values()) {
            if (step.name().equals(named)) return step;
        }
        return GenerationStep.Decoration.VEGETAL_DECORATION;
    }

    /**
     * The biomes one rule reaches: one of Java's when it lies in the world the filter names and its
     * id matches, and a pack's own when the tags its pack gave it do. None while the rule's add-on is
     * not active, because Fabric asks again for every server and a world without the add-on has no
     * placed feature to add.
     */
    private static Predicate<BiomeSelectionContext> selector(FeatureRuleDefinition rule, String namespace) {
        Predicate<BiomeSelectionContext> world = switch (rule.world()) {
            case "nether" -> BiomeSelectors.foundInTheNether();
            case "end" -> BiomeSelectors.foundInTheEnd();
            default -> BiomeSelectors.foundInOverworld();
        };
        return context -> ActiveAddons.isActive(namespace)
            && rule.biomes().allows(Platform.idOf(context.getBiomeKey()).toString(), () -> world.test(context));
    }
}
