package at.ioexception.mcaddon.bedrock.entity;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a client entity definition says about which clips run and how big the model is drawn.
 *
 * A pack says which controllers run one of two ways: the newer `scripts.animate` list, or the
 * `animation_controllers` list beside it, which names each controller and runs every one of them.
 */
class ClientEntityParserTest {
    @Test
    void keepsTheControllerListInTheOrderThePackWroteIt() {
        ClientEntityDefinition definition = read("""
            {
              "animation_controllers": [
                {"general": "controller.animation.villager.general"},
                {"move": "controller.animation.villager.move"},
                {"arms": "controller.animation.villager.arms"}
              ]
            }
            """);

        assertEquals(List.of("controller.animation.villager.general",
                "controller.animation.villager.move",
                "controller.animation.villager.arms"),
            List.copyOf(definition.animationControllers().values()));
    }

    /** A short name is looked up in both maps, because either may be the one that declared it. */
    @Test
    void resolvesAShortNameFromEitherMap() {
        ClientEntityDefinition definition = read("""
            {
              "animations": {"walk": "animation.thing.walk"},
              "animation_controllers": [{"move": "controller.animation.thing.move"}]
            }
            """);

        assertEquals("animation.thing.walk", definition.resolveAnimation("walk"));
        assertEquals("controller.animation.thing.move", definition.resolveAnimation("move"));
        assertEquals("nothing", definition.resolveAnimation("nothing"));
    }

    /** The first texture is what an entity falls back on, so the pack's order has to survive. */
    @Test
    void keepsTheTextureMapInTheOrderThePackWroteIt() {
        ClientEntityDefinition definition = read("""
            {"textures": {"zulu": "a", "alpha": "b", "mike": "c"}}
            """);

        assertEquals(List.of("zulu", "alpha", "mike"), List.copyOf(definition.textures().keySet()));
    }

    @Test
    void readsThePerAxisScaleInEitherCase() {
        assertNotNull(read("""
            {"scripts": {"scaleX": "2.0", "scaleY": "3.0", "scaleZ": "4.0"}}
            """).scaleY());

        ClientEntityDefinition lowercase = read("""
            {"scripts": {"scalex": "2.0", "scaley": "3.0", "scalez": "4.0"}}
            """);
        assertEquals(3f, lowercase.scaleY().asFloat(new MolangEnvironment()));
        assertNull(lowercase.scale());
    }

    private static ClientEntityDefinition read(String body) {
        return ClientEntityParser.read("test:thing", Json.parse(body).getAsJsonObject());
    }
}
