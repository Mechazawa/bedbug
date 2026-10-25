package at.ioexception.mcaddon.bedrock.entity;

import at.ioexception.mcaddon.bedrock.animation.AnimationPlayer;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.server.ServerHooks;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An entity a Molang value refers to, read on the side the expression runs on.
 *
 * A client reads the scope of the animator drawing the entity, which shows only the variables its
 * client definition names `public`. A server reads the scope of the entity's behaviour pack script,
 * whose variables a pack has no way to mark, so every one of them is readable. An entity with no
 * script of its own on that side still answers its queries.
 */
public record EntityReference(Entity entity) implements MolangValue.Reference {
    private static Function<Entity, MolangEnvironment> onClient = entity -> null;

    /** How a client finds an entity's scope, which only client code can look up. */
    public static void readOnClientWith(Function<Entity, MolangEnvironment> scopes) {
        onClient = scopes;
    }

    @Override
    public MolangEnvironment scope() {
        if (entity.isRemoved()) return null;
        if (entity.level().isClientSide()) return onClient.apply(entity);
        if (entity instanceof AddonEntity addon) return addon.scope();
        return questions(entity,
            () -> ServerHooks.state() == null ? Map.of() : ServerHooks.state().properties(entity.getUUID()));
    }

    /** A scope that answers the entity's queries and holds no variables of the entity's own. */
    public static MolangEnvironment questions(Entity entity, Supplier<Map<String, Object>> properties) {
        AnimationPlayer asked = new AnimationPlayer(null, entity);
        MolangEnvironment scope = asked.environment();
        scope.setQueries(new EntityQueries(entity, asked, EntityQueries.Drawn.NONE, properties, false));
        scope.setLifeTime(entity.tickCount / 20f);
        if (!entity.level().isClientSide() && ServerHooks.state() != null) {
            scope.shareTemporaries(ServerHooks.state().temporaries());
        }
        return scope;
    }
}
