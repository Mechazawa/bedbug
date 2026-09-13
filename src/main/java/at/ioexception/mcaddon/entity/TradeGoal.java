package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.bedrock.behaviour.TradeTable;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.EnumSet;

/**
 * The two halves of a Bedrock trader's attention: the shop being open, and wanting it to be.
 *
 * `minecraft:trade_table` says what is for sale and the merchant screen shows it. These two goals are
 * what the trader does around that: it stands still and faces whoever has the shop open, and before
 * anybody has opened it, it notices a player holding something it buys and holds up what it sells.
 */
public final class TradeGoal extends Goal {
    private final AddonEntity mob;
    private final boolean whileTrading;
    private final double maxDistance;
    private final double withinRadius;
    private final int interestTime;
    private final int cooldown;
    private final Filter conditions;
    private int interested;
    private int untilNext;
    private Player customer;
    private ItemStack shown = ItemStack.EMPTY;

    private TradeGoal(AddonEntity mob, JsonObject component, boolean whileTrading) {
        this.mob = mob;
        this.whileTrading = whileTrading;
        this.maxDistance = Goals.number(component, "max_distance_from_player", 8);
        this.withinRadius = Math.max(1, Goals.number(component, "within_radius", 8));
        this.interestTime = Goals.ticks(component, "interest_time", 45);
        this.cooldown = Goals.ticks(component, "cooldown", 2);
        this.conditions = Goals.filter(component, "filters");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** `minecraft:behavior.trade_with_player`, which holds the trader still while the shop is open. */
    public static TradeGoal whileTrading(AddonEntity mob, JsonObject component) {
        return new TradeGoal(mob, component, true);
    }

    /** `minecraft:behavior.trade_interest`, which shows a likely customer what is for sale. */
    public static TradeGoal showingWares(AddonEntity mob, JsonObject component) {
        return new TradeGoal(mob, component, false);
    }

    @Override
    public boolean canUse() {
        if (whileTrading) {
            customer = mob.getTradingPlayer();
            return customer != null && mob.distanceTo(customer) <= maxDistance
                && conditions.matches(
                    new Filter.Context(mob, customer, customer, ServerHooks.state()));
        }

        if (untilNext-- > 0 || mob.getTradingPlayer() != null) return false;

        customer = mob.level().getEntitiesOfClass(Player.class,
                mob.getBoundingBox().inflate(withinRadius)).stream()
            .filter(this::carriesSomethingWanted)
            .min(Comparator.comparingDouble(mob::distanceToSqr))
            .orElse(null);
        return customer != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (whileTrading) {
            return mob.getTradingPlayer() != null
                && mob.distanceTo(mob.getTradingPlayer()) <= maxDistance;
        }
        return customer != null && interested < interestTime
            && carriesSomethingWanted(customer) && mob.getTradingPlayer() == null;
    }

    @Override
    public void start() {
        interested = 0;
        if (whileTrading) return;

        shown = mob.getMainHandItem().copy();
        forSale().ifPresent(offer -> mob.setItemSlot(EquipmentSlot.MAINHAND, offer));
    }

    @Override
    public void tick() {
        if (customer == null) return;

        interested++;
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(customer, 30f, 30f);
    }

    @Override
    public void stop() {
        if (whileTrading) return;

        untilNext = cooldown;
        mob.setItemSlot(EquipmentSlot.MAINHAND, shown);
        shown = ItemStack.EMPTY;
    }

    /** Whether the player is holding something one of the trader's own offers asks for. */
    private boolean carriesSomethingWanted(Player player) {
        TradeTable trades = AddonEntities.tradesOf(mob.getType());
        if (trades == null || player.getMainHandItem().isEmpty()) return false;

        String held = BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString();
        return trades.offers().stream()
            .anyMatch(offer -> held.equals(offer.cost().item())
                || (offer.secondCost() != null && held.equals(offer.secondCost().item())));
    }

    /** The first thing the trader sells, which is what it holds up to be noticed. */
    private java.util.Optional<ItemStack> forSale() {
        TradeTable trades = AddonEntities.tradesOf(mob.getType());
        if (trades == null) return java.util.Optional.empty();

        return trades.offers().stream()
            .map(offer -> stack(offer.result().item()))
            .filter(stack -> !stack.isEmpty())
            .findFirst();
    }

    private static ItemStack stack(String item) {
        net.minecraft.resources.Identifier id = item == null
            ? null : net.minecraft.resources.Identifier.tryParse(item);
        return id == null ? ItemStack.EMPTY
            : BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }
}
