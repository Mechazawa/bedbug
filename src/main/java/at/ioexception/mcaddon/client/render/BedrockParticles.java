package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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
        Map.entry("basic_flame_particle", "flame"),
        Map.entry("basic_smoke_particle", "smoke"),
        Map.entry("basic_crit_particle", "crit"),
        Map.entry("basic_bubble_particle", "bubble"),
        Map.entry("bubble_column_up_particle", "bubble_column_up"),
        Map.entry("water_splash_particle", "splash"),
        Map.entry("water_wake_particle", "fishing"),
        Map.entry("water_evaporation_actor_emitter", "cloud"),
        Map.entry("villager_happy", "happy_villager"),
        Map.entry("villager_angry", "angry_villager"),
        Map.entry("heart_particle", "heart"),
        Map.entry("note_particle", "note"),
        Map.entry("explosion_particle", "explosion"),
        Map.entry("huge_explosion_emitter", "explosion_emitter"),
        Map.entry("huge_explosion_lab_misc_emitter", "explosion_emitter"),
        Map.entry("large_explosion", "explosion"),
        Map.entry("critical_hit_emitter", "crit"),
        Map.entry("enchanting_table_particle", "enchant"),
        Map.entry("mob_portal", "portal"),
        Map.entry("dragon_breath_fire", "dragon_breath"),
        Map.entry("dragon_breath_trail", "dragon_breath"),
        Map.entry("dragon_dying_explosion", "explosion_emitter"),
        Map.entry("campfire_smoke_particle", "campfire_cosy_smoke"),
        Map.entry("campfire_tall_smoke_particle", "campfire_signal_smoke"),
        Map.entry("lava_particle", "lava"),
        Map.entry("endrod", "end_rod"),
        Map.entry("ink_emitter", "squid_ink"),
        Map.entry("totem_particle", "totem_of_undying"),
        Map.entry("sculk_soul_particle", "sculk_soul"),
        Map.entry("snowflake_particle", "snowflake"),
        Map.entry("soul_particle", "soul"),
        Map.entry("electric_spark_particle", "electric_spark"),
        Map.entry("wax_particle", "wax_on"),
        Map.entry("glow_particle", "glow"),
        Map.entry("sonic_explosion", "sonic_boom"),
        Map.entry("obsidian_glow_dust_particle", "reverse_portal"),
        Map.entry("rising_borderdust_particle", "ash"),
        Map.entry("evaporation", "cloud"));

    private static final Map<String, SimpleParticleType> RESOLVED = new HashMap<>();

    private BedrockParticles() {
    }

    /**
     * The Java effect a Bedrock id names, or null when this version has nothing to show for it.
     *
     * Only effects that carry no extra data are answered. A dust or a falling block needs a colour or
     * a block state that a particle name alone does not say, so those are reported as unimplemented
     * rather than drawn as the wrong thing.
     */
    public static ParticleOptions vanilla(String bedrock) {
        String name = bedrock.startsWith("minecraft:")
            ? bedrock.substring("minecraft:".length()) : bedrock;
        if (name.contains(":")) return null;

        return RESOLVED.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> {
            SimpleParticleType found = lookUp(RENAMED.get(key));
            if (found != null) return found;

            found = lookUp(key);
            return found != null ? found : lookUp(key.replaceAll("_particle$|_emitter$", ""));
        });
    }

    private static SimpleParticleType lookUp(String java) {
        if (java == null || java.isEmpty()) return null;

        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE
            .getOptional(Platform.id("minecraft", java)).orElse(null);
        return type instanceof SimpleParticleType simple ? simple : null;
    }
}
