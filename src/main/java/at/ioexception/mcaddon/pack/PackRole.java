package at.ioexception.mcaddon.pack;

public enum PackRole {
    /** Models, textures, animations, sounds and text: everything the client draws and plays. */
    RESOURCE,
    /** Entities, items, blocks, recipes, trading, functions and scripts. */
    BEHAVIOUR,
    /** A skin pack or world template, which this mod has nothing to do with. */
    OTHER
}
