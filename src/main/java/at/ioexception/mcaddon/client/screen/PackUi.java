package at.ioexception.mcaddon.client.screen;

import at.ioexception.mcaddon.bedrock.ui.UiDefinitions;
import at.ioexception.mcaddon.bedrock.ui.UiPlaced;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.resource.AddonSet;
import net.minecraft.resources.Identifier;

/** The JSON UI of the active add-ons' resource packs, and the textures its images name. */
public final class PackUi {
    private static volatile UiDefinitions definitions = UiDefinitions.EMPTY;

    private PackUi() {
    }

    public static void read(AddonSet set) {
        definitions = UiDefinitions.read(set.converted().stream().map(ConvertedPack::pack).toList());
    }

    public static UiDefinitions definitions() {
        return definitions;
    }

    /** The texture a pack's own texture path became, or null where no active pack ships it. */
    public static Identifier texture(String bedrockPath) {
        String path = bedrockPath.replaceFirst("\\.(png|tga)$", "");
        return ActiveAddons.active().converted().stream()
            .map(converted -> converted.texturePaths().get(path))
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .map(mapped -> Platform.id(mapped.substring(0, mapped.indexOf(':')),
                "textures/" + mapped.substring(mapped.indexOf(':') + 1) + ".png"))
            .orElse(null);
    }

    /** Draws an image control's texture over its rectangle; a texture no pack ships draws nothing. */
    public static void drawImage(Platform.Painter painter, UiPlaced image) {
        Identifier texture = image.texture().isEmpty() ? null : texture(image.texture());
        if (texture != null) painter.image(texture, image.x(), image.y(), image.width(), image.height());
    }
}
