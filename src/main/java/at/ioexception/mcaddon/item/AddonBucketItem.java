package at.ioexception.mcaddon.item;

import at.ioexception.mcaddon.entity.AddonEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.material.Fluids;

/**
 * A bucket of water holding one of a pack's own entities, which `minecraft:bucketable` lets a player
 * scoop up.
 *
 * Java's own `MobBucketItem` carries the entity out of the water and back into it, writing what it
 * keeps about the entity into the bucket and handing it back as the entity is let go, so this is
 * that item with the name a pack's entity has.
 */
public final class AddonBucketItem extends MobBucketItem {
    private final EntityType<AddonEntity> held;

    private AddonBucketItem(EntityType<AddonEntity> held, Properties properties) {
        super(held, Fluids.WATER, SoundEvents.BUCKET_EMPTY_FISH, properties);
        this.held = held;
    }

    /** An item has carried its own registry id since 1.21.2, so it must be set before registering. */
    public static AddonBucketItem build(EntityType<AddonEntity> held, Identifier id) {
        Properties properties = new Properties().stacksTo(1);
        //? if >=1.21.2 {
        properties = properties.setId(ResourceKey.create(Registries.ITEM, id))
            .component(net.minecraft.core.component.DataComponents.BUCKET_ENTITY_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY);
        //?} else if >=1.21 {
        /*properties = properties.component(net.minecraft.core.component.DataComponents.BUCKET_ENTITY_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY);
        *///?}
        return new AddonBucketItem(held, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.mcaddon.entity_bucket", held.getDescription());
    }
}
