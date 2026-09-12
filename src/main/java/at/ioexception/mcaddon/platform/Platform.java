package at.ioexception.mcaddon.platform;

import net.minecraft.resources.Identifier;

/**
 * Every call whose spelling differs between the supported Minecraft versions.
 *
 * Keeping them here is what lets the rest of the mod read as ordinary code. Whole-class renames
 * are handled by the string replacements in the buildscript instead.
 */
public final class Platform {
    private Platform() {
    }

    /** The factory replaced the public constructor in 1.21. */
    public static Identifier id(String namespace, String path) {
        //? if >=1.21 {
        return Identifier.fromNamespaceAndPath(namespace, path);
        //?} else {
        /*return new Identifier(namespace, path);
        *///?}
    }
}
