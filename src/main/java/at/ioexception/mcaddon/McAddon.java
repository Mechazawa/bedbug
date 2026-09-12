package at.ioexception.mcaddon;

import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McAddon implements ModInitializer {
    public static final String MOD_ID = "mcaddon";
    public static final Logger LOGGER = LoggerFactory.getLogger("Bedrock Add-ons");

    public static Identifier id(String path) {
        return Platform.id(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Bedrock add-on loader starting.");
    }
}
