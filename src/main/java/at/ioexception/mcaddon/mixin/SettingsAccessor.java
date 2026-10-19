package at.ioexception.mcaddon.mixin;

import net.minecraft.server.dedicated.Settings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Properties;

/** `server.properties` as read, which keeps the keys Java has no setting for, Bedrock's among them. */
@Mixin(Settings.class)
public interface SettingsAccessor {
    @Accessor("properties")
    Properties mcaddon$properties();
}
