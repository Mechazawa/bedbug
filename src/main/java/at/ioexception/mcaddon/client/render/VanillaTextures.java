package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
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

    /**
     * Each face of Bedrock's 64 by 32 sign, which is also the layout of Java's sign entity texture up
     * to 26.1.2, and where Java's 32 by 32 sign block texture holds that face. The block model draws
     * no top on its post, so both ends of the post take its bottom.
     */
    private static final List<Face> SIGN_FACES = List.of(
        new Face(2, 0, 24, 2, 0, 0, false),
        new Face(26, 0, 24, 2, 0, 28, true),
        new Face(0, 2, 2, 12, 24, 16, false),
        new Face(2, 2, 24, 12, 0, 2, false),
        new Face(26, 2, 2, 12, 24, 2, false),
        new Face(28, 2, 24, 12, 0, 16, false),
        new Face(2, 14, 2, 2, 28, 30, false),
        new Face(4, 14, 2, 2, 28, 30, false),
        new Face(0, 16, 2, 14, 30, 16, false),
        new Face(2, 16, 2, 14, 28, 0, false),
        new Face(4, 16, 2, 14, 30, 0, false),
        new Face(6, 16, 2, 14, 28, 16, false));

    private VanillaTextures() {
    }

    /** The Java texture for the path, or null where the game has none under that name. */
    static Identifier of(String bedrockPath) {
        Matcher sign = SIGN.matcher(bedrockPath);
        if (!sign.matches()) return null;

        boolean hanging = sign.group(3) != null;
        String wood = sign.group(2) != null ? sign.group(2) : sign.group(1) == null ? "oak" : sign.group(1);
        if (wood.equals("darkoak")) wood = "dark_oak";
        //? if >=26.2 {
        if (hanging) return Platform.id("minecraft", "textures/block/" + wood + "_hanging_sign.png");
        return composedSign(wood);
        //?} else {
        /*return Platform.id("minecraft", "textures/entity/signs/" + (hanging ? "hanging/" : "") + wood + ".png");
        *///?}
    }

    /** Bedrock's sign texture for the wood, made from Java's sign block texture, or null where Java has none. */
    private static Identifier composedSign(String wood) {
        Identifier block = Platform.id("minecraft", "textures/block/" + wood + "_sign.png");
        Identifier composed = Platform.id(McAddon.MOD_ID, "textures/vanilla/sign/" + wood + ".png");
        Minecraft client = Minecraft.getInstance();
        try (InputStream stream = client.getResourceManager().open(block);
             NativeImage source = NativeImage.read(stream)) {
            NativeImage sign = new NativeImage(64, 32, true);
            SIGN_FACES.forEach(face -> source.copyRect(sign, face.fromX(), face.fromY(), face.x(), face.y(),
                face.width(), face.height(), false, face.flipped()));
            client.getTextureManager().register(composed, new DynamicTexture(composed::toString, sign));
            return composed;
        } catch (IOException missing) {
            return null;
        }
    }

    private record Face(int x, int y, int width, int height, int fromX, int fromY, boolean flipped) {
    }
}
