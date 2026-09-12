package at.ioexception.mcaddon.bedrock.model;

import at.ioexception.mcaddon.pack.BedrockPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a Bedrock material answers the questions a Java render type is chosen by.
 *
 * A material says what it wants two ways: through its own defines and states, and through the name
 * of the material it is written on top of. Culling is the one that inverts: the entity materials
 * ship with culling already off, so a pack asks for culling by removing a state rather than by
 * adding one, and an absent state therefore cannot mean the same thing as a removed one.
 *
 * Both removal lists point at the parent. What a material removes is almost always something the
 * material above it added, so a removal has to survive until the chain is folded together instead of
 * being applied where it is written.
 */
class MaterialDefinitionTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({
        "entity_alphablend, '', true",
        "entity_blend, '', true",
        "entity, Blending, true",
        "entity_alphatest, '', false",
        "entity, DisableCulling, false",
    })
    void blendsOnItsOwnStateOrOnTheParentsName(String parent, String states, boolean expected) {
        assertEquals(expected, material(parent, "", states, "").blends());
    }

    @ParameterizedTest
    @CsvSource({
        "entity, USE_EMISSIVE, true",
        "entity, USE_ONLY_EMISSIVE, true",
        "entity_emissive_alpha, '', true",
        "entity, USE_OVERLAY, false",
        "entity, '', false",
    })
    void glowsOnItsOwnDefineOrOnTheParentsName(String parent, String defines, boolean expected) {
        assertEquals(expected, material(parent, defines, "", "").emissive());
    }

    @ParameterizedTest
    @CsvSource({
        "'', '', false",
        "DisableCulling, '', false",
        "'', DisableCulling, true",
    })
    void cullsOnlyWhenDisableCullingWasRemoved(String states, String removed, boolean expected) {
        assertEquals(expected, material("entity", "", states, removed).culls());
    }

    @Test
    void isInvisibleWhenThePassWritesNoColour() {
        assertTrue(material("entity", "", "DisableColorWrite", "").invisible());
        assertFalse(material("entity", "", "Blending", "").invisible());
    }

    @Test
    void needsAStencilOnlyForTheStatesJavaCannotDraw() {
        assertTrue(material("entity", "", "EnableStencilTest", "").needsStencil());
        assertTrue(material("entity", "", "StencilWrite", "").needsStencil());
        assertFalse(material("entity", "", "Blending", "").needsStencil());
    }

    /** A child inherits its parent's defines and states, and its own removals win over both. */
    @Test
    void foldsAParentIntoTheChild() throws IOException {
        Map<String, MaterialDefinition> materials = readMaterials("""
            {
              "materials": {
                "base": {"+defines": ["USE_EMISSIVE"], "+states": ["DisableCulling", "Blending"]},
                "child:base": {"-states": ["DisableCulling"]}
              }
            }
            """);

        MaterialDefinition child = materials.get("child");
        assertTrue(child.emissive());
        assertTrue(child.blends());
        assertTrue(child.culls());
        assertFalse(materials.get("base").culls());
    }

    /** Resolution walks the whole chain, so a grandparent's defines reach the child as well. */
    @Test
    void foldsAWholeChainAndNotOnlyTheDirectParent() throws IOException {
        Map<String, MaterialDefinition> materials = readMaterials("""
            {
              "materials": {
                "base": {"+defines": ["USE_EMISSIVE"]},
                "middle:base": {"+states": ["Blending"]},
                "child:middle": {"+states": ["DisableColorWrite"]}
              }
            }
            """);

        MaterialDefinition child = materials.get("child");
        assertTrue(child.emissive());
        assertTrue(child.blends());
        assertTrue(child.invisible());
        assertFalse(materials.get("middle").invisible());
    }

    /**
     * `-defines` cancels a define the parent added, which is the only thing a pack writes it for.
     *
     * Applying it where it is written would leave it with nothing to remove: a material's own
     * `+defines` are the ones it chose to have, so cancelling them in the same object is a no-op.
     */
    @Test
    void dropsADefineTheParentAdded() throws IOException {
        Map<String, MaterialDefinition> materials = readMaterials("""
            {
              "materials": {
                "base": {"+defines": ["USE_EMISSIVE"]},
                "child:base": {"-defines": ["USE_EMISSIVE"]}
              }
            }
            """);

        assertTrue(materials.get("base").emissive());
        assertFalse(materials.get("child").emissive());
    }

    @Test
    void dropsADefineItsGrandparentAdded() throws IOException {
        Map<String, MaterialDefinition> materials = readMaterials("""
            {
              "materials": {
                "base": {"+defines": ["USE_EMISSIVE"]},
                "middle:base": {"+states": ["Blending"]},
                "child:middle": {"-defines": ["USE_EMISSIVE"]}
              }
            }
            """);

        assertTrue(materials.get("middle").emissive());
        assertFalse(materials.get("child").emissive());
        assertTrue(materials.get("child").blends());
    }

    /** A removal reaches as far down the chain as the define did, and no further. */
    @Test
    void letsALaterMaterialAddBackADefineItsParentDropped() throws IOException {
        Map<String, MaterialDefinition> materials = readMaterials("""
            {
              "materials": {
                "base": {"+defines": ["USE_EMISSIVE"]},
                "middle:base": {"-defines": ["USE_EMISSIVE"]},
                "child:middle": {"+defines": ["USE_EMISSIVE"]}
              }
            }
            """);

        assertFalse(materials.get("middle").emissive());
        assertTrue(materials.get("child").emissive());
    }

    @Test
    void dropsADefineItAddedItself() throws IOException {
        Map<String, MaterialDefinition> materials = readMaterials("""
            {
              "materials": {
                "solo": {"+defines": ["USE_EMISSIVE", "USE_OVERLAY"], "-defines": ["USE_EMISSIVE"]}
              }
            }
            """);

        assertFalse(materials.get("solo").emissive());
    }

    private Map<String, MaterialDefinition> readMaterials(String body) throws IOException {
        Path pack = directory.resolve("pack");
        Files.createDirectories(pack.resolve("materials"));
        Files.writeString(pack.resolve("materials").resolve("entity.material"), body);
        return MaterialDefinition.readAll(new BedrockPack(pack, null, "test.mcaddon"));
    }

    private static MaterialDefinition material(String parent, String defines, String states, String removed) {
        return new MaterialDefinition("test", parent, words(defines), words(states), words(removed), Set.of());
    }

    private static Set<String> words(String value) {
        return value.isBlank() ? Set.of() : Set.of(value.split(" "));
    }
}
