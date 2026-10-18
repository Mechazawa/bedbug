package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;

/** The textures an add-on entity's renderer binds, by the Bedrock path its definition names. */
public final class PackTextures {
    private PackTextures() {
    }

    /** Where the conversion filed one of the entity's textures. */
    public static Identifier of(String entity, String bedrockPath) {
        return AddonClientAssets.of(entity).models().textureId(bedrockPath);
    }

    /** The glowing layer a texture set split off one of the entity's textures, null where there is none. */
    public static Identifier glowOf(String entity, String bedrockPath) {
        String glow = AddonClientAssets.of(entity).models().converted().emissiveLayers().get(bedrockPath);
        if (glow == null) return null;

        int colon = glow.indexOf(':');
        return Platform.id(glow.substring(0, colon), "textures/" + glow.substring(colon + 1) + ".png");
    }

    /** The alpha of one texel of a texture as the game's resources hold it. */
    public static int alphaAt(Identifier texture, int x, int y) {
        try (InputStream stream = Minecraft.getInstance().getResourceManager().getResource(texture).orElseThrow()
            .open()) {
            return ImageIO.read(stream).getRGB(x, y) >>> 24;
        } catch (IOException unreadable) {
            throw new AssertionError("The texture " + texture + " could not be read.", unreadable);
        }
    }
}
