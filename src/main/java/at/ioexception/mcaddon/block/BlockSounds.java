package at.ioexception.mcaddon.block;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;

import java.util.Map;

/**
 * The sound a block makes, from the group `blocks.json` names it by.
 *
 * Bedrock keeps a table of groups and Java keeps a `SoundType` per block, and the two hold the same
 * sounds under different names. Rather than pairing a hundred group names with a hundred Java
 * constants, a group is answered by the vanilla block Java already gives that sound to: `stone` is
 * the sound of stone, so the block named `stone` carries the answer. Most groups are named after
 * such a block already, and the rest are paired below.
 */
public final class BlockSounds {
    private BlockSounds() {
    }

    /** A group whose name is not itself a Java block, and the block whose sound it is. */
    private static final Map<String, String> NAMED_AFTER = Map.ofEntries(
        Map.entry("bamboo_wood", "bamboo_planks"),
        Map.entry("bamboo_wood_hanging_sign", "bamboo_hanging_sign"),
        Map.entry("cherry_wood_hanging_sign", "cherry_hanging_sign"),
        Map.entry("cloth", "white_wool"),
        Map.entry("copper", "copper_block"),
        Map.entry("coral", "tube_coral_block"),
        Map.entry("deadbush", "dead_bush"),
        Map.entry("dirt_with_roots", "rooted_dirt"),
        Map.entry("eyeblossom", "open_eyeblossom"),
        Map.entry("frog_spawn", "frogspawn"),
        Map.entry("froglight", "ochre_froglight"),
        Map.entry("fungus", "crimson_fungus"),
        Map.entry("grass", "grass_block"),
        Map.entry("hanging_sign", "oak_hanging_sign"),
        Map.entry("iron", "iron_block"),
        Map.entry("metal", "iron_block"),
        Map.entry("mob_spawner", "spawner"),
        Map.entry("nether_brick", "nether_bricks"),
        Map.entry("nether_wood", "crimson_planks"),
        Map.entry("nether_wood_hanging_sign", "crimson_hanging_sign"),
        Map.entry("netherite", "netherite_block"),
        Map.entry("nylium", "crimson_nylium"),
        Map.entry("resin", "resin_block"),
        Map.entry("resin_brick", "resin_bricks"),
        Map.entry("roots", "crimson_roots"),
        Map.entry("slime", "slime_block"),
        Map.entry("stem", "crimson_stem"),
        Map.entry("vines", "vine"),
        Map.entry("web", "cobweb"),
        Map.entry("wood", "oak_planks"));

    /**
     * Null where this version of the game has no block carrying that group's sound.
     *
     * A group Bedrock added alongside a block Java has not got answers nothing, and so does one a
     * pack defined for itself in `sounds.json`, which names sound events rather than a Java sound
     * type. Either way the block keeps the sound its material gives it and the group is named.
     */
    public static SoundType of(String group) {
        if (group == null) return null;

        Block block = blockNamed(NAMED_AFTER.getOrDefault(group, group));
        if (block == null) block = blockNamed(group + "_block");
        if (block == null) {
            AddonEntities.unsupported("blocks.json sound group " + group);
            return null;
        }
        return block.defaultBlockState().getSoundType();
    }

    private static Block blockNamed(String name) {
        return BuiltInRegistries.BLOCK.getOptional(Platform.id("minecraft", name)).orElse(null);
    }
}
