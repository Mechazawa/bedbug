package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Draws the weapon it is holding while it still has something to load it with.
 *
 * `minecraft:behavior.charge_held_item` is what makes a Bedrock piglin raise its crossbow. Java says
 * the same thing by having the mob use the item in its hand, so the goal holds the use down while the
 * entity has a target and one of the items the pack listed.
 */
public final class ChargeHeldItemGoal extends Goal {
    private final AddonEntity mob;
    private final Set<Item> ammunition = new LinkedHashSet<>();

    public ChargeHeldItemGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        for (JsonElement element : Json.list(component, "items")) {
            String name = element.isJsonPrimitive() ? element.getAsString()
                : element.isJsonObject() ? Json.text(element.getAsJsonObject(), "item", null) : null;
            Identifier id = name == null ? null
                : Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
            if (id != null) BuiltInRegistries.ITEM.getOptional(id).ifPresent(ammunition::add);
        }
        setFlags(EnumSet.of(Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        ItemStack held = mob.getMainHandItem();
        return mob.getTarget() != null && !held.isEmpty() && !mob.isUsingItem() && loaded();
    }

    @Override
    public boolean canContinueToUse() {
        return mob.isUsingItem() && mob.getTarget() != null;
    }

    @Override
    public void start() {
        mob.startUsingItem(InteractionHand.MAIN_HAND);
    }

    @Override
    public void stop() {
        mob.stopUsingItem();
    }

    /** Whether the entity is carrying one of the items the pack said charges the weapon. */
    private boolean loaded() {
        return ammunition.isEmpty() || ammunition.contains(mob.getOffhandItem().getItem())
            || ammunition.contains(mob.getMainHandItem().getItem());
    }
}
