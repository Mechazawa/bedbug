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
 */
public record RenderController(
    String name,
    MolangExpression geometry,
    List<MolangExpression> textures,
    List<Map<String, MolangExpression>> partVisibility,
    MolangExpression uvOffsetU,
    MolangExpression uvOffsetV,
    Map<String, List<MolangValue>> arrays
) {
}
