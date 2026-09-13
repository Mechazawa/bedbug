package at.ioexception.mcaddon.block;

import at.ioexception.mcaddon.bedrock.behaviour.BlockDefinition;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a pack's block components mean once they are read as Java's.
 *
 * Every answer here is a function of the JSON alone, so none of it needs a world. The two that
 * carry arithmetic are the friction axis, which the editions count from opposite ends, and the box
 * coordinates, which Bedrock centres on the block and Java does not.
 */
class BlockComponentsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @CsvSource({
        // Both defaults: Bedrock's 0.4 of grip is Java's 0.6 of slipperiness.
        "0.4, 0.6",
        "0.9, 0.1",
        "0.6, 0.4",
        // No grip at all is slipperier than Java can hold, so it stops at blue ice.
        "0.0, 0.989",
        // A pack writing outside the published range is held inside it.
        "1.5, 0.1",
        "-1.0, 0.989",
    })
    void readsFrictionFromTheOppositeEndOfTheAxis(float bedrock, float java) {
        assertEquals(java, BlockComponents.javaFriction(bedrock), 0.0001f);
    }

    @Test
    void takesABoxOffTheCentreOfTheBlockAndOntoItsCorner() {
        VoxelShape slab = BlockComponents.box(object("{\"origin\": [-8, 0, -8], \"size\": [16, 8, 16]}"));
        assertEquals(new AABB(0, 0, 0, 1, 0.5, 1), slab.bounds());

        VoxelShape post = BlockComponents.box(object("{\"origin\": [-4, 0, -4], \"size\": [8, 16, 8]}"));
        assertEquals(new AABB(0.25, 0, 0.25, 0.75, 1, 0.75), post.bounds());
    }

    @Test
    void fillsTheWholeBlockWhenNeitherCornerIsGiven() {
        assertEquals(new AABB(0, 0, 0, 1, 1, 1), BlockComponents.box(new JsonObject()).bounds());
    }

    @Test
    void turnsAFalseCollisionBoxIntoNothingToWalkInto() {
        BlockComponents components = read("{\"minecraft:collision_box\": false}");
        assertTrue(components.collision().isEmpty());
        assertNull(components.selection());
    }

    @Test
    void readsABoxWrittenAsAListAsOneShape() {
        BlockComponents components = read("{\"minecraft:collision_box\": ["
            + "{\"origin\": [-8, 0, -8], \"size\": [16, 4, 16]},"
            + "{\"origin\": [-2, 4, -2], \"size\": [4, 12, 4]}]}");
        assertEquals(new AABB(0, 0, 0, 1, 1, 1), components.collision().bounds());
    }

    @ParameterizedTest
    @CsvSource({
        "'{\"minecraft:flammable\": true}', 5, 20",
        "'{\"minecraft:flammable\": {\"catch_chance_modifier\": 30, \"destroy_chance_modifier\": 60}}', 30, 60",
        "'{\"minecraft:flammable\": false}', -1, 20",
        "'{}', -1, 20",
    })
    void readsBothShapesOfTheFlammableComponent(String json, int catchChance, int destroyChance) {
        BlockComponents components = read(json);
        assertEquals(catchChance, components.catchChance());
        assertEquals(destroyChance, components.destroyChance());
    }

    @Test
    void namesTheNearestColourJavaPaintsAMapWith() {
        for (MapColor colour : List.of(MapColor.COLOR_RED, MapColor.SNOW, MapColor.DEEPSLATE)) {
            assertEquals(colour, BlockComponents.nearestMapColour(colour.col));
            // The palette is far coarser than a channel, so a colour a step off still lands on it.
            assertEquals(colour, BlockComponents.nearestMapColour(colour.col ^ 1));
        }
    }

    @Test
    void readsTheTableAndTheTagsAPackNames() {
        BlockDefinition definition = definition("{\"minecraft:loot\": \"loot_tables/blocks/ore.json\","
            + "\"minecraft:tags\": [\"minecraft:mineable/pickaxe\", \"ore\"]}");
        assertEquals("loot_tables/blocks/ore.json", BlockComponents.loot(definition));
        assertEquals(List.of("minecraft:mineable/pickaxe", "ore"), BlockComponents.tags(definition));
    }

    @Test
    void readsTheNameAPackWantsShown() {
        assertEquals("tile.test:ore.name",
            BlockComponents.displayName(definition("{\"minecraft:display_name\": \"tile.test:ore.name\"}")));
        assertNull(BlockComponents.displayName(definition("{}")));
    }

    private static BlockComponents read(String components) {
        return BlockComponents.of(definition(components), Map.of(), PackShapes.NONE);
    }

    private static BlockDefinition definition(String components) {
        return new BlockDefinition("test:block", Map.of(), null, false, object(components),
            Map.of(), List.of(), new JsonObject());
    }

    private static JsonObject object(String json) {
        return Json.parse(json).getAsJsonObject();
    }
}
