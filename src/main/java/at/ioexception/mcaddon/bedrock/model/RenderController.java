package at.ioexception.mcaddon.bedrock.model;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;

import java.util.List;
import java.util.Map;

/**
 * One drawing pass: which geometry, which textures, and which bones are left out.
 *
 * An entity lists several. The villager alone has seven: the body, the clothing overlay, the trade
 * tier badge, the held sign, the writing on that sign and the costume.
 *
 * `uvOffset` slides the texture under the model. The add-on uses it for exactly one thing: the
 * sign, whose 87 possible messages are drawn one above the other in a single tall strip.
 *
 * The four colour groups tint the pass. `colour` multiplies the texture, `overlay` is laid over it
 * by its own alpha, and the other two replace the overlay while the entity is hurt or burning.
 */
public record RenderController(
    String name,
    MolangExpression geometry,
    List<MolangExpression> textures,
    List<Map<String, MolangExpression>> partVisibility,
    List<Map<String, MolangExpression>> materials,
    MolangExpression uvOffsetU,
    MolangExpression uvOffsetV,
    MolangExpression uvScaleU,
    MolangExpression uvScaleV,
    /** Draws the pass at full brightness, whatever the light where the entity stands. */
    boolean ignoresLighting,
    /** What the light reaching the pass is multiplied by. Null leaves it as the world lit it. */
    MolangExpression lightMultiplier,
    Map<String, List<MolangValue>> arrays,
    ControllerColour colour,
    ControllerColour overlay,
    ControllerColour hurt,
    ControllerColour onFire
) {
}
