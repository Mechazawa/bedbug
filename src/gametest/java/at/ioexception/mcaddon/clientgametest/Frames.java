package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.Submitted;
import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.function.Function;
import java.util.function.Predicate;

/** Waiting for what an entity's renderer submits to change the way a server command asked. */
final class Frames {
    /** Ticks a frame may take to show a change, which covers the command reaching the client. */
    private static final int PATIENCE = 100;

    private Frames() {
    }

    static Submitted waitFor(ClientGameTestContext context, AddonEntity entity, Predicate<Submitted> holds,
        String failure) {
        return waitFor(context, entity, holds, last -> failure);
    }

    /** The same, with the failure worded from the last frame so it can say what was drawn instead. */
    static Submitted waitFor(ClientGameTestContext context, AddonEntity entity, Predicate<Submitted> holds,
        Function<Submitted, String> failure) {
        Submitted frame = null;
        for (int tick = 0; tick < PATIENCE; tick++) {
            frame = context.computeOnClient(client -> Submitted.entity(entity));
            if (holds.test(frame)) return frame;
            context.waitTick();
        }
        throw new AssertionError(failure.apply(frame));
    }
}
