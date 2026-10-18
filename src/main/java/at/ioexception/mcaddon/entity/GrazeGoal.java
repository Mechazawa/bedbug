package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * `minecraft:behavior.eat_block`: a sheep's grazing, on the blocks the pack pairs up.
 *
 * Java's own goal eats grass and nothing else, at a chance it fixes by age. Bedrock states the chance
 * as Molang asked each tick, and the block at the feet is tried before the one underfoot, which is
 * where the short grass and the grass block of Mojang's own pairs stand.
 */
public final class GrazeGoal extends Goal {
    private final AddonEntity mob;
    private final String successChance;
    private final int eatTicks;
    private final Map<Block, Block> pairs;
    private final String eatEvent;
    private BlockPos eating;
    private int eatAt;
    private boolean eaten;

    public GrazeGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        JsonElement chance = component.get("success_chance");
        this.successChance = chance != null && chance.isJsonPrimitive() ? chance.getAsString() : "0.02";
        this.eatTicks = Goals.ticks(component, "time_until_eat", 1.8);
        this.pairs = pairs(component);
        this.eatEvent = Goals.event(component, "on_eat");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (mob.getRandom().nextDouble() >= mob.answer(successChance).number()) return false;

        eating = edible();
        return eating != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !eaten;
    }

    @Override
    public void start() {
        eatAt = mob.tickCount + eatTicks;
        eaten = false;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (mob.tickCount < eatAt) return;

        eaten = true;
        Block replacement = pairs.get(mob.level().getBlockState(eating).getBlock());
        if (replacement == null) return;

        mob.level().levelEvent(2001, eating, Block.getId(mob.level().getBlockState(eating)));
        mob.level().setBlock(eating, replacement.defaultBlockState(), Block.UPDATE_ALL);
        if (eatEvent != null) EntityEvents.trigger(mob, eatEvent, null, ServerHooks.state());
    }

    private BlockPos edible() {
        return Stream.of(mob.blockPosition(), mob.blockPosition().below())
            .filter(at -> pairs.containsKey(mob.level().getBlockState(at).getBlock()))
            .findFirst()
            .orElse(null);
    }

    /** What the schema gives a pack that names no pairs, which are Mojang's sheep's. */
    private static final String SHEEP_PAIRS = "[{\"eat_block\": \"grass\", \"replace_block\": \"dirt\"},"
        + " {\"eat_block\": \"short_grass\", \"replace_block\": \"air\"},"
        + " {\"eat_block\": \"fern\", \"replace_block\": \"air\"}]";

    private static Map<Block, Block> pairs(JsonObject component) {
        JsonObject written = component.has("eat_and_replace_block_pairs") ? component
            : JsonParser.parseString("{\"eat_and_replace_block_pairs\": " + SHEEP_PAIRS + "}").getAsJsonObject();
        Map<Block, Block> pairs = new LinkedHashMap<>();
        Json.list(written, "eat_and_replace_block_pairs").stream()
            .filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .forEach(pair -> {
                Block eat = AddonBlocks.named(Json.text(pair, "eat_block", ""));
                Block replace = AddonBlocks.named(Json.text(pair, "replace_block", "air"));
                if (eat != null && replace != null) pairs.put(eat, replace);
            });
        return pairs;
    }
}
