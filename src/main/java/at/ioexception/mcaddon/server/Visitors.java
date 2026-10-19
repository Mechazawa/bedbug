package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.state.AddonState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The players `permission set <player> visitor` put at Bedrock's lowest level, by uuid with the name
 * each last had, kept beside the world.
 *
 * Bedrock's visitor has every one of the player permissions off: build, mine, doors and switches,
 * open containers, attack players, attack mobs, operator commands and teleport. It may still trade,
 * ride and use what it carries. Each is refused here on the server, through the hooks a click from
 * any client reaches, so a client without this mod is held to it too: a block used, placed or broken,
 * an item that places a block or an entity, an item frame used, every entity struck and any damage
 * the visitor is the cause of. A container entity or a mount's inventory is refused where it opens,
 * in `VisitorMenuMixin`. A visitor is never an operator, which is where the last two go.
 */
public final class Visitors {
    private static final String SECTION = "visitors";

    private static final Map<UUID, String> VISITORS = new LinkedHashMap<>();

    private Visitors() {
    }

    public static void load(AddonState state) {
        VISITORS.clear();
        JsonElement saved = state == null ? null : state.section(SECTION);
        if (saved == null || !saved.isJsonObject()) return;

        saved.getAsJsonObject().entrySet().forEach(entry ->
            VISITORS.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString()));
    }

    public static boolean is(Entity player) {
        return player instanceof ServerPlayer && VISITORS.containsKey(player.getUUID());
    }

    /** Puts the player at the visitor level or takes them off it. */
    public static void set(ServerPlayer player, boolean visitor) {
        boolean changed = visitor
            ? !player.getScoreboardName().equals(VISITORS.put(player.getUUID(), player.getScoreboardName()))
            : VISITORS.remove(player.getUUID()) != null;
        AddonState state = ServerHooks.state();
        if (!changed || state == null) return;

        JsonObject saved = new JsonObject();
        VISITORS.forEach((uuid, name) -> saved.addProperty(uuid.toString(), name));
        state.setSection(SECTION, saved);
    }

    public static List<String> names() {
        return List.copyOf(VISITORS.values());
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> refused(player));
        AttackBlockCallback.EVENT.register((player, level, hand, position, direction) -> refused(player));
        PlayerBlockBreakEvents.BEFORE.register((level, player, position, state, blockEntity) -> !is(player));
        AttackEntityCallback.EVENT.register((player, level, hand, target, hit) -> refused(player));
        UseEntityCallback.EVENT.register((player, level, hand, target, hit) ->
            target instanceof ItemFrame ? refused(player) : InteractionResult.PASS);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !is(source.getEntity()));
        // Fabric's use-item callback answers with the stack it leaves behind; from 26 on it answers
        // with the outcome alone.
        //? if >=26 {
        UseItemCallback.EVENT.register((player, level, hand) ->
            places(player.getItemInHand(hand).getItem()) ? refused(player) : InteractionResult.PASS);
        //?} else {
        /*UseItemCallback.EVENT.register((player, level, hand) ->
            places(player.getItemInHand(hand).getItem()) && is(player)
                ? net.minecraft.world.InteractionResultHolder.fail(player.getItemInHand(hand))
                : net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand)));
        *///?}
    }

    private static InteractionResult refused(Player player) {
        return is(player) ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    /** The items whose use away from a block still puts a block or an entity into the world. */
    private static boolean places(Item item) {
        return item instanceof BlockItem || item instanceof BucketItem || item instanceof BoatItem
            || item instanceof SpawnEggItem;
    }
}
