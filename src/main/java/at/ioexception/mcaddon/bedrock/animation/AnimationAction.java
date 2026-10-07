package at.ioexception.mcaddon.bedrock.animation;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.PackCommand;
import at.ioexception.mcaddon.server.ServerHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * One statement an animation's timeline or a controller state fires.
 *
 * Bedrock writes all three kinds as plain strings and tells them apart by how each begins: `@s name`
 * raises one of the entity's own events, a leading slash is a command run from the entity, and
 * anything else is Molang. The first two change the world, which a behaviour pack's clip is written
 * for and a resource pack's clip has no way to do, so each answers only where there is a server to
 * answer it.
 */
public sealed interface AnimationAction {
    /** Reads the kind out of the string, which is how Bedrock decides it too. */
    static AnimationAction of(String written) {
        String statement = written.trim();
        if (statement.startsWith("@s ")) return new Event(statement.substring(3).trim());
        if (statement.startsWith("/")) return new Command(statement.substring(1));
        return new Expression(MolangParser.parse(statement));
    }

    /**
     * Acts on the entity the clip is playing on.
     *
     * The entity is null where a clip is run with nothing standing in a world, which leaves the
     * Molang half of a timeline the only part with anywhere to land.
     */
    void run(Entity entity, MolangEnvironment environment);

    record Expression(MolangExpression statement) implements AnimationAction {
        @Override
        public void run(Entity entity, MolangEnvironment environment) {
            statement.evaluate(environment);
        }
    }

    /** One of the events the entity's own definition declares, raised on the entity itself. */
    record Event(String name) implements AnimationAction {
        @Override
        public void run(Entity entity, MolangEnvironment environment) {
            if (entity == null || !(entity.level() instanceof ServerLevel)) return;
            if (ServerHooks.state() == null) return;
            EntityEvents.trigger(entity, name, null, ServerHooks.state());
        }
    }

    /** A command run as the entity, the way {@link PackCommand} runs one. */
    record Command(String line) implements AnimationAction {
        @Override
        public void run(Entity entity, MolangEnvironment environment) {
            new PackCommand(line).runAs(entity);
        }
    }
}
