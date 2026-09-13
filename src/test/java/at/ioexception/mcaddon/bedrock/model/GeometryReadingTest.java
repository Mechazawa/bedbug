package at.ioexception.mcaddon.bedrock.model;

import at.ioexception.mcaddon.bedrock.BedrockAssets;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.util.Json;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a geometry reaches the baker from each of the two shapes a pack may write it in.
 *
 * The 1.8 shape is where the two differ most: it spells the texture size without an underscore,
 * writes the resting rotation under a second name, and says what a geometry extends in the key
 * rather than in a field, pointing at a geometry that is usually in another file.
 */
class GeometryReadingTest {
    @TempDir
    Path directory;

    @Test
    void takesTheTextureSizeFromTheNameThe18ShapeSpellsItWith() throws IOException {
        BedrockGeometry geometry = legacy("""
            {
              "format_version": "1.8.0",
              "geometry.thing": {"texturewidth": 128, "textureheight": 32, "bones": []}
            }
            """).geometry("geometry.thing");

        assertEquals(128, geometry.textureWidth());
        assertEquals(32, geometry.textureHeight());
    }

    @Test
    void namesAnExtendingGeometryWithoutTheParentItNames() throws IOException {
        BedrockAssets assets = legacy("""
            {
              "format_version": "1.8.0",
              "geometry.child:geometry.parent": {"bones": [{"name": "hat"}]}
            }
            """, """
            {
              "format_version": "1.8.0",
              "geometry.parent": {"texturewidth": 64, "bones": [{"name": "head"}]}
            }
            """);

        assertNull(assets.geometry("geometry.child:geometry.parent"));
        assertNotNull(assets.geometry("geometry.child"));
    }

    /** A child takes the parent's bones and its texture size, and adds its own bones to them. */
    @Test
    void foldsTheParentIntoAnExtendingGeometry() throws IOException {
        BedrockGeometry child = legacy("""
            {
              "format_version": "1.8.0",
              "geometry.child:geometry.parent": {"bones": [{"name": "hat"}]}
            }
            """, """
            {
              "format_version": "1.8.0",
              "geometry.parent": {"texturewidth": 128, "textureheight": 128,
                "bones": [{"name": "head"}, {"name": "body"}]}
            }
            """).geometry("geometry.child");

        assertEquals(128, child.textureWidth());
        assertEquals(3, child.bones().size());
        assertTrue(child.byName().containsKey("head"));
        assertTrue(child.byName().containsKey("hat"));
    }

    /** A bone the child names again replaces the parent's, which is how an armour layer thickens one. */
    @Test
    void letsTheChildReplaceABoneRatherThanRepeatIt() throws IOException {
        BedrockGeometry child = legacy("""
            {
              "format_version": "1.8.0",
              "geometry.child:geometry.parent": {"bones": [{"name": "head", "pivot": [0, 8, 0]}]}
            }
            """, """
            {
              "format_version": "1.8.0",
              "geometry.parent": {"bones": [{"name": "head", "pivot": [1, 1, 1]}]}
            }
            """).geometry("geometry.child");

        assertEquals(1, child.bones().size());
        assertEquals(0f, child.bones().get(0).pivot().x);
    }

    @Test
    void readsTheRestingRotationUnderTheOlderName() {
        BedrockBone bone = bone("""
            {"name": "body", "bind_pose_rotation": [90, 0, 0]}
            """);

        assertEquals(90f, bone.rotation().x);
    }

    /** The two names hold the same value wherever both are written, so the newer one wins. */
    @Test
    void prefersTheNewerRotationNameOverTheOlder() {
        BedrockBone bone = bone("""
            {"name": "spine", "rotation": [45, 0, 0], "bind_pose_rotation": [45, 0, 0]}
            """);

        assertEquals(45f, bone.rotation().x);
    }

    @Test
    void hangsChildrenOffABoneThatIsNeverDrawn() {
        BakedGeometry baked = GeometryBaker.bake(geometry("""
            [
              {"name": "root", "neverRender": true, "cubes": [{"origin": [0, 0, 0], "size": [1, 1, 1],
                "uv": [0, 0]}]},
              {"name": "arm", "parent": "root", "cubes": [{"origin": [0, 0, 0], "size": [1, 1, 1],
                "uv": [0, 0]}]}
            ]
            """));

        assertEquals(0, baked.bones().get("root").quads().size());
        assertEquals(6, baked.bones().get("arm").quads().size());
    }

    /** A cube's own inflate replaces the bone's for that cube, rather than adding to it. */
    @Test
    void letsACubeOverrideTheBonesInflate() {
        BedrockBone bone = bone("""
            {"name": "body", "inflate": 4,
             "cubes": [{"origin": [0, 0, 0], "size": [1, 1, 1], "uv": [0, 0]},
                       {"origin": [0, 0, 0], "size": [1, 1, 1], "uv": [0, 0], "inflate": 0.5}]}
            """);

        assertEquals(4f, bone.cubes().get(0).inflate());
        assertEquals(0.5f, bone.cubes().get(1).inflate());
    }

    @Test
    void letsACubeOverrideTheBonesMirror() {
        BedrockBone bone = bone("""
            {"name": "body", "mirror": true,
             "cubes": [{"origin": [0, 0, 0], "size": [1, 1, 1], "uv": [0, 0]},
                       {"origin": [0, 0, 0], "size": [1, 1, 1], "uv": [0, 0], "mirror": false}]}
            """);

        assertTrue(bone.cubes().get(0).mirror());
        assertFalse(bone.cubes().get(1).mirror());
    }

    /** A face with no `uv_size` maps the box as seen from that face, which is not a patch of no area. */
    @Test
    void mapsAFaceWithNoPatchSizeOntoTheBoxItCovers() {
        BedrockCube cube = bone("""
            {"name": "body", "cubes": [{"origin": [0, 0, 0], "size": [2, 3, 5],
              "uv": {"north": {"uv": [0, 0]}, "east": {"uv": [8, 0]}, "up": {"uv": [16, 0]}}}]}
            """).cubes().get(0);

        CubeUv.PerFace faces = (CubeUv.PerFace) cube.uv();
        assertEquals(2f, faces.faces().get(Direction.NORTH).width());
        assertEquals(3f, faces.faces().get(Direction.NORTH).height());
        assertEquals(5f, faces.faces().get(Direction.EAST).width());
        assertEquals(5f, faces.faces().get(Direction.UP).height());
    }

    private static BedrockBone bone(String body) {
        return geometry("[" + body + "]").bones().get(0);
    }

    private static BedrockGeometry geometry(String bones) {
        return GeometryParser.readGeometry("geometry.test",
            Json.parse("{\"description\": {}, \"bones\": " + bones + "}").getAsJsonObject());
    }

    private BedrockAssets legacy(String... files) throws IOException {
        Path pack = directory.resolve("pack");
        Files.createDirectories(pack.resolve("models"));
        for (int index = 0; index < files.length; index++) {
            Files.writeString(pack.resolve("models").resolve(index + ".geo.json"), files[index]);
        }

        PackManifest manifest = PackManifest.tryFrom(Json.parse("""
            {"header": {"uuid": "00000000-0000-0000-0000-000000000000", "name": "test"}}
            """).getAsJsonObject());
        return BedrockAssets.read(new BedrockPack(pack, manifest, "test.mcaddon"));
    }
}
