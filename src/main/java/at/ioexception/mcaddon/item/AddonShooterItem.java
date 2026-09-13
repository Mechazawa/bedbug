package at.ioexception.mcaddon.item;

import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A bow: an item drawn and then released to launch its ammunition.
 *
 * Bedrock keeps the whole weapon in data. `minecraft:shooter` lists what may be loaded and says how
 * a draw becomes power, and what flies is the ammunition's own `minecraft:projectile`, so a pack can
 * fire an entity it declared itself. Java's own bow is a class with an arrow written into it, which
 * is why none of this can be expressed as components on a plain item.
 */
public final class AddonShooterItem extends Item {
    /** What a use counts down from, high enough that a draw is never cut short by the count. */
    private static final int FOREVER = 72000;

    /** How fast a vanilla arrow leaves a fully drawn bow, which a pack's scale is applied to. */
    private static final float ARROW_SPEED = 3f;

    /** A draw shorter than this launches nothing, the same tap a vanilla bow ignores. */
    private static final float SHORTEST_DRAW = 0.1f;

    private final List<String> ammunition;
    private final float drawTicks;
    private final boolean scaleByDraw;
    private final float powerScale;

    private AddonShooterItem(Properties properties, List<String> ammunition, float drawSeconds,
        boolean scaleByDraw, float powerScale) {
        super(properties);
        this.ammunition = ammunition;
        // A pack that names no draw duration means the twenty ticks a vanilla bow takes.
        this.drawTicks = drawSeconds > 0f ? drawSeconds * 20f : 20f;
        this.scaleByDraw = scaleByDraw;
        this.powerScale = powerScale;
    }

    /** An item has carried its own registry id since 1.21.2, so it must be set before registering. */
    public static AddonShooterItem build(Properties properties, Identifier id, List<String> ammunition,
        float drawSeconds, boolean scaleByDraw, float powerScale) {
        //? if >=1.21.2 {
        return new AddonShooterItem(properties.setId(ResourceKey.create(Registries.ITEM, id)),
            ammunition, drawSeconds, scaleByDraw, powerScale);
        //?} else {
        /*return new AddonShooterItem(properties, ammunition, drawSeconds, scaleByDraw, powerScale);
        *///?}
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.BOW;
    }

    //? if >=1.21 {
    @Override
    public int getUseDuration(ItemStack stack, LivingEntity holder) {
        return FOREVER;
    }
    //?} else {
    /*@Override
    public int getUseDuration(ItemStack stack) {
        return FOREVER;
    }
    *///?}

    //? if >=1.21.2 {
    @Override
    public net.minecraft.world.InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (loaded(player) == null) return net.minecraft.world.InteractionResult.FAIL;

        player.startUsingItem(hand);
        return net.minecraft.world.InteractionResult.CONSUME;
    }
    //?} else {
    /*@Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(
        Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (loaded(player) == null) {
            return net.minecraft.world.InteractionResultHolder.fail(held);
        }

        player.startUsingItem(hand);
        return net.minecraft.world.InteractionResultHolder.consume(held);
    }
    *///?}

    //? if >=26 {
    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity holder, int timeLeft) {
        return shoot(level, holder, FOREVER - timeLeft);
    }
    //?} else {
    /*@Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity holder, int timeLeft) {
        shoot(level, holder, FOREVER - timeLeft);
    }
    *///?}

    /** Whether the draw was long enough to have launched anything. */
    private boolean shoot(Level level, LivingEntity holder, int drawn) {
        if (!(holder instanceof Player player)) return false;

        float power = scaleByDraw ? Math.min(drawn / drawTicks, 1f) : 1f;
        if (power < SHORTEST_DRAW) return false;

        ItemStack ammo = loaded(player);
        if (ammo == null) return false;
        if (level.isClientSide()) return true;

        EntityType<?> type = AddonEntities.typeOf(AddonItems.projectileOf(ammo.getItem()));
        if (type == null) return false;

        Entity shot = create(type, level);
        if (shot == null) return false;

        shot.setPos(player.getX(), player.getEyeY() - 0.1, player.getZ());
        if (shot instanceof Projectile aimed) {
            aimed.setOwner(player);
            aimed.shootFromRotation(player, player.getXRot(), player.getYRot(), 0f,
                power * ARROW_SPEED * powerScale, 1f);
        } else {
            shot.setDeltaMovement(player.getLookAngle().scale(power * ARROW_SPEED * powerScale));
        }
        level.addFreshEntity(shot);

        if (!player.getAbilities().instabuild) ammo.shrink(1);
        return true;
    }

    /**
     * The stack this would fire, or null where the player is carrying nothing it accepts.
     *
     * A creative player shoots the first kind the pack listed whether or not any is carried, which
     * is what Bedrock's `use_in_creative` asks for and what a vanilla bow does.
     */
    private ItemStack loaded(Player player) {
        Item first = null;
        for (String name : ammunition) {
            Item wanted = AddonItems.named(name);
            if (wanted == null) continue;
            if (first == null) first = wanted;

            ItemStack carried = carried(player, wanted);
            if (carried != null) return carried;
        }

        if (first == null || !player.getAbilities().instabuild) return null;
        return new ItemStack(first);
    }

    /** The stack of one kind the player is carrying, or null where none is. */
    private static ItemStack carried(Player player, Item wanted) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack candidate = player.getInventory().getItem(slot);
            if (candidate.getItem() == wanted && !candidate.isEmpty()) return candidate;
        }
        return null;
    }

    /** From 26 an entity says why it is being created, and a shot one is triggered by its shooter. */
    private static Entity create(EntityType<?> type, Level level) {
        //? if >=26 {
        return type.create(level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
        //?} else {
        /*return type.create(level);
        *///?}
    }
}
