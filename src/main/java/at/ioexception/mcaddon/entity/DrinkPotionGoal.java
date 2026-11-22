package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.BedrockPotions;
import at.ioexception.mcaddon.bedrock.behaviour.BedrockUnits;
import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

/**
 * `minecraft:behavior.drink_potion`, drunk the way Java's wandering trader drinks: the potion is held
 * and used for its use duration, and its effects land when it is finished.
 *
 * The first of `potions` whose filters hold and whose `chance` comes up is drunk. While it drinks,
 * `speed_modifier` is added to the entity's movement speed, as Java's witch slows by a quarter while
 * drinking. The goal holds only the controls `control_flags` names, so its `priority` orders it
 * against goals holding one of those.
 */
public final class DrinkPotionGoal extends Goal {
    private final AddonEntity mob;
    private final JsonObject component;
    private final double slowdown;
    private ItemStack held = ItemStack.EMPTY;
    private net.minecraft.world.item.alchemy.Potion chosen;

    public DrinkPotionGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.component = component;
        this.slowdown = Goals.number(component, "speed_modifier", 0);
        setFlags(Goals.controls(component));
    }

    /** How often the selector asks a goal that is not running whether it may start. */
    private static final int ASKED_EVERY = 2;

    @Override
    public boolean canUse() {
        if (mob.isUsingItem()) return false;

        Filter.Context context = new Filter.Context(mob, mob, mob.getTarget(), ServerHooks.state());
        return Json.list(component, "potions").stream()
            .filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .filter(potion -> Goals.filter(potion, "filters").matches(context))
            .filter(potion -> mob.getRandom().nextDouble()
                < BedrockUnits.chanceOverTicks(Goals.number(potion, "chance", 1), ASKED_EVERY))
            .findFirst()
            .flatMap(potion -> BedrockPotions.numbered((int) Goals.number(potion, "id", -1)))
            .map(potion -> chosen = potion)
            .isPresent();
    }

    @Override
    public boolean canContinueToUse() {
        return mob.isUsingItem();
    }

    @Override
    public void start() {
        held = mob.getItemBySlot(EquipmentSlot.MAINHAND);
        mob.setItemSlot(EquipmentSlot.MAINHAND, Platform.potionStack(chosen));
        mob.startUsingItem(InteractionHand.MAIN_HAND);
        Platform.drinkingSlowdown(mob, slowdown);
    }

    @Override
    public void stop() {
        mob.stopUsingItem();
        mob.setItemSlot(EquipmentSlot.MAINHAND, held);
        held = ItemStack.EMPTY;
        Platform.drinkingSlowdown(mob, 0);
    }
}
