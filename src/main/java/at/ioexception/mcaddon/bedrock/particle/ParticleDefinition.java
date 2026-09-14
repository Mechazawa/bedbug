package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.animation.Vector3Expression;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One `particles/*.json`, which is how a pack describes an effect of its own.
 *
 * A file names the material and texture the effect is drawn with, and then two sets of components:
 * the emitter's, which say where and how often a particle appears, and the particle's, which say
 * how one behaves once it exists. A component the pack leaves out is null here. A field left out of
 * a component the pack does declare carries the value Bedrock gives it, so every expression in a
 * component that exists can be evaluated without a null check.
 *
 * Alongside the components a file may carry curves, which turn a Molang input into a Molang
 * variable any component can then read, and events, which the components ask for by name.
 */
public record ParticleDefinition(String identifier, String material, String texture, Emitter emitter,
    Particle particle, Map<String, ParticleCurve> curves, Map<String, ParticleEvent> events) {

    /** Every component name this reader acts on. A file naming another one is reported. */
    private static final Set<String> READ = Set.of(
        "minecraft:emitter_initialization", "minecraft:emitter_lifetime_events",
        "minecraft:emitter_lifetime_expression", "minecraft:emitter_lifetime_looping",
        "minecraft:emitter_lifetime_once", "minecraft:emitter_local_space",
        "minecraft:emitter_rate_instant", "minecraft:emitter_rate_manual",
        "minecraft:emitter_rate_steady", "minecraft:emitter_shape_box",
        "minecraft:emitter_shape_custom", "minecraft:emitter_shape_disc",
        "minecraft:emitter_shape_entity_aabb", "minecraft:emitter_shape_point",
        "minecraft:emitter_shape_sphere", "minecraft:particle_appearance_billboard",
        "minecraft:particle_appearance_lighting", "minecraft:particle_appearance_tinting",
        "minecraft:particle_expire_if_in_blocks", "minecraft:particle_expire_if_not_in_blocks",
        "minecraft:particle_expire_not_in_blocks", "minecraft:particle_initial_speed",
        "minecraft:particle_initial_spin", "minecraft:particle_initialization",
        "minecraft:particle_kill_plane", "minecraft:particle_lifetime_events",
        "minecraft:particle_lifetime_expression", "minecraft:particle_motion_collision",
        "minecraft:particle_motion_dynamic", "minecraft:particle_motion_parametric");

    public record Emitter(Initialization initialization, LocalSpace localSpace, EmitterRate rate,
        EmitterLifetime lifetime, EmitterShape shape, ParticleEvent.Lifecycle events) {

        /** Molang the emitter runs for its variables: once when it is created, then every update. */
        public record Initialization(MolangExpression creation, MolangExpression perUpdate) {
        }

        /** Which of the emitting entity's movements the particles follow instead of being left behind. */
        public record LocalSpace(boolean position, boolean rotation, boolean velocity) {
        }

        static Emitter read(JsonObject components) {
            JsonObject initialization =
                ParticleJson.object(components, "minecraft:emitter_initialization");
            JsonObject localSpace = ParticleJson.object(components, "minecraft:emitter_local_space");

            return new Emitter(
                initialization == null ? null : new Initialization(
                    ParticleJson.expression(initialization, "creation_expression", 0),
                    ParticleJson.expression(initialization, "per_update_expression", 0)),
                localSpace == null ? null : new LocalSpace(
                    ParticleJson.flag(localSpace, "position", false),
                    ParticleJson.flag(localSpace, "rotation", false),
                    ParticleJson.flag(localSpace, "velocity", false)),
                EmitterRate.read(components),
                EmitterLifetime.read(components),
                EmitterShape.read(components),
                ParticleEvent.lifecycle(
                    ParticleJson.object(components, "minecraft:emitter_lifetime_events")));
        }
    }

    public record Particle(InitialSpeed initialSpeed, Spin spin, Lifetime lifetime, Dynamic dynamic,
        Parametric parametric, Collision collision, Billboard billboard, ParticleTint tint,
        List<String> expireInBlocks, List<String> expireNotInBlocks, KillPlane killPlane,
        Initialization initialization, boolean lit, ParticleEvent.Lifecycle events) {

        public record Spin(MolangExpression rotation, MolangExpression rotationRate) {
        }

        /** `expiration` ends a particle before its time is up, and zero leaves it to live it out. */
        public record Lifetime(MolangExpression maxLifetime, MolangExpression expiration) {
        }

        /** Molang the particle runs for its variables: every update, and again every frame drawn. */
        public record Initialization(MolangExpression perRender, MolangExpression perUpdate) {
        }

        /** Motion the particle works out for itself, from an acceleration and a drag. */
        public record Dynamic(Vector3Expression linearAcceleration, MolangExpression linearDrag,
            MolangExpression rotationAcceleration, MolangExpression rotationDrag) {
        }

        /** Motion the pack states outright, as a position and rotation for each moment. */
        public record Parametric(Vector3Expression relativePosition, Vector3Expression direction,
            MolangExpression rotation) {
        }

        public record Collision(MolangExpression enabled, MolangExpression drag,
            MolangExpression restitution, MolangExpression radius, boolean expireOnContact,
            List<Impact> events) {
        }

        /** An event a collision raises, once the particle was moving at least that fast into it. */
        public record Impact(String event, float minimumSpeed) {
        }

        /** The plane `a * x + b * y + c * z + d = 0`, which kills whatever crosses it. */
        public record KillPlane(float a, float b, float c, float d) {
        }

        static Particle read(JsonObject components) {
            JsonObject spin = ParticleJson.object(components, "minecraft:particle_initial_spin");
            JsonObject lifetime =
                ParticleJson.object(components, "minecraft:particle_lifetime_expression");
            JsonObject start =
                ParticleJson.object(components, "minecraft:particle_initialization");

            return new Particle(
                InitialSpeed.read(components.get("minecraft:particle_initial_speed")),
                spin == null ? null : new Spin(ParticleJson.expression(spin, "rotation", 0),
                    ParticleJson.expression(spin, "rotation_rate", 0)),
                lifetime == null ? null : new Lifetime(
                    ParticleJson.expression(lifetime, "max_lifetime", 0),
                    ParticleJson.expression(lifetime, "expiration_expression", 0)),
                readDynamic(components),
                readParametric(components),
                readCollision(components),
                Billboard.read(components),
                ParticleTint.read(components),
                blocks(components, "minecraft:particle_expire_if_in_blocks"),
                expireNotInBlocks(components),
                readKillPlane(components),
                start == null ? null : new Initialization(
                    ParticleJson.expression(start, "per_render_expression", 0),
                    ParticleJson.expression(start, "per_update_expression", 0)),
                components.has("minecraft:particle_appearance_lighting"),
                ParticleEvent.lifecycle(
                    ParticleJson.object(components, "minecraft:particle_lifetime_events")));
        }

        private static Dynamic readDynamic(JsonObject components) {
            JsonObject json = ParticleJson.object(components, "minecraft:particle_motion_dynamic");
            if (json == null) return null;

            return new Dynamic(ParticleJson.vector(json, "linear_acceleration"),
                ParticleJson.expression(json, "linear_drag_coefficient", 0),
                ParticleJson.expression(json, "rotation_acceleration", 0),
                ParticleJson.expression(json, "rotation_drag_coefficient", 0));
        }

        private static Parametric readParametric(JsonObject components) {
            JsonObject json = ParticleJson.object(components, "minecraft:particle_motion_parametric");
            if (json == null) return null;

            return new Parametric(ParticleJson.vector(json, "relative_position"),
                ParticleJson.vector(json, "direction"),
                ParticleJson.expression(json, "rotation", 0));
        }

        private static Collision readCollision(JsonObject components) {
            JsonObject json = ParticleJson.object(components, "minecraft:particle_motion_collision");
            if (json == null) return null;

            return new Collision(ParticleJson.expression(json, "enabled", 1),
                ParticleJson.expression(json, "collision_drag", 0),
                ParticleJson.expression(json, "coefficient_of_restitution", 0),
                ParticleJson.expression(json, "collision_radius", 0),
                ParticleJson.flag(json, "expire_on_contact", false),
                impacts(json.get("events")));
        }

        private static List<Impact> impacts(JsonElement value) {
            if (value == null || !value.isJsonArray()) return List.of();

            List<Impact> impacts = new ArrayList<>();
            value.getAsJsonArray().forEach(node -> {
                if (!node.isJsonObject()) return;

                JsonObject json = node.getAsJsonObject();
                String event = Json.text(json, "event", null);
                if (event != null) {
                    impacts.add(new Impact(event, ParticleJson.number(json, "min_speed", 0f)));
                }
            });
            return List.copyOf(impacts);
        }

        private static KillPlane readKillPlane(JsonObject components) {
            List<JsonElement> plane = Json.list(components, "minecraft:particle_kill_plane");
            if (plane.size() != 4) return null;

            return new KillPlane(coefficient(plane.get(0)), coefficient(plane.get(1)),
                coefficient(plane.get(2)), coefficient(plane.get(3)));
        }

        private static float coefficient(JsonElement value) {
            boolean number = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            return number ? value.getAsFloat() : 0;
        }

        /** Packs are written with either the name the schema gives this or the shorter one. */
        private static List<String> expireNotInBlocks(JsonObject components) {
            List<String> named = blocks(components, "minecraft:particle_expire_if_not_in_blocks");
            return named.isEmpty() ? blocks(components, "minecraft:particle_expire_not_in_blocks") : named;
        }

        private static List<String> blocks(JsonObject components, String key) {
            return Json.list(components, key).stream()
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString)
                .toList();
        }
    }

    /** The event under that name, or null when the file declares none. */
    public ParticleEvent event(String name) {
        return events.get(name);
    }

    /** Null for a file that is not a particle effect, or one this reader cannot make sense of. */
    public static ParticleDefinition tryFrom(Path file) {
        try {
            JsonObject effect = ParticleJson.object(Json.parseObject(file), "particle_effect");
            JsonObject description = ParticleJson.object(effect, "description");
            String identifier = description == null ? null : Json.text(description, "identifier", null);
            if (identifier == null) return null;

            JsonObject render = ParticleJson.object(description, "basic_render_parameters");
            if (render == null) render = new JsonObject();

            JsonObject components = ParticleJson.object(effect, "components");
            if (components == null) components = new JsonObject();
            components.keySet().stream().filter(name -> !READ.contains(name))
                .forEach(name -> AddonEntities.unsupported("particle component " + name));

            return new ParticleDefinition(identifier,
                Json.text(render, "material", null),
                Json.text(render, "texture", null),
                Emitter.read(components),
                Particle.read(components),
                ParticleCurve.readAll(ParticleJson.object(effect, "curves")),
                ParticleEvent.readAll(ParticleJson.object(effect, "events")));
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            return null;
        }
    }

    /** Every effect a pack ships, by the identifier a `.mcfunction`, an event or a script names. */
    public static Map<String, ParticleDefinition> readAll(BedrockPack pack) {
        Map<String, ParticleDefinition> effects = new LinkedHashMap<>();
        for (Path file : pack.list("particles", ".json")) {
            ParticleDefinition effect = tryFrom(file);
            if (effect != null) effects.put(effect.identifier(), effect);
        }
        return effects;
    }
}
