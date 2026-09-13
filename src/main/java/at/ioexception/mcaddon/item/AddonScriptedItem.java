package at.ioexception.mcaddon.item;

import at.ioexception.mcaddon.script.ScriptHost;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * An item a pack's own script decides the behaviour of.
 *
 * `minecraft:custom_components` lists names the pack registered from its script as the world starts
 * up, and each name carries the callbacks Bedrock runs at the moments an item has. The item itself
 * does nothing: everything about what happens is in the pack.
 */
public final class AddonScriptedItem extends Item {
    private final List<String> scripted;

    private AddonScriptedItem(Properties properties, List<String> scripted) {
        super(properties);
        this.scripted = scripted;
    }

    /** An item has carried its own registry id since 1.21.2, so it must be set before registering. */
    public static AddonScriptedItem build(Properties properties, Identifier id, List<String> scripted) {
        //? if >=1.21.2 {
        return new AddonScriptedItem(properties.setId(ResourceKey.create(Registries.ITEM, id)), scripted);
        //?} else {
        /*return new AddonScriptedItem(properties, scripted);
        *///?}
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() == null) return InteractionResult.PASS;

        JsonObject fields = new JsonObject();
        fields.addProperty("source", context.getPlayer().getStringUUID());
        fields.addProperty("player", context.getPlayer().getStringUUID());
        JsonObject where = new JsonObject();
        where.addProperty("dimension",
            at.ioexception.mcaddon.platform.Platform.idOf(context.getLevel().dimension()).toString());
        where.addProperty("x", context.getClickedPos().getX());
        where.addProperty("y", context.getClickedPos().getY());
        where.addProperty("z", context.getClickedPos().getZ());
        fields.add("block", where);

        return run("onUseOn", fields) ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    //? if >=1.21.2 {
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        return used(player) ? InteractionResult.FAIL : InteractionResult.PASS;
    }
    //?} else {
    /*@Override
    public net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack> use(
        Level level, Player player, InteractionHand hand) {
        net.minecraft.world.item.ItemStack held = player.getItemInHand(hand);
        return used(player) ? net.minecraft.world.InteractionResultHolder.fail(held)
            : net.minecraft.world.InteractionResultHolder.pass(held);
    }
    *///?}

    private boolean used(Player player) {
        JsonObject fields = new JsonObject();
        fields.addProperty("source", player.getStringUUID());
        fields.addProperty("player", player.getStringUUID());
        return run("onUse", fields);
    }

    /** Whether any of the pack's components said the use should not go ahead. */
    private boolean run(String callback, JsonObject fields) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return false;

        String arguments = fields.toString();
        boolean cancelled = false;
        for (String name : scripted) {
            cancelled |= scripts.runItemComponent(name, callback, arguments);
        }
        return cancelled;
    }
}
