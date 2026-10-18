package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.bedrock.behaviour.DataValues;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What the two editions call the same vanilla block, in both directions.
 *
 * Most ids match. Of those that do not, some are a plain rename (`golden_rail` is Java's
 * `powered_rail`), some are one Java block in a state of its own (`lit_furnace` is a furnace with
 * `lit` set, a `_double_slab` a slab of the double type), and some are a whole family Bedrock picks
 * a member of by a data value or a named state, which {@link DataValues} holds. A Bedrock name may
 * also mean something other than the Java block spelled the same: Bedrock's `snow` is the full
 * block and its `snow_layer` the layer Java calls `snow`, and the same goes for `nether_brick` and
 * `stone_stairs`. The registry has the last word on every answer, because a Java name differs from
 * one supported version to the next.
 *
 * The way back starts from a state rather than a name, because Bedrock spells as separate ids what
 * Java holds in one block's properties.
 */
public final class BedrockBlockNames {
    /**
     * Bedrock's spelling and the Java block it is, for a block whose item carries the same name.
     *
     * These turn round cleanly, so the way back is this table read the other way.
     */
    static final Map<String, String> RENAMED = Map.ofEntries(
        Map.entry("azalea_leaves_flowered", "flowering_azalea_leaves"),
        Map.entry("brick_block", "bricks"),
        Map.entry("deadbush", "dead_bush"),
        Map.entry("dirt_with_roots", "rooted_dirt"),
        Map.entry("end_brick_stairs", "end_stone_brick_stairs"),
        Map.entry("end_bricks", "end_stone_bricks"),
        Map.entry("fence_gate", "oak_fence_gate"),
        Map.entry("frog_spawn", "frogspawn"),
        Map.entry("golden_rail", "powered_rail"),
        Map.entry("grass_path", "dirt_path"),
        Map.entry("hardened_clay", "terracotta"),
        Map.entry("lit_pumpkin", "jack_o_lantern"),
        Map.entry("magma", "magma_block"),
        Map.entry("melon_block", "melon"),
        Map.entry("mob_spawner", "spawner"),
        Map.entry("nether_brick", "nether_bricks"),
        Map.entry("normal_stone_slab", "stone_slab"),
        Map.entry("normal_stone_stairs", "stone_stairs"),
        Map.entry("noteblock", "note_block"),
        Map.entry("prismarine_bricks_stairs", "prismarine_brick_stairs"),
        Map.entry("quartz_ore", "nether_quartz_ore"),
        Map.entry("red_nether_brick", "red_nether_bricks"),
        Map.entry("silver_glazed_terracotta", "light_gray_glazed_terracotta"),
        Map.entry("slime", "slime_block"),
        Map.entry("small_dripleaf_block", "small_dripleaf"),
        Map.entry("snow", "snow_block"),
        Map.entry("snow_layer", "snow"),
        Map.entry("stone_stairs", "cobblestone_stairs"),
        Map.entry("stonecutter_block", "stonecutter"),
        Map.entry("trapdoor", "oak_trapdoor"),
        Map.entry("undyed_shulker_box", "shulker_box"),
        Map.entry("waterlily", "lily_pad"),
        Map.entry("waxed_copper", "waxed_copper_block"),
        Map.entry("web", "cobweb"),
        Map.entry("wooden_button", "oak_button"),
        Map.entry("wooden_door", "oak_door"),
        Map.entry("wooden_pressure_plate", "oak_pressure_plate"));

    /**
     * Bedrock blocks that are a Java block in a particular state, or whose item is called something
     * else, written as Java's own `block[property=value]`.
     *
     * The way back for each is worked out from the state in {@link #bedrock(BlockState)}. A banner
     * and a bed keep their colour in the block entity in Bedrock and in the block in Java, so the
     * colour here is the one Bedrock's own zero stands for.
     */
    private static final Map<String, String> STATED = Map.ofEntries(
        Map.entry("beetroot", "beetroots"),
        Map.entry("cave_vines_body_with_berries", "cave_vines_plant[berries=true]"),
        Map.entry("cave_vines_head_with_berries", "cave_vines[berries=true]"),
        Map.entry("daylight_detector_inverted", "daylight_detector[inverted=true]"),
        Map.entry("flowing_lava", "lava"),
        Map.entry("flowing_water", "water"),
        Map.entry("lit_blast_furnace", "blast_furnace[lit=true]"),
        Map.entry("lit_deepslate_redstone_ore", "deepslate_redstone_ore[lit=true]"),
        Map.entry("lit_furnace", "furnace[lit=true]"),
        Map.entry("lit_redstone_lamp", "redstone_lamp[lit=true]"),
        Map.entry("lit_redstone_ore", "redstone_ore[lit=true]"),
        Map.entry("lit_smoker", "smoker[lit=true]"),
        Map.entry("moving_block", "moving_piston"),
        Map.entry("piston_arm_collision", "piston_head"),
        Map.entry("portal", "nether_portal"),
        Map.entry("powered_comparator", "comparator[powered=true]"),
        Map.entry("powered_repeater", "repeater[powered=true]"),
        Map.entry("reeds", "sugar_cane"),
        Map.entry("standing_banner", "black_banner"),
        Map.entry("standing_sign", "oak_sign"),
        Map.entry("sticky_piston_arm_collision", "piston_head[type=sticky]"),
        Map.entry("trip_wire", "tripwire"),
        Map.entry("unlit_redstone_torch", "redstone_torch[lit=false]"),
        Map.entry("unpowered_comparator", "comparator"),
        Map.entry("unpowered_repeater", "repeater"),
        Map.entry("wall_banner", "black_wall_banner"),
        Map.entry("wall_sign", "oak_wall_sign"),
        // Names Bedrock no longer writes and still reads, which the way back never answers with.
        Map.entry("chain", "iron_chain"),
        Map.entry("grass", "grass_block"),
        Map.entry("invisible_bedrock", "barrier"),
        Map.entry("light_block", "light"),
        Map.entry("sealantern", "sea_lantern"),
        Map.entry("yellow_flower", "dandelion"));

    /**
     * Java's name for a block, newest first, followed by what older supported versions call it.
     *
     * Java renamed a few of its own blocks and items after 1.20.1, so a Bedrock name has to land on
     * whichever of them the running version has.
     */
    static final Map<String, List<String>> JAVA_SPELLINGS = Map.of(
        "short_grass", List.of("short_grass", "grass"),
        "iron_chain", List.of("iron_chain", "chain"),
        "turtle_scute", List.of("turtle_scute", "scute"));

    /**
     * Bedrock blocks Java has none of that this mod builds itself, under its own namespace, each
     * with the item that places it.
     */
    static final Map<String, String> BUILT_HERE = Map.of("border_block", "mcaddon:border_block");

    /**
     * Blocks Bedrock has and Java has no counterpart for: the education edition's chemistry and
     * its hardened glass, the building permission blocks, Bedrock's own technical blocks and the
     * experiments Java has not shipped. An item frame is an entity in Java rather than a block.
     */
    private static final List<Pattern> BEDROCK_ONLY = Stream.of(
        "allow", "deny", "camera", "unknown", "info_update2?", "reserved6",
        "client_request_placeholder_block", "netherreactor", "glowingobsidian", "frame", "glow_frame",
        "element_\\d+", "element_constructor", "chemistry_table", "chemical_heat", "compound_creator",
        "lab_table", "material_reducer", "colored_torch_\\w+", "underwater_torch", "underwater_tnt",
        "hard_(\\w+_)?(stained_)?glass(_pane)?", "\\w+_wool_(double_)?slab", "\\w+_wool_stairs",
        "\\w+_cushion", "(stripped_)?poplar_\\w+", "\\w+_poplar_leaves", "red_shrub", "shelf_mushroom",
        "straw_bed", "\\w+_concrete_(double_)?slab", "\\w+_concrete_stairs")
        .map(Pattern::compile)
        .toList();

    private static final Pattern LIGHT_LEVEL = Pattern.compile("light_block_(\\d+)");
    private static final Pattern DOUBLE_SLAB = Pattern.compile("(\\w*?)_?double_(\\w*slab\\d?)");

    /** The way back from {@link #BUILT_HERE}, by the whole id this mod registers each under. */
    static final Map<String, String> BUILT_BACK = BUILT_HERE.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getValue, entry -> "minecraft:" + entry.getKey()));

    /** The way back from {@link #RENAMED}, for every block whose Java name is not Bedrock's. */
    private static final Map<String, String> BEDROCK = RENAMED.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

    /** The way back from an older Java spelling to the newest, which is the one the tables use. */
    private static final Map<String, String> NEWEST = olderSpellings();

    private BedrockBlockNames() {
    }

    /**
     * The Java block id for a Bedrock one, or null when no version of Java has that block.
     *
     * An id outside the `minecraft` namespace belongs to an add-on and comes back untouched.
     */
    public static String java(String bedrock) {
        Named named = Named.of(bedrock, 0);
        return named == null ? null : named.id();
    }

    /** Whether a data value beside this id picks one of the blocks Java split it into. */
    public static boolean family(String bedrock) {
        String name = bare(bedrock);
        return name != null && DataValues.split(stripDouble(name));
    }

    /** Whether Bedrock has this block and Java has nothing to stand in for it. */
    public static boolean bedrockOnly(String bedrock) {
        String name = bare(bedrock);
        return name != null && BEDROCK_ONLY.stream().anyMatch(only -> only.matcher(name).matches());
    }

    /**
     * The Java block a Bedrock name and its states stand for, or null when Java has no such block.
     *
     * A state the family is keyed on picks the block, and every other state is translated to the
     * Java property it means. One that no property answers is reported and left at Java's default,
     * so one unknown state costs that state rather than the block.
     */
    public static Resolved state(String bedrock, Map<String, ?> states, Consumer<String> report) {
        Map<String, String> written = new LinkedHashMap<>();
        states.forEach((key, value) -> written.put(key, String.valueOf(value)));

        String name = bare(bedrock);
        if (name == null) return Resolved.of(packOwn(bedrock), Map.of(), written, report);

        Named named = DataValues.split(stripDouble(name))
            ? Named.picked(name, written) : Named.of(bedrock, 0);
        if (named == null) return null;

        Map<String, String> left = new LinkedHashMap<>(written);
        left.keySet().removeIf(state -> DataValues.picks(stripDouble(name), state));
        return Resolved.of(named.id(), named.properties(), left, report);
    }

    /** The same for the older spelling that names a family member by a number. */
    public static Resolved state(String bedrock, int data, Consumer<String> report) {
        Named named = Named.of(bedrock, data);
        return named == null ? null : Resolved.of(named.id(), named.properties(), Map.of(), report);
    }

    /** The Bedrock id a pack knows this block by, worked out from the state Java holds it in. */
    public static String bedrock(BlockState state) {
        Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!key.getNamespace().equals("minecraft")) return BUILT_BACK.getOrDefault(key.toString(), key.toString());

        String name = NEWEST.getOrDefault(key.getPath(), key.getPath());
        String stated = statedBedrock(name, state);
        return "minecraft:" + (stated != null ? stated : bedrockName(name));
    }

    /** The Bedrock id for a Java block id, read in its default state. */
    public static String bedrock(String java) {
        Identifier id = Identifier.tryParse(java);
        Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        return block == null ? java : bedrock(block.defaultBlockState());
    }

    /**
     * The Bedrock name of a Java block whose state does not change which Bedrock id it is.
     *
     * Java splits by placement what Bedrock keeps as one block: a torch on a wall, a sign, a banner
     * and a head each have a second block in Java, and a potted plant is Bedrock's flower pot with
     * the plant inside it.
     */
    static String bedrockName(String java) {
        String renamed = BEDROCK.get(java);
        if (renamed != null) return renamed;

        if (java.startsWith("potted_")) return "flower_pot";
        if (java.startsWith("attached_") && java.endsWith("_stem")) return java.substring(9);
        if (java.endsWith("_wall_torch")) return java.replace("_wall_torch", "_torch");
        if (java.equals("wall_torch")) return "torch";
        if (java.endsWith("_wall_skull")) return java.replace("_wall_skull", "_skull");
        if (java.endsWith("_wall_head")) return java.replace("_wall_head", "_head");
        if (java.endsWith("_wall_banner")) return "wall_banner";
        if (java.endsWith("_banner")) return "standing_banner";
        if (java.endsWith("_bed")) return "bed";
        if (java.endsWith("_wall_hanging_sign")) return java.replace("_wall_hanging_sign", "_hanging_sign");
        if (java.endsWith("_sign") && !java.endsWith("_hanging_sign")) return sign(java);
        return switch (java) {
            case "beetroots" -> "beetroot";
            case "sugar_cane" -> "reeds";
            case "tripwire" -> "trip_wire";
            case "nether_portal" -> "portal";
            case "moving_piston" -> "moving_block";
            case "kelp_plant", "twisting_vines_plant", "weeping_vines_plant" ->
                java.substring(0, java.length() - "_plant".length());
            case "tall_seagrass" -> "seagrass";
            case "big_dripleaf_stem" -> "big_dripleaf";
            case "water_cauldron", "lava_cauldron", "powder_snow_cauldron" -> "cauldron";
            case "cave_air", "void_air" -> "air";
            default -> java;
        };
    }

    /** Bedrock spells oak's and dark oak's signs its own way, and every sign standing as `standing`. */
    private static String sign(String java) {
        boolean wall = java.endsWith("_wall_sign");
        String wood = java.substring(0, java.length() - (wall ? "_wall_sign" : "_sign").length());
        if (wood.equals("oak")) return wall ? "wall_sign" : "standing_sign";
        if (wood.equals("dark_oak")) wood = "darkoak";
        return wood + (wall ? "_wall_sign" : "_standing_sign");
    }

    /** The Bedrock id a state picks out, for the blocks Bedrock gives an id per state. */
    private static String statedBedrock(String name, BlockState state) {
        String type = valueOf(state, "type");
        if ("double".equals(type) && name.endsWith("_slab")) return doubleSlab(bedrockName(name));

        boolean lit = "true".equals(valueOf(state, "lit"));
        boolean powered = "true".equals(valueOf(state, "powered"));
        return switch (name) {
            case "furnace", "blast_furnace", "smoker", "redstone_ore", "deepslate_redstone_ore",
                "redstone_lamp" -> lit ? "lit_" + name : null;
            case "redstone_torch", "redstone_wall_torch" -> lit ? "redstone_torch" : "unlit_redstone_torch";
            case "repeater", "comparator" -> (powered ? "powered_" : "unpowered_") + name;
            case "daylight_detector" -> "true".equals(valueOf(state, "inverted"))
                ? "daylight_detector_inverted" : null;
            case "cave_vines" -> "true".equals(valueOf(state, "berries"))
                ? "cave_vines_head_with_berries" : null;
            case "cave_vines_plant" -> "true".equals(valueOf(state, "berries"))
                ? "cave_vines_body_with_berries" : "cave_vines";
            case "piston_head" -> "sticky".equals(type) ? "sticky_piston_arm_collision" : "piston_arm_collision";
            case "light" -> "light_block_" + valueOf(state, "level");
            default -> null;
        };
    }

    /** Bedrock names a double slab after its slab, with the copper ones putting `double` inside. */
    private static String doubleSlab(String slab) {
        if (slab.endsWith("cut_copper_slab")) return slab.replace("cut_copper_slab", "double_cut_copper_slab");
        return slab.substring(0, slab.length() - "slab".length()) + "double_slab";
    }

    private static String valueOf(BlockState state, String property) {
        Property<?> found = state.getBlock().getStateDefinition().getProperty(property);
        return found == null ? null : nameOf(state, found);
    }

    private static <T extends Comparable<T>> String nameOf(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** A double slab's Bedrock id with `double` taken out, which names the slab or its family. */
    private static String stripDouble(String name) {
        Matcher matcher = DOUBLE_SLAB.matcher(name);
        if (!matcher.matches()) return name;
        return matcher.group(1).isEmpty() ? matcher.group(2) : matcher.group(1) + "_" + matcher.group(2);
    }

    /** The bare name of a vanilla id, lower-cased the way Bedrock reads it, or null for a pack's. */
    private static String bare(String bedrock) {
        String name = DataValues.vanilla(bedrock);
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    /** The newest Java spelling of a name an older supported version may still use. */
    static String newest(String java) {
        return NEWEST.getOrDefault(java, java);
    }

    /** Bedrock's name for a Java block or block item that turns round through {@link #RENAMED}. */
    static String renamedBack(String java) {
        return BEDROCK.getOrDefault(java, java);
    }

    /** A pack's own id as the registry holds it, its namespace and path sanitised the same way. */
    static String packOwn(String bedrock) {
        Names.Identifier split = Names.split(bedrock, "minecraft");
        return split.namespace() + ":" + split.path();
    }

    private static Map<String, String> olderSpellings() {
        Map<String, String> newest = new HashMap<>();
        JAVA_SPELLINGS.forEach((current, all) -> all.forEach(older -> newest.put(older, current)));
        return Map.copyOf(newest);
    }

    /** The first of a Java name's spellings this version has, or null for none. */
    static String shipped(String java) {
        for (String candidate : JAVA_SPELLINGS.getOrDefault(java, List.of(java))) {
            if (BuiltInRegistries.BLOCK.containsKey(Platform.id("minecraft", candidate))) return candidate;
        }
        return null;
    }

    /**
     * A Bedrock name read as far as a Java block and the properties its own name implies.
     *
     * `id` is a whole resource location, so a pack's own block comes back with its namespace.
     */
    private record Named(String id, Map<String, String> properties) {
        static Named of(String bedrock, int data) {
            String name = bare(bedrock);
            if (name == null) return new Named(packOwn(bedrock), Map.of());
            if (DataValues.split(stripDouble(name))) return member(name, DataValues.item(stripDouble(name), data));
            if (BUILT_HERE.containsKey(name)) return new Named(BUILT_HERE.get(name), Map.of());

            String java = javaName(name);
            return java == null ? null : parsed(java, Map.of());
        }

        static Named picked(String name, Map<String, String> states) {
            return member(name, DataValues.block(stripDouble(name), states));
        }

        /** A family member, which is a slab of the double type when the family was a double one. */
        private static Named member(String name, String member) {
            if (member == null) return null;

            Map<String, String> implied = stripDouble(name).equals(name) ? Map.of() : Map.of("type", "double");
            return parsed(DataValues.vanilla(member), implied);
        }

        private static Named parsed(String java, Map<String, String> implied) {
            int open = java.indexOf('[');
            Map<String, String> properties = new LinkedHashMap<>(implied);
            if (open >= 0) {
                for (String pair : java.substring(open + 1, java.length() - 1).split(",")) {
                    String[] sides = pair.split("=", 2);
                    properties.put(sides[0], sides[1]);
                }
            }
            String shipped = shipped(open < 0 ? java : java.substring(0, open));
            return shipped == null ? null : new Named("minecraft:" + shipped, properties);
        }
    }

    /**
     * The Java name, state included, that a bare lower-cased Bedrock name stands for.
     *
     * Bedrock once named the block form of an item kept apart from it `item.` and the item's name,
     * as `item.soul_campfire` and `item.reeds`, which packs still write in a block list.
     */
    private static String javaName(String bedrock) {
        String name = bedrock.startsWith("item.") ? bedrock.substring("item.".length()) : bedrock;
        String renamed = RENAMED.getOrDefault(name, STATED.get(name));
        if (renamed != null) return renamed;

        Matcher light = LIGHT_LEVEL.matcher(name);
        if (light.matches()) return "light[level=" + light.group(1) + "]";

        String stripped = stripDouble(name);
        if (!stripped.equals(name)) {
            String slab = stripped.equals("cut_copper_slab") || stripped.endsWith("_cut_copper_slab")
                ? stripped : RENAMED.getOrDefault(stripped, stripped);
            return slab + "[type=double]";
        }

        String wood = name.replace("darkoak_", "dark_oak_");
        return wood.endsWith("_standing_sign") ? wood.replace("_standing_sign", "_sign") : wood;
    }

    /**
     * One Bedrock state, as the Java properties it may name and how its value is read.
     *
     * A name on its own is not enough for the states that count a direction rather than spelling it:
     * `facing_direction` is a number and Java wants a word, so a rule carries both halves. The
     * properties are tried in order, because one Bedrock state can land on either of two Java ones
     * depending on the block, as an upside-down stair and an upside-down slab do.
     */
    private record StateRule(List<String> properties, UnaryOperator<String> value) {
        static StateRule named(String... properties) {
            return new StateRule(List.of(properties), UnaryOperator.identity());
        }

        static StateRule wallSide(String property) {
            return new StateRule(List.of(property), given -> given.equals("short") ? "low" : given);
        }

        static StateRule counted(String property, String... words) {
            return new StateRule(List.of(property), given -> {
                int index = given.matches("-?\\d+") ? Integer.parseInt(given) : -1;
                return index >= 0 && index < words.length ? words[index] : given;
            });
        }
    }

    /** The order Bedrock counts a direction in, which is the order Java reads a 3D data value in. */
    private static final String[] SIDES = {"down", "up", "north", "south", "west", "east"};

    /** The order Bedrock counts the four horizontal directions in, for stairs and for repeaters. */
    private static final String[] AROUND = {"south", "west", "north", "east"};

    /**
     * The Bedrock states known to be spelled or counted differently from the Java one.
     *
     * Bedrock suffixes a flag with `_bit`, which is stripped before a name is looked for, so only
     * what differs by more than that is here. A state with no entry and no Java property of its own
     * name is reported rather than guessed at.
     */
    private static final Map<String, StateRule> STATE_RULES = Map.ofEntries(
        Map.entry("pillar_axis", StateRule.named("axis")),
        Map.entry("toggle", StateRule.named("powered")),
        Map.entry("button_pressed", StateRule.named("powered")),
        Map.entry("growth", StateRule.named("age")),
        Map.entry("liquid_depth", StateRule.named("level")),
        Map.entry("block_light_level", StateRule.named("level")),
        Map.entry("minecraft:cardinal_direction", StateRule.named("facing")),
        Map.entry("minecraft:vertical_half", StateRule.named("half", "type")),
        Map.entry("minecraft:block_face", StateRule.named("facing", "face")),
        Map.entry("ground_sign_direction", StateRule.named("rotation")),
        Map.entry("hanging_sign_direction", StateRule.named("rotation")),
        Map.entry("facing_direction", StateRule.counted("facing", SIDES)),
        Map.entry("weirdo_direction", StateRule.counted("facing", AROUND)),
        Map.entry("direction", StateRule.counted("facing", AROUND)),
        // Java splits a torch into a standing block and a wall block, so only the four that name a
        // wall carry across; `top` and `unknown` name the other block and are reported instead.
        Map.entry("torch_facing_direction", StateRule.named("facing")),
        // Stairs carry it as `half`, slabs as `type`, and Java spells both ends the same way.
        // A wall's post and its four sides, where Bedrock's `short` side is Java's `low`.
        Map.entry("wall_post_bit", StateRule.named("up")),
        Map.entry("wall_connection_type_east", StateRule.wallSide("east")),
        Map.entry("wall_connection_type_north", StateRule.wallSide("north")),
        Map.entry("wall_connection_type_south", StateRule.wallSide("south")),
        Map.entry("wall_connection_type_west", StateRule.wallSide("west")),
        Map.entry("upside_down", new StateRule(List.of("half", "type"),
            given -> "1".equals(given) || "true".equals(given) ? "top" : "bottom")));

    /**
     * A Java block state, with the properties a pack named or a rename implied kept apart.
     *
     * A command testing for a block has to ask about those and no others: Bedrock's
     * `testforblock` on a bare name matches the block in any state, and so does Java's.
     */
    public record Resolved(BlockState state, List<Property<?>> stated) {
        static Resolved of(String id, Map<String, String> implied, Map<String, String> written,
            Consumer<String> report) {
            Identifier parsed = Identifier.tryParse(id);
            Block block = parsed == null ? null : BuiltInRegistries.BLOCK.getOptional(parsed).orElse(null);
            if (block == null) return null;

            BlockState state = block.defaultBlockState();
            List<Property<?>> stated = new ArrayList<>();
            for (Map.Entry<String, String> property : implied.entrySet()) {
                Property<?> found = block.getStateDefinition().getProperty(property.getKey());
                if (found == null) continue;
                state = set(state, found, property.getValue(), id, report);
                stated.add(found);
            }
            for (Map.Entry<String, String> named : written.entrySet()) {
                Property<?> found = translated(state, named.getKey());
                if (found == null) {
                    report.accept("block state " + id + "[" + named.getKey() + "]");
                    continue;
                }
                state = set(state, found, value(named.getKey(), named.getValue(), found), id, report);
                if (!stated.contains(found)) stated.add(found);
            }
            return new Resolved(state, List.copyOf(stated));
        }

        /** The block as Java's command parser reads it, naming only the stated properties. */
        public String command() {
            if (stated.isEmpty()) return id();
            return id() + properties().entrySet().stream()
                .map(property -> property.getKey() + "=" + property.getValue())
                .collect(Collectors.joining(",", "[", "]"));
        }

        public String id() {
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        }

        /** The stated properties under Java's names and values, in the order they were stated. */
        public Map<String, String> properties() {
            Map<String, String> properties = new LinkedHashMap<>();
            stated.forEach(property -> properties.put(property.getName(), nameOf(state, property)));
            return properties;
        }

        /**
         * The property a state names: under its own spelling, then as a pack's own block registered
         * it, then under the Java names its rule offers. A pack's block carries its states under
         * sanitised names, so `custom:facing` is the property `custom_facing`.
         */
        private static Property<?> translated(BlockState state, String name) {
            String bare = name.toLowerCase(Locale.ROOT);
            String stripped = bare.endsWith("_bit") ? bare.substring(0, bare.length() - 4) : bare;
            StateRule rule = STATE_RULES.getOrDefault(bare, STATE_RULES.get(stripped));

            String sanitised = Names.sanitise(name).replace('.', '_').replace('/', '_');
            List<String> candidates = new ArrayList<>(List.of(name, sanitised, bare, stripped));
            if (rule != null) candidates.addAll(rule.properties());
            return candidates.stream().distinct()
                .map(candidate -> state.getBlock().getStateDefinition().getProperty(candidate))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        }

        /** Bedrock writes a flag as a byte and Java parses it from the words the property uses. */
        private static String value(String name, String written, Property<?> property) {
            String bare = name.toLowerCase(Locale.ROOT);
            String stripped = bare.endsWith("_bit") ? bare.substring(0, bare.length() - 4) : bare;
            StateRule rule = STATE_RULES.getOrDefault(bare, STATE_RULES.get(stripped));
            String text = property instanceof BooleanProperty && written.matches("\\d+")
                ? (written.equals("0") ? "false" : "true") : written;
            return rule == null ? text : rule.value().apply(text);
        }

        private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property,
            String value, String block, Consumer<String> report) {
            T parsed = property.getValue(value).orElse(null);
            if (parsed == null) {
                report.accept("block state " + block + "[" + property.getName() + "=" + value + "]");
                return state;
            }
            return state.setValue(property, parsed);
        }
    }
}
