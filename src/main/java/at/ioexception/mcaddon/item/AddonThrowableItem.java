package at.ioexception.mcaddon.item;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * An item whose use launches the entity a pack named.
 *
 * Bedrock splits this over two components: `minecraft:throwable` says that holding right click
 * launches something, and `minecraft:projectile` says what. The entity type is looked up at the
 * moment of the throw, because a pack's own types and its items register in the same pass.
 *
 * A pack entity carrying its own `minecraft:projectile` flies on the arc that component describes,
 * at its own power scaled by `launch_power_scale`, and remembers who threw it. An entity Java knows
 * as a projectile is aimed the same way on Java's arc. Anything else is given the velocity and
 * nothing more, which is as far as a pack that throws an ordinary mob can be carried.
 */
public final class AddonThrowableItem extends Item {
    private final String projectile;
    private final float power;

    private AddonThrowableItem(Properties properties, String projectile, float power) {
        super(properties);
        this.projectile = projectile;
        this.power = power;
    }

    /** An item has carried its own registry id since 1.21.2, so it must be set before registering. */
    public static AddonThrowableItem build(Properties properties, Identifier id, String projectile,
        float power) {
        //? if >=1.21.2 {
        return new AddonThrowableItem(properties.setId(ResourceKey.create(Registries.ITEM, id)),
            projectile, power);
        //?} else {
        /*return new AddonThrowableItem(properties, projectile, power);
        *///?}
    }

    //? if >=1.21.2 {
    @Override
    public net.minecraft.world.InteractionResult use(Level level, Player player, InteractionHand hand) {
        launch(level, player);
        spend(player, hand);
        return net.minecraft.world.InteractionResult.SUCCESS;
    }
    //?} else {
    /*@Override
    public net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack> use(
        Level level, Player player, InteractionHand hand) {
        launch(level, player);
        net.minecraft.world.item.ItemStack held = player.getItemInHand(hand);
        spend(player, hand);
        return net.minecraft.world.InteractionResultHolder.sidedSuccess(held, level.isClientSide());
    }
    *///?}

    private void launch(Level level, Player player) {
        if (level.isClientSide()) return;

        EntityType<?> type = AddonEntities.typeOf(projectile);
        if (type == null) return;

        Entity thrown = create(type, level);
        if (thrown == null) return;

        if (thrown instanceof AddonEntity own && own.fireFrom(player, power)) {
            level.addFreshEntity(own);
            return;
        }

        thrown.setPos(player.getX(), player.getEyeY() - 0.1, player.getZ());
        if (thrown instanceof Projectile shot) {
            shot.setOwner(player);
            shot.shootFromRotation(player, player.getXRot(), player.getYRot(), 0f, power, 1f);
        } else {
            thrown.setDeltaMovement(player.getLookAngle().scale(power));
        }
        level.addFreshEntity(thrown);
    }

    /** From 26 an entity says why it is being created, and a thrown one is triggered by its thrower. */
    private static Entity create(EntityType<?> type, Level level) {
        //? if >=26 {
        return type.create(level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
        //?} else {
        /*return type.create(level);
        *///?}
    }

    private static void spend(Player player, InteractionHand hand) {
        if (!player.getAbilities().instabuild) player.getItemInHand(hand).shrink(1);
    }
}
