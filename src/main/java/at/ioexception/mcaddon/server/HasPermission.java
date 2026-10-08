package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.input.InputPermission;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The input permissions Bedrock's `haspermission` selector argument asks a player to have on or off.
 *
 * `@a[haspermission={camera=disabled,movement=enabled}]` keeps the players whose categories are each
 * in the state named. A category answers for itself, as `/inputpermission query` reads it, and only a
 * player has input permissions, so no other entity is ever kept.
 */
public record HasPermission(Map<InputPermission, Boolean> wanted) implements Predicate<Entity> {
    private static final DynamicCommandExceptionType UNREADABLE = new DynamicCommandExceptionType(
        what -> Component.literal("haspermission cannot read " + what));

    static HasPermission read(StringReader reader) throws CommandSyntaxException {
        Map<InputPermission, Boolean> wanted = new EnumMap<>(InputPermission.class);
        for (Map.Entry<String, String> field : BracedArguments.read(reader)) {
            InputPermission permission = InputPermission.tryFrom(field.getKey());
            if (permission == null) throw UNREADABLE.createWithContext(reader, "the permission " + field.getKey());

            switch (field.getValue().toLowerCase(Locale.ROOT)) {
                case "enabled" -> wanted.put(permission, true);
                case "disabled" -> wanted.put(permission, false);
                default -> throw UNREADABLE.createWithContext(reader, "the state " + field.getValue());
            }
        }
        return new HasPermission(wanted);
    }

    /** Whether a player whose categories are switched on as this says has every state asked for. */
    public boolean answeredBy(Predicate<InputPermission> enabled) {
        return wanted.entrySet().stream().allMatch(state -> enabled.test(state.getKey()) == state.getValue());
    }

    @Override
    public boolean test(Entity entity) {
        return entity instanceof ServerPlayer player
            && answeredBy(permission -> PlayerInputs.enabled(player, permission));
    }
}
