package at.ioexception.mcaddon.item;

import at.ioexception.mcaddon.bedrock.behaviour.ItemDefinition;
import at.ioexception.mcaddon.client.render.BedrockParticles;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The effects `minecraft:durability_sensor` shows as an item wears down.
 *
 * Bedrock emits them each time the item takes damage while the durability it has left has reached
 * one of the thresholds the pack listed, and among the thresholds reached the one asking for the
 * least durability decides.
 *
 * The sound is a Bedrock level sound event and the particle a Bedrock effect name, so both are
 * looked up by name the way every other pack-named sound and effect here is. A name the running
 * game has nothing under is reported by `/mcaddon entities` rather than passed over.
 */
public final class DurabilitySensor {
    /** One threshold: the durability left that reaches it, and what reaching it shows. */
    private record Threshold(int durability, String particle, String sound) {
    }

    private final List<Threshold> thresholds;

    private DurabilitySensor(List<Threshold> thresholds) {
        this.thresholds = thresholds;
    }

    /** The sensor a pack declared on this item, or null where it declared none that can fire. */
    public static DurabilitySensor tryFrom(ItemDefinition definition) {
        JsonObject sensor = definition.components().get("minecraft:durability_sensor");
        if (sensor == null) return null;

        // Bedrock states the effects on the item and counts the durability on the stack, so an item
        // the pack gave no durability has nothing for a threshold to measure against.
        if (definition.durability() <= 0) {
            AddonEntities.unsupported("minecraft:durability_sensor without durability");
            return null;
        }

        List<Threshold> thresholds = new ArrayList<>();
        for (JsonElement element : Json.list(sensor, "durability_thresholds")) {
            if (!element.isJsonObject()) continue;
            JsonObject declared = element.getAsJsonObject();

            thresholds.add(new Threshold(
                (int) Json.number(declared, "durability", 0),
                Json.text(declared, "particle_type", null),
                Json.text(declared, "sound_event", null)));
        }
        thresholds.sort(Comparator.comparingInt(Threshold::durability));
        return thresholds.isEmpty() ? null : new DurabilitySensor(List.copyOf(thresholds));
    }

    /** Shows what the wear has reached, and nothing while the item is still above every threshold. */
    public void reached(ServerLevel level, Entity holder, int remaining) {
        thresholds.stream()
            .filter(threshold -> remaining <= threshold.durability())
            .findFirst()
            .ifPresent(threshold -> show(level, holder, threshold));
    }

    private static void show(ServerLevel level, Entity holder, Threshold threshold) {
        ParticleOptions options = particle(threshold.particle());
        if (options != null) {
            level.sendParticles(options, holder.getX(), holder.getEyeY(), holder.getZ(),
                8, 0.25, 0.25, 0.25, 0);
        }

        SoundEvent event = sound(threshold.sound());
        if (event != null) {
            level.playSound(null, holder.blockPosition(), event, SoundSource.PLAYERS, 1f, 1f);
        }
    }

    /** `none` is Bedrock's own word for a threshold that shows nothing. */
    private static ParticleOptions particle(String named) {
        if (named == null || named.equals("none")) return null;

        ParticleOptions options = BedrockParticles.vanilla(named);
        if (options == null) {
            AddonEntities.unsupported("minecraft:durability_sensor particle_type " + named);
        }
        return options;
    }

    /** `undefined` is Bedrock's own word for a threshold that makes no sound. */
    private static SoundEvent sound(String named) {
        if (named == null || named.equals("undefined")) return null;

        Identifier id = Identifier.tryParse(named.contains(":") ? named : "minecraft:" + named);
        SoundEvent event = id == null ? null
            : BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
        if (event == null) {
            AddonEntities.unsupported("minecraft:durability_sensor sound_event " + named);
        }
        return event;
    }
}
