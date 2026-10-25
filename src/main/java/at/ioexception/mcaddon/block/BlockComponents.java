package at.ioexception.mcaddon.block;

import at.ioexception.mcaddon.bedrock.ClientBlockDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.BlockDefinition;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.BlockEvents;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a pack's block components become on the Java side.
 *
 * Java takes most of them as block settings fixed before the registries freeze, which is what
 * {@link #settings} builds. The rest are answered while the game runs, so the block holds them and
 * reads them back.
 *
 * Most components are written either as the bare value or as an object wrapping it under a named
 * field, and a handful take `true` for the default and `false` for the opposite, so every read here
 * accepts all three shapes.
 *
 * @param collision      the area entities collide with, or null for the whole block
 * @param selection      the area the cursor selects, or null for the whole block
 * @param lightDampening what light passing through loses, or -1 to let Java work it out
 * @param craftingTitle  the title of the crafting screen this block opens, or null for no screen
 * @param catchChance    how readily fire spreads here, or -1 when the block does not burn
 * @param destroyChance  how readily fire destroys the block once it burns
 * @param redstone       the signal this block puts out, or null when it puts out none
 * @param placement      what has to be beside the block for it to stay, empty when anything does
 * @param events         the pack's own events, or null when the block names none
 * @param dynamicProperties whether a script may store values on each block of this kind separately
 * @param chestObstruction what a chest opening in the space below this block is allowed to do
 * @param support        the shape other blocks rest on, or null for the one the collision box gives
 * @param connections    which of the blocks that reach sideways may reach this one
 * @param connectionSides the horizontal sides of the block those blocks may reach it on
 * @param occlusion      the shape neighbouring faces are culled against, or null for Java's own
 * @param permutations   what the permutations make of each state, or null where they change nothing
 *                       a state is asked
 * @param traits         the states set from how the block was placed, or null where it has none
 */
public record BlockComponents(VoxelShape collision, VoxelShape selection, int lightDampening,
    Component craftingTitle, int catchChance, int destroyChance, Redstone redstone,
    List<Placement> placement, BlockEvents events,
    at.ioexception.mcaddon.bedrock.behaviour.CustomComponents scripted,
    boolean dynamicProperties, ChestObstruction chestObstruction, VoxelShape support,
    Connections connections, Set<Direction> connectionSides, VoxelShape occlusion, Permutations permutations,
    PlacementTraits traits) {

    /** The explosion resistance Java gives bedrock, which no blast reaches. */
    private static final float BLAST_PROOF = 3600000f;

    /**
     * The post of a Java fence, which is what `minecraft:support` calls a fence shape.
     *
     * Java measures a shape across the whole block and Bedrock writes the same post as six to ten
     * sixteenths on both horizontal axes.
     */
    private static final VoxelShape FENCE_POST = Shapes.box(0.375, 0, 0.375, 0.625, 1, 0.625);

    /** What `minecraft:redstone_producer` puts out, and which faces of the block carry it. */
    public record Redstone(int power, List<Direction> connected, Direction strong) {
    }

    /**
     * Which of the blocks that reach sideways `minecraft:connection_rule` lets reach this one.
     *
     * Java asks three separate questions, one for fences, one for walls and one for panes and bars,
     * and each of them first asks whether the face turned towards it is full. That shared first
     * question is where `none` is answered; the other two values are answered per asker.
     */
    public enum Connections {
        NONE, ONLY_FENCES, ALL;

        /** Bedrock's default is `all`, and so is anything written here that names no type. */
        static Connections of(String named) {
            return switch (named == null ? "" : named) {
                case "none" -> NONE;
                case "only_fences" -> ONLY_FENCES;
                default -> ALL;
            };
        }
    }

    /**
     * Whether a chest may open in the space below this block.
     *
     * Java asks the block above a chest whether it conducts redstone, which for a pack's own block
     * is whatever `minecraft:redstone_conductivity` said and has nothing to do with the lid. This
     * answers the question Bedrock asks instead, so the two components stay apart.
     */
    public enum ChestObstruction {
        ALWAYS, NEVER, SHAPE;

        /** Bedrock's default is `shape`, and so is anything written here that names no rule. */
        static ChestObstruction of(String named) {
            return switch (named == null ? "" : named) {
                case "always" -> ALWAYS;
                case "never" -> NEVER;
                default -> SHAPE;
            };
        }

        /** A `shape` rule blocks the lid where the block fills its own space, as both editions do. */
        public boolean blocks(BlockState state, BlockGetter level, BlockPos position) {
            return switch (this) {
                case ALWAYS -> true;
                case NEVER -> false;
                case SHAPE -> state.isCollisionShapeFullBlock(level, position);
            };
        }
    }

    /** One `minecraft:placement_filter` condition: the faces it may sit against, and against what. */
    public record Placement(List<Direction> faces, List<String> blocks) {
        /**
         * A face pointing up is the top of whatever the block stands on, so the neighbour to look at
         * lies the other way. An empty block list accepts anything that is not air.
         */
        private boolean holds(LevelReader level, BlockPos position) {
            return faces.stream().anyMatch(face -> {
                BlockState against = level.getBlockState(position.relative(face.getOpposite()));
                if (blocks.isEmpty()) return !against.isAir();
                String id = AddonBlocks.identifierOf(against);
                return blocks.stream().anyMatch(block -> AddonBlocks.identifierOf(block).equals(id));
            });
        }
    }

    public static BlockComponents of(BlockDefinition definition,
        Map<String, Property<?>> properties, PackShapes shapes) {
        JsonObject components = definition.components();
        boolean burns = components.has("minecraft:flammable")
            && !isFalse(components.get("minecraft:flammable"));

        return new BlockComponents(
            shape(components, "minecraft:collision_box"),
            shape(components, "minecraft:selection_box"),
            (int) number(components, "minecraft:light_dampening", "light_dampening", -1),
            craftingTitle(components),
            burns ? (int) number(components, "minecraft:flammable", "catch_chance_modifier", 5) : -1,
            (int) number(components, "minecraft:flammable", "destroy_chance_modifier", 20),
            redstone(components), placement(components),
            BlockEvents.of(definition, properties),
            at.ioexception.mcaddon.bedrock.behaviour.CustomComponents.of(components),
            flag(components, "minecraft:block_entity", "dynamic_properties"),
            ChestObstruction.of(text(components, "minecraft:chest_obstruction", "obstruction_rule")),
            support(components), connections(components), connectionSides(components),
            shapes.named(cullingShape(components)), Permutations.of(definition, properties),
            PlacementTraits.of(definition, properties));
    }

    /** The area entities collide with in this state, or null for the whole block. */
    public VoxelShape collisionAt(BlockState state) {
        return permutations == null ? collision : permutations.at(state).collision();
    }

    /** The area the cursor selects in this state, or null for the whole block. */
    public VoxelShape selectionAt(BlockState state) {
        return permutations == null ? selection : permutations.at(state).selection();
    }

    /** The hardness of this state, which a permutation's `destructible_by_mining` may change. */
    public float hardnessAt(BlockState state, float own) {
        return permutations == null ? own : permutations.at(state).hardness();
    }

    /** The shape `minecraft:geometry` culls against, which it names beside the model it draws. */
    private static String cullingShape(JsonObject components) {
        JsonElement geometry = components.get("minecraft:geometry");
        return geometry != null && geometry.isJsonObject()
            ? Json.text(geometry.getAsJsonObject(), "culling_shape", null) : null;
    }

    /**
     * Which blocks `minecraft:connection_rule` lets reach across to this one.
     *
     * `only_fences` is a separate question per asker, and the one Java asks a pane or a bar
     * carries no asker, so it is reported and the rest of the rule still decides.
     */
    private static Connections connections(JsonObject components) {
        if (!components.has("minecraft:connection_rule")) return Connections.ALL;

        Connections accepted =
            Connections.of(text(components, "minecraft:connection_rule", "accepts_connections_from"));
        if (accepted == Connections.ONLY_FENCES) {
            AddonEntities.unsupported("minecraft:connection_rule only_fences");
        }
        return accepted;
    }

    /**
     * The horizontal sides `minecraft:connection_rule` lets a fence, a wall or a pane reach this
     * block on, all four when the rule names none. A direction names the side of this block the
     * neighbour stands on, which Mojang's documentation does not spell out.
     */
    private static Set<Direction> connectionSides(JsonObject components) {
        Set<Direction> sides = EnumSet.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
        if (!components.has("minecraft:connection_rule")) return sides;

        JsonObject rule = object(components, "minecraft:connection_rule");
        if (!rule.has("enabled_directions")) return sides;

        sides.retainAll(directions(rule.get("enabled_directions")));
        return sides;
    }

    /**
     * The shape `minecraft:support` asks other blocks to rest on, or null to keep Java's own.
     *
     * A stair shape is read off the block's facing and its upper or lower half, which Bedrock
     * carries in the `minecraft:placement_direction` traits. A pack's traits become no Java state
     * here, so a block asking for one is reported and keeps the shape its collision box gives.
     */
    private static VoxelShape support(JsonObject components) {
        String shape = text(components, "minecraft:support", "shape");
        if (shape == null) return null;
        if ("fence".equals(shape)) return FENCE_POST;

        AddonEntities.unsupported("minecraft:support " + shape);
        return null;
    }

    /** Zero unless the block produces a signal and the asking neighbour touches a connected face. */
    public int signal(Direction direction) {
        if (redstone == null) return 0;
        return redstone.connected().contains(direction.getOpposite()) ? redstone.power() : 0;
    }

    /** A strongly powered face powers the block beyond it as well, which is Java's direct signal. */
    public int directSignal(Direction direction) {
        if (redstone == null || redstone.strong() == null) return 0;
        return redstone.strong() == direction.getOpposite() ? redstone.power() : 0;
    }

    public boolean canSitAt(LevelReader level, BlockPos position) {
        return placement.isEmpty() || placement.stream().anyMatch(one -> one.holds(level, position));
    }

    /** Whether `minecraft:liquid_detection` asks for the block to hold water, which Java waterlogs. */
    public static boolean waterlogged(BlockDefinition definition) {
        for (JsonElement element
            : Json.list(object(definition.components(), "minecraft:liquid_detection"), "detection_rules")) {
            if (!element.isJsonObject()) continue;
            JsonObject rule = element.getAsJsonObject();

            for (String field : List.of("on_liquid_touches", "stops_liquid_flowing_from_direction",
                "use_liquid_clipping")) {
                if (rule.has(field)) AddonEntities.unsupported("minecraft:liquid_detection " + field);
            }
            if (flag(rule, "can_contain_liquid", null)) return true;
        }
        return false;
    }

    /**
     * `minecraft:destruction_particles` asking for no particles at all, which Java has a flag for.
     *
     * The texture becomes the block model's particle texture as the pack is converted. Java takes
     * the tint from the biome, so the tint method a pack may name has nothing to change.
     */
    private static boolean breaksWithoutParticles(JsonObject components) {
        if (object(components, "minecraft:destruction_particles").has("tint_method")) {
            AddonEntities.unsupported("minecraft:destruction_particles tint_method");
        }
        return components.has("minecraft:destruction_particles")
            && number(components, "minecraft:destruction_particles", "particle_count", 100) == 0;
    }

    private static Redstone redstone(JsonObject components) {
        JsonObject producer = object(components, "minecraft:redstone_producer");
        if (!components.has("minecraft:redstone_producer")) return null;

        List<Direction> connected = directions(producer.get("connected_faces"));
        String strong = text(components, "minecraft:redstone_producer", "strongly_powered_face");
        if (producer.has("transform_relative")) {
            AddonEntities.unsupported("minecraft:redstone_producer transform_relative");
        }
        return new Redstone(
            (int) number(components, "minecraft:redstone_producer", "power", 0),
            connected.isEmpty() ? List.of(Direction.values()) : connected,
            strong == null ? null : Direction.byName(strong));
    }

    private static List<Placement> placement(JsonObject components) {
        List<Placement> conditions = new ArrayList<>();
        for (JsonElement element
            : Json.list(object(components, "minecraft:placement_filter"), "conditions")) {
            if (!element.isJsonObject()) continue;
            JsonObject condition = element.getAsJsonObject();

            List<Direction> faces = directions(condition.get("allowed_faces"));
            conditions.add(new Placement(faces.isEmpty() ? List.of(Direction.values()) : faces,
                blockFilter(condition)));
        }
        return List.copyOf(conditions);
    }

    /** A filter entry is a block name, or an object naming one; a tag names a set Java has no map for. */
    private static List<String> blockFilter(JsonObject condition) {
        List<String> names = new ArrayList<>();
        for (JsonElement element : Json.list(condition, "block_filter")) {
            if (element.isJsonPrimitive()) {
                names.add(qualified(element.getAsString()));
            } else if (element.isJsonObject() && element.getAsJsonObject().has("name")) {
                names.add(qualified(Json.text(element.getAsJsonObject(), "name", "")));
            } else {
                AddonEntities.unsupported("minecraft:placement_filter block tags");
            }
        }
        return List.copyOf(names);
    }

    private static String qualified(String name) {
        return name.indexOf(':') < 0 ? "minecraft:" + name : name;
    }

    private static List<Direction> directions(JsonElement value) {
        List<Direction> faces = new ArrayList<>();
        if (value == null) return faces;

        for (JsonElement element : value.isJsonArray() ? value.getAsJsonArray().asList() : List.of(value)) {
            Direction face = element.isJsonPrimitive() ? Direction.byName(element.getAsString()) : null;
            if (face != null) faces.add(face);
        }
        return faces;
    }

    /**
     * The block settings Java reads once, when the block is registered.
     *
     * Redstone conductivity is set whether or not the pack asked for it, because the two editions
     * disagree about the default: Java lets any full solid block carry a signal, and Bedrock lets
     * none of a pack's own blocks carry one until `redstone_conductor` says so.
     *
     * The sound is the one thing here the behaviour half does not state: Bedrock names a block's
     * sound group in the resource pack's `blocks.json`, so it arrives with the rest of what that
     * file says about the block and is absent for a block the file does not mention.
     */
    public BlockBehaviour.Properties settings(BlockDefinition definition,
        ClientBlockDefinition appearance) {
        JsonObject components = definition.components();
        int emission = lightEmission(components);
        Permutations live = permutations;
        boolean conductor = flag(components, "minecraft:redstone_conductivity", "redstone_conductor")
            || flag(components, "minecraft:redstone_consumer", "propagates_power");
        if (object(components, "minecraft:redstone_consumer").has("min_power")) {
            AddonEntities.unsupported("minecraft:redstone_consumer min_power");
        }

        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
            .strength(hardness(components), resistance(components))
            .lightLevel(state -> live == null ? emission : live.at(state).light())
            .friction(javaFriction(number(components, "minecraft:friction", "friction", 0.4f)))
            .pushReaction(pushReaction(text(components, "minecraft:movable", "movement_type")))
            .isRedstoneConductor((state, level, position) -> conductor);

        // `minecraft:breathability` of `air` puts a mob standing inside the block in breathable
        // space, which Java says by not suffocating it there. `solid` is the default in both.
        if ("air".equals(breathability(components))) {
            properties = properties.isSuffocating((state, level, position) -> false);
        }
        if (definition.transparent()) properties = properties.noOcclusion();
        if (components.has("minecraft:replaceable")) properties = properties.replaceable();
        if (components.has("minecraft:random_ticking")) properties = properties.randomTicks();
        if (breaksWithoutParticles(components)) {
            //? if >=1.21 {
            properties = properties.noTerrainParticles();
            //?} else {
            /*properties = properties.noParticlesOnBreak();
            *///?}
        }

        properties = precipitation(components, properties);

        BlockBehaviour.OffsetType offset = offsetType(components);
        if (offset != BlockBehaviour.OffsetType.NONE) properties = properties.offsetType(offset);

        SoundType sound = BlockSounds.of(soundGroup(components, appearance));
        if (sound != null) properties = properties.sound(sound);

        NoteBlockInstrument instrument = instrument(components);
        if (instrument != null) properties = properties.instrument(instrument);

        MapColor colour = mapColour(components);
        return colour == null ? properties : properties.mapColor(colour);
    }

    /**
     * The sound group the block makes its noises from.
     *
     * `minecraft:sound` names it in the behaviour pack, which is where a block states it from 1.26.20
     * on and what outranks the group the resource pack's `blocks.json` names for the same block.
     */
    private static String soundGroup(JsonObject components, ClientBlockDefinition appearance) {
        String stated = text(components, "minecraft:sound", "sound");
        if (stated != null && !stated.isEmpty()) return stated;
        return appearance == null ? null : appearance.soundGroup();
    }

    /**
     * What `minecraft:precipitation_interactions` asks rain and snow to do here.
     *
     * Java decides both from one switch: a block is in the motion-blocking heightmap or it is not,
     * and rain stops at the highest block that is. `forceSolidOn` and `forceSolidOff` set that
     * switch whatever shape the pack gave the block, and they move what else reads it, so a block
     * declaring no rule of its own keeps the answer its collision box gives.
     *
     * Snow settling on top is a second question, asked of the face below it, and the tag the
     * declaration joins is what answers it for a block whose top face is not full.
     */
    private static BlockBehaviour.Properties precipitation(JsonObject components,
        BlockBehaviour.Properties properties) {
        String behaviour = text(components, "minecraft:precipitation_interactions",
            "precipitation_behavior");
        if (behaviour == null) return properties;

        return switch (behaviour) {
            case "none" -> properties.forceSolidOff();
            case "obstruct_rain", "obstruct_rain_accumulate_snow" -> properties.forceSolidOn();
            default -> {
                // Bedrock's `snowlogging` puts snow inside the block's own space, which Java keeps
                // as a block of its own standing on top and has no second occupant for.
                AddonEntities.unsupported("minecraft:precipitation_interactions " + behaviour);
                yield properties;
            }
        };
    }

    /** The component is written either as the bare word or as an object wrapping it under `value`. */
    private static String breathability(JsonObject components) {
        String named = text(components, "minecraft:breathability", null);
        return named != null ? named : text(components, "minecraft:breathability", "value");
    }

    /** Fire spreads to and consumes this block at the rates the pack asked for. */
    public void registerFlammability(Block block) {
        if (catchChance < 0) return;
        FlammableBlockRegistry.getDefaultInstance().add(block, destroyChance, catchChance);
    }

    /**
     * Bedrock's friction and Java's read the same axis from opposite ends.
     *
     * Bedrock counts grip, from 0.0 for none up to 0.9, and Java counts slipperiness, 0.6 for
     * stone, 0.8 for a slime block and 0.989 for blue ice. Subtracting one from the other lines the
     * two defaults up, Bedrock's 0.4 against Java's 0.6, and the result is held at blue ice because
     * an entity on a block slipperier than 1.0 accelerates without bound.
     */
    public static float javaFriction(float bedrock) {
        float grip = Math.clamp(bedrock, 0f, 0.9f);
        return Math.min(1f - grip, 0.989f);
    }

    /** Java paints a map from a fixed palette, so a pack's colour becomes the nearest entry in it. */
    public static MapColor nearestMapColour(int rgb) {
        MapColor nearest = MapColor.NONE;
        int shortest = Integer.MAX_VALUE;

        for (int id = 0; id < 64; id++) {
            MapColor candidate = MapColor.byId(id);
            if (candidate == null || candidate == MapColor.NONE) continue;

            int distance = squaredDistance(rgb, candidate.col);
            if (distance >= shortest) continue;
            shortest = distance;
            nearest = candidate;
        }
        return nearest;
    }

    /**
     * One box of a `collision_box` or `selection_box`, as the shape Java collides and selects with.
     *
     * A Bedrock box is written in the block's own sixteenths, centred on the x and z axes and rising
     * from the bottom face, so `[-8, 0, -8]` names the corner Java calls the origin. A pack is
     * allowed to write a box reaching outside its own block and Java draws one, so nothing here
     * clips the result back to the cube.
     */
    public static VoxelShape box(JsonObject box) {
        double[] origin = vector(box.get("origin"), -8, 0, -8);
        double[] size = vector(box.get("size"), 16, 16, 16);

        double[] near = {(origin[0] + 8) / 16, origin[1] / 16, (origin[2] + 8) / 16};
        double[] far = {near[0] + size[0] / 16, near[1] + size[1] / 16, near[2] + size[2] / 16};
        return Shapes.box(
            Math.min(near[0], far[0]), Math.min(near[1], far[1]), Math.min(near[2], far[2]),
            Math.max(near[0], far[0]), Math.max(near[1], far[1]), Math.max(near[2], far[2]));
    }

    /** Null when the pack asked for the whole block, which is what Java does without being told. */
    static VoxelShape shape(JsonObject components, String name) {
        JsonElement component = components.get(name);
        if (component == null) return null;
        if (component.isJsonPrimitive()) return isFalse(component) ? Shapes.empty() : null;
        if (component.isJsonObject()) return box(component.getAsJsonObject());
        if (!component.isJsonArray()) return null;

        VoxelShape shape = Shapes.empty();
        for (JsonElement element : component.getAsJsonArray()) {
            if (element.isJsonObject()) shape = Shapes.or(shape, box(element.getAsJsonObject()));
        }
        return shape;
    }

    /**
     * The title of the screen a custom crafting table opens.
     *
     * Bedrock names a translation key here and shows the key itself when nothing resolves it, which
     * is what Java's translatable component does too. The crafting tags the component also carries
     * choose which recipes the table can make, and Java has one crafting grid that makes all of
     * them, so they are left to `AddonBlocks` to report.
     */
    private static Component craftingTitle(JsonObject components) {
        if (!components.has("minecraft:crafting_table")) return null;

        String name = text(components, "minecraft:crafting_table", "table_name");
        if (name == null) name = text(components, "minecraft:display_name", null);
        return Component.translatable(name == null ? "container.crafting" : name);
    }

    private static MapColor mapColour(JsonObject components) {
        JsonElement component = components.get("minecraft:map_color");
        if (component == null) return null;

        JsonElement colour = component.isJsonObject()
            ? component.getAsJsonObject().get("color") : component;
        Integer rgb = rgb(colour);
        return rgb == null ? null : nearestMapColour(rgb);
    }

    /** A colour is `#rrggbb`, which may carry an alpha byte in front, or the channels as an array. */
    private static Integer rgb(JsonElement colour) {
        if (colour == null) return null;
        if (colour.isJsonArray()) {
            JsonArray channels = colour.getAsJsonArray();
            if (channels.size() < 3) return null;

            int packed = 0;
            for (int channel = 0; channel < 3; channel++) {
                JsonElement value = channels.get(channel);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
                packed = (packed << 8) | (value.getAsInt() & 0xFF);
            }
            return packed;
        }
        if (!colour.isJsonPrimitive()) return null;

        try {
            return (int) (Long.parseLong(colour.getAsString().replace("#", ""), 16) & 0xFFFFFF);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static int squaredDistance(int left, int right) {
        int red = ((left >> 16) & 0xFF) - ((right >> 16) & 0xFF);
        int green = ((left >> 8) & 0xFF) - ((right >> 8) & 0xFF);
        int blue = (left & 0xFF) - (right & 0xFF);
        return red * red + green * green + blue * blue;
    }

    static int lightEmission(JsonObject components) {
        return (int) number(components, "minecraft:light_emission", "light_emission", 0);
    }

    /** `false` means the block cannot be mined at all, which Java writes as a negative hardness. */
    static float hardness(JsonObject components) {
        JsonElement component = components.get("minecraft:destructible_by_mining");
        if (component != null && isFalse(component)) return -1f;
        return number(components, "minecraft:destructible_by_mining", "seconds_to_destroy", 1f);
    }

    private static float resistance(JsonObject components) {
        JsonElement component = components.get("minecraft:destructible_by_explosion");
        if (component != null && isFalse(component)) return BLAST_PROOF;
        return number(components, "minecraft:destructible_by_explosion", "explosion_resistance", 1f);
    }

    /**
     * Which instrument a note block sitting on this block plays.
     *
     * Bedrock names a sound per face, and the face a note block on top touches is `up`, whose own
     * default is the harp Java falls back to anyway. `note.none` and the trumpets have no
     * counterpart every supported version ships, so those leave Java's answer alone.
     */
    private static NoteBlockInstrument instrument(JsonObject components) {
        String sound = text(components, "minecraft:instrument_sound", "up");
        return sound == null ? null : INSTRUMENTS.get(sound);
    }

    /** Bedrock's name for the sound a Java instrument makes, or `note.none` for one it has no name for. */
    public static String instrumentName(NoteBlockInstrument instrument) {
        return INSTRUMENTS.entrySet().stream()
            .filter(entry -> entry.getValue() == instrument)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(NO_INSTRUMENT);
    }

    /** What Bedrock calls a face that plays nothing. */
    public static final String NO_INSTRUMENT = "note.none";

    private static final Map<String, NoteBlockInstrument> INSTRUMENTS = Map.ofEntries(
        Map.entry("note.harp", NoteBlockInstrument.HARP),
        Map.entry("note.bd", NoteBlockInstrument.BASEDRUM),
        Map.entry("note.snare", NoteBlockInstrument.SNARE),
        Map.entry("note.hat", NoteBlockInstrument.HAT),
        Map.entry("note.bassattack", NoteBlockInstrument.BASS),
        Map.entry("note.flute", NoteBlockInstrument.FLUTE),
        Map.entry("note.bell", NoteBlockInstrument.BELL),
        Map.entry("note.guitar", NoteBlockInstrument.GUITAR),
        Map.entry("note.chime", NoteBlockInstrument.CHIME),
        Map.entry("note.xylophone", NoteBlockInstrument.XYLOPHONE),
        Map.entry("note.iron_xylophone", NoteBlockInstrument.IRON_XYLOPHONE),
        Map.entry("note.cow_bell", NoteBlockInstrument.COW_BELL),
        Map.entry("note.didgeridoo", NoteBlockInstrument.DIDGERIDOO),
        Map.entry("note.bit", NoteBlockInstrument.BIT),
        Map.entry("note.banjo", NoteBlockInstrument.BANJO),
        Map.entry("note.pling", NoteBlockInstrument.PLING),
        Map.entry("note.zombie", NoteBlockInstrument.ZOMBIE),
        Map.entry("note.skeleton", NoteBlockInstrument.SKELETON),
        Map.entry("note.creeper", NoteBlockInstrument.CREEPER),
        Map.entry("note.enderdragon", NoteBlockInstrument.DRAGON),
        Map.entry("note.witherskeleton", NoteBlockInstrument.WITHER_SKELETON),
        Map.entry("note.piglin", NoteBlockInstrument.PIGLIN));

    /**
     * How far a block sits from the middle of its own space.
     *
     * Bedrock gives a range and a number of steps per axis, and Java offers two fixed patterns, so
     * what carries across is which axes move at all.
     */
    private static BlockBehaviour.OffsetType offsetType(JsonObject components) {
        JsonObject offsets = object(components, "minecraft:random_offset");
        if (moves(offsets, "y")) return BlockBehaviour.OffsetType.XYZ;
        return moves(offsets, "x") || moves(offsets, "z")
            ? BlockBehaviour.OffsetType.XZ : BlockBehaviour.OffsetType.NONE;
    }

    private static boolean moves(JsonObject offsets, String name) {
        JsonObject range = object(object(offsets, name), "range");
        return axis(range, "min", 0) != 0 || axis(range, "max", 0) != 0;
    }

    /** The parts of a `minecraft:transformation` a Java blockstate cannot carry. */
    public static List<String> unmappedTransformation(BlockDefinition definition) {
        JsonObject transformation = object(definition.components(), "minecraft:transformation");
        List<String> unmapped = new ArrayList<>();

        if (vector(transformation.get("rotation"), 0, 0, 0)[2] != 0) unmapped.add("rotation about z");
        for (String field : List.of("scale", "translation", "rotation_pivot", "scale_pivot")) {
            if (transformation.has(field)) unmapped.add(field);
        }
        return unmapped;
    }

    /** The quarter turns about x and about y that a `minecraft:transformation` asks the model for. */
    public static int[] rotation(BlockDefinition definition) {
        return rotation(definition.components());
    }

    /** The same quarter turns, out of components a permutation may have laid over the block's own. */
    public static int[] rotation(JsonObject components) {
        JsonObject transformation = object(components, "minecraft:transformation");
        double[] degrees = vector(transformation.get("rotation"), 0, 0, 0);
        return new int[] {quarterTurn(degrees[0]), quarterTurn(degrees[1])};
    }

    /** Java draws a variant at a quarter turn, so anything between two of them lands on one. */
    private static int quarterTurn(double degrees) {
        return Math.floorMod((int) Math.round(degrees / 90), 4) * 90;
    }

    private static PushReaction pushReaction(String movement) {
        if (movement == null) return Platform.PUSH_PULL;
        return switch (movement) {
            case "push" -> Platform.PUSH_ONLY;
            case "popped" -> Platform.POPPED;
            case "immovable" -> Platform.IMMOVABLE;
            default -> Platform.PUSH_PULL;
        };
    }

    /** The bare value the component carries, or the one its object wraps under `key`. */
    private static JsonElement value(JsonObject components, String name, String key) {
        JsonElement component = components.get(name);
        if (component == null || !component.isJsonObject()) return component;
        return key == null ? null : component.getAsJsonObject().get(key);
    }

    private static float number(JsonObject components, String name, String key, float fallback) {
        JsonElement value = value(components, name, key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
            ? value.getAsFloat() : fallback;
    }

    private static String text(JsonObject components, String name, String key) {
        JsonElement value = value(components, name, key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : null;
    }

    private static boolean flag(JsonObject components, String name, String key) {
        JsonElement value = value(components, name, key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
            && value.getAsBoolean();
    }

    private static boolean isFalse(JsonElement component) {
        return component.isJsonPrimitive() && component.getAsJsonPrimitive().isBoolean()
            && !component.getAsBoolean();
    }

    /** A vector is three numbers in an array, or the same three under `x`, `y` and `z`. */
    static double[] vector(JsonElement value, double x, double y, double z) {
        double[] fallback = {x, y, z};
        if (value == null) return fallback;

        if (value.isJsonArray()) {
            JsonArray numbers = value.getAsJsonArray();
            if (numbers.size() < 3) return fallback;
            return new double[] {
                numbers.get(0).getAsDouble(), numbers.get(1).getAsDouble(), numbers.get(2).getAsDouble()};
        }
        if (!value.isJsonObject()) return fallback;

        JsonObject axes = value.getAsJsonObject();
        return new double[] {axis(axes, "x", x), axis(axes, "y", y), axis(axes, "z", z)};
    }

    private static double axis(JsonObject axes, String name, double fallback) {
        JsonElement value = axes.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsDouble() : fallback;
    }

    /** The named member, or an empty object when the pack left it out or wrote something else. */
    private static JsonObject object(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    /** The tags the pack asked for, in the order it wrote them. */
    public static List<String> tags(BlockDefinition definition) {
        List<String> tags = new ArrayList<>();
        // Java decides what a leash can be tied to from a tag rather than from the block, so a
        // block a pack declared leashable joins the tag that already means it.
        if (definition.components().has("minecraft:leashable")) tags.add("minecraft:fences");

        // Snow settles where the face below it is full, and a tag is how Java says a block carries
        // it anyway, which is what a pack asking for snow to gather here needs.
        if ("obstruct_rain_accumulate_snow".equals(text(definition.components(),
            "minecraft:precipitation_interactions", "precipitation_behavior"))) {
            tags.add(at.ioexception.mcaddon.platform.Platform.snowSupportTag());
        }

        JsonElement component = definition.components().get("minecraft:tags");
        if (component == null || !component.isJsonArray()) return tags;

        for (JsonElement element : component.getAsJsonArray()) {
            if (element.isJsonPrimitive()) tags.add(element.getAsString());
        }
        return tags;
    }

    /** The loot table a pack named, as the pack-relative path it wrote. */
    public static String loot(BlockDefinition definition) {
        JsonObject components = definition.components();
        String table = text(components, "minecraft:loot", null);
        return table == null ? text(components, "minecraft:loot", "table") : table;
    }

    /** The translation key or text a pack wants shown for the block, or null for Java's own name. */
    public static String displayName(BlockDefinition definition) {
        return text(definition.components(), "minecraft:display_name", null);
    }
}
