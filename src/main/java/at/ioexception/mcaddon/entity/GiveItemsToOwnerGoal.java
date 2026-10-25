package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Brings the owner whatever the entity picked up and throws it at their feet.
 *
 * `minecraft:behavior.go_and_give_items_to_owner` is how a Bedrock pet hands over what it found.
 * Java has nothing for it: its own fox drops what it carries when it is fed rather than delivering
 * it, so the walk, the throw and the arc the pack asked for all happen here.
 */
public final class GiveItemsToOwnerGoal extends Goal {
    private final AddonEntity mob;
    private final double runSpeed;
    private final double reachDistance;
    private final double throwForce;
    private final double verticalMultiplier;
    private final String throwSound;
    private final List<String> thrownEvents = new ArrayList<>();

    public GiveItemsToOwnerGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.runSpeed = Goals.number(component, "run_speed", 1);
        this.reachDistance = Goals.number(component, "reach_mob_distance", 3);
        this.throwForce = Goals.number(component, "throw_force", 0.2);
        this.verticalMultiplier = Goals.number(component, "vertical_throw_mul", 1.5);
        String sound = Goals.text(component, "throw_sound");
        this.throwSound = sound == null ? "charge" : sound;
        for (JsonElement element : Json.list(component, "on_item_throw")) {
            String event = element.isJsonPrimitive() ? element.getAsString()
                : element.isJsonObject() ? Json.text(element.getAsJsonObject(), "event", null) : null;
            if (event != null) thrownEvents.add(event);
        }
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity owner = mob.getOwner();
        return owner != null && owner.level() == mob.level() && !mob.getMainHandItem().isEmpty();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        LivingEntity owner = mob.getOwner();
        if (owner == null) return;

        mob.getLookControl().setLookAt(owner, 30f, 30f);
        if (mob.distanceTo(owner) > reachDistance) {
            mob.getNavigation().moveTo(owner, runSpeed);
            return;
        }
        mob.getNavigation().stop();
        throwAt(owner);
    }

    /** Lets go of what the entity is holding, aimed along the arc the pack asked for. */
    private void throwAt(LivingEntity owner) {
        if (!(mob.level() instanceof ServerLevel level)) return;

        ItemStack held = mob.getMainHandItem();
        if (held.isEmpty()) return;

        mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ItemEntity dropped = new ItemEntity(level, mob.getX(), mob.getEyeY(), mob.getZ(), held.copy());

        Vec3 towards = owner.position().subtract(mob.position()).normalize();
        dropped.setDeltaMovement(towards.x * throwForce,
            towards.y * throwForce + throwForce * verticalMultiplier, towards.z * throwForce);
        level.addFreshEntity(dropped);
        mob.makeNoise(throwSound);

        thrownEvents.forEach(event -> EntityEvents.trigger(mob, event, owner, ServerHooks.state()));
    }
}
