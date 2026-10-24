package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.resources.Identifier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game's own texture for a Bedrock vanilla texture path a pack names without shipping it.
 *
 * Bedrock reads a texture path from the pack and then from the game, so a model borrowing the
 * sign's texture means the sign everybody already has.
 */
final class VanillaTextures {
    private static final Pattern SIGN = Pattern.compile(
        "textures/entity/(?:sign(?:_(\\w+))?|(\\w+?)_(hanging_)?sign)");

    private VanillaTextures() {
    }

    /** The Java texture for the path, or null where the game has none under that name. */
    static Identifier of(String bedrockPath) {
        Matcher sign = SIGN.matcher(bedrockPath);
        if (!sign.matches()) return null;

        boolean hanging = sign.group(3) != null;
        String wood = sign.group(2) != null ? sign.group(2) : sign.group(1) == null ? "oak" : sign.group(1);
        if (wood.equals("darkoak")) wood = "dark_oak";
        // From 26.3 the sign's entity texture is gone and the block texture is laid out differently,
        // so the board is drawn from the wrong texels there.
        //? if >=26.3 {
        return Platform.id("minecraft", "textures/block/" + wood + (hanging ? "_hanging_sign" : "_sign") + ".png");
        //?} else {
        /*return Platform.id("minecraft", "textures/entity/signs/" + (hanging ? "hanging/" : "") + wood + ".png");
        *///?}
    }
}
