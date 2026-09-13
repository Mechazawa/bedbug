package at.ioexception.mcaddon.item;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

/**
 * A record: an item a jukebox plays when it is put in.
 *
 * `minecraft:record` names the sound, how long it lasts and what a comparator beside the jukebox
 * reads. Java keeps all three in a jukebox song, which the converter writes into the data pack, and
 * a song lives in a registry the world loads rather than one the game is built with. That is why
 * the song is looked up as the record goes in rather than written onto the item at registration.
 *
 * 1.20.1 has no jukebox songs, and a record there is an ordinary item.
 */
public final class AddonRecordItem extends Item {
    private final Identifier song;

    private AddonRecordItem(Properties properties, Identifier song) {
        super(properties);
        this.song = song;
    }

    /** An item has carried its own registry id since 1.21.2, so it must be set before registering. */
    public static AddonRecordItem build(Properties properties, Identifier id, Identifier song) {
        //? if >=1.21.2 {
        return new AddonRecordItem(properties.setId(ResourceKey.create(Registries.ITEM, id)), song);
        //?} else {
        /*return new AddonRecordItem(properties, song);
        *///?}
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() == null) return InteractionResult.PASS;

        ItemStack playable = Platform.playable(context.getLevel(), context.getItemInHand(), song);
        InteractionResult put = Platform.insertIntoJukebox(context.getLevel(),
            context.getClickedPos(), playable, context.getPlayer());
        // The jukebox took the copy carrying the song, so the one in the hand is the one to spend.
        if (put.consumesAction()) context.getItemInHand().shrink(1);
        return put;
    }
}
