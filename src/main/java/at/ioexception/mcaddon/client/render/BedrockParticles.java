package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a Bedrock particle id looks like in Java.
 *
 * The two editions ship many of the same effects under different names, so the pairs are listed and
 * the rest is looked up by its bare name: `minecraft:soul_particle` and `minecraft:soul` are the same
 * thing. A pair only counts once the running version actually has the Java side, which is why the
 * registry decides rather than a compiled-in field.
 */
public final class BedrockParticles {
    private static final Map<String, String> RENAMED = Map.ofEntries(
        Map.entry("basic_flame", "flame"),
        Map.entry("basic_smoke", "smoke"),
        Map.entry("basic_crit", "crit"),
        Map.entry("basic_bubble", "bubble"),
        Map.entry("water_splash", "splash"),
        Map.entry("water_wake", "fishing"),
        Map.entry("water_evaporation_actor", "cloud"),
        Map.entry("evaporation", "cloud"),
        Map.entry("villager_happy", "happy_villager"),
        Map.entry("villager_angry", "angry_villager"),
        Map.entry("huge_explosion", "explosion_emitter"),
        Map.entry("huge_explosion_lab_misc", "explosion_emitter"),
        Map.entry("large_explosion", "explosion"),
        Map.entry("critical_hit", "crit"),
        Map.entry("enchanting_table", "enchant"),
        Map.entry("mob_portal", "portal"),
        Map.entry("dragon_breath_fire", "dragon_breath"),
        Map.entry("dragon_breath_trail", "dragon_breath"),
        Map.entry("dragon_dying_explosion", "explosion_emitter"),
        Map.entry("campfire_smoke", "campfire_cosy_smoke"),
        Map.entry("campfire_tall_smoke", "campfire_signal_smoke"),
        Map.entry("endrod", "end_rod"),
        Map.entry("ink", "squid_ink"),
        Map.entry("totem", "totem_of_undying"),
        Map.entry("wax", "wax_on"),
        Map.entry("sonic_explosion", "sonic_boom"),
        Map.entry("obsidian_glow_dust", "reverse_portal"),
        Map.entry("rising_borderdust", "ash"));

    /** Bedrock names an effect after what it is, and often ends it with what kind of thing it is. */
    private static final Pattern KIND = Pattern.compile("_(particle|emitter)$");

    /** Names already looked up, including the ones nothing matched, so a miss is asked once. */
    private static final Map<String, Optional<SimpleParticleType>> RESOLVED = new HashMap<>();

    private BedrockParticles() {
    }

    /**
     * The Java effect a Bedrock id names, or null when this version has nothing to show for it.
     *
     * Only effects that carry no extra data are answered. A dust or a falling block needs a colour or
     * a block state that a particle name alone does not say, so those are reported as unimplemented
     * rather than drawn as the wrong thing.
     *
     * `minecraft:soul_particle` and `minecraft:soul` are the same effect, so the kind at the end of a
     * Bedrock name is dropped before asking. Only what survives that is worth a table entry.
     */
    public static ParticleOptions vanilla(String bedrock) {
        String name = bedrock.startsWith("minecraft:")
            ? bedrock.substring("minecraft:".length()) : bedrock;
        if (name.contains(":")) return null;

        return RESOLVED.computeIfAbsent(name.toLowerCase(Locale.ROOT), BedrockParticles::resolve)
            .orElse(null);
    }

    /**
     * The Java name for a Bedrock one: the same name without its kind, or what the table renames it.
     *
     * The rule comes first because it answers most of them, which keeps the table down to the names
     * the two editions genuinely disagree on.
     */
    private static Optional<SimpleParticleType> resolve(String bedrock) {
        String bare = KIND.matcher(bedrock).replaceAll("");

        SimpleParticleType found = lookUp(RENAMED.get(bare));
        if (found == null) found = lookUp(bare);
        if (found == null) found = lookUp(bedrock);
        return Optional.ofNullable(found);
    }

    private static SimpleParticleType lookUp(String java) {
        if (java == null || java.isEmpty()) return null;

        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE
            .getOptional(Platform.id("minecraft", java)).orElse(null);
        return type instanceof SimpleParticleType simple ? simple : null;
    }
}
