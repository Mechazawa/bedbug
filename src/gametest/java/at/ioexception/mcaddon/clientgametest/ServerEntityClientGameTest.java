package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.EntityProperties;
import at.ioexception.mcaddon.client.render.PosedParts;
import at.ioexception.mcaddon.client.render.Submitted;
import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * What a dedicated server decides about an add-on entity reaches the client connected to it.
 *
 * Every check here is the server changing something and the client being asked whether it heard:
 * the entity arriving as the pack's own type, a property the pack syncs to clients, the component
 * group an event switched on, and a clip `/playanimation` started, on an add-on entity and on one
 * Java draws with a model of its own. One wisp is changed while it is too far away for the client
 * to be tracking it, so what the client learns as it first sees it is the only way the state can
 * arrive.
 */
public final class ServerEntityClientGameTest implements FabricClientGameTest {
    private static final double TOLERANCE = 0.005;
    private static final double WISP_BOX_HEIGHT = 0.6;
    private static final double CALMED_SCALE = 0.75;
    private static final double CUBE_HEIGHT = 4 / 16.0;

    /** How far `animation.tp.wisp.lift` raises the wisp's one bone, in blocks. */
    private static final double LIFT = 4 / 16.0;

    /** Far past any distance a server tracks an entity at for a client. */
    private static final int FAR = 400;

    private static final int PATIENCE = 200;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestDedicatedServerContext server = Worlds.server(context);
            var connection = server.connect()) {
            Worlds.waitForChunks(connection);

            int near = summon(context, server, "~ ~ ~3", "near");
            AddonEntity wisp = arrived(context, near);
            animation(context, server, wisp);
            groups(context, server, wisp);
            lateTracked(context, server);
            vanillaAnimation(context, server);
        }
    }

    /** The client holds the entity under the server's id and as the pack's own type. */
    private static AddonEntity arrived(ClientGameTestContext context, int id) {
        Entity entity = waitForClient(context, id);
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        Assert.that(entity instanceof AddonEntity && type.equals("tp:wisp"),
            "Expected the server's tp:wisp to reach the client as an add-on tp:wisp and found a " + type
                + " of class " + entity.getClass().getSimpleName() + ".");
        Object settled = context.computeOnClient(client -> EntityProperties.of(id).get("tp:settled"));
        Assert.that(Boolean.FALSE.equals(settled),
            "Expected the client to hold tp:settled at its declared default of false and found " + settled + ".");
        return (AddonEntity) entity;
    }

    private static void animation(ClientGameTestContext context, TestServerContext server, AddonEntity wisp) {
        AABB still = context.computeOnClient(client -> Submitted.entity(wisp).box());
        Assert.that(still != null, "tp:wisp has a client definition and nothing was drawn for it.");
        Assert.near(still.minY, 0, TOLERANCE, "the lowest drawn point of tp:wisp before any clip is played");

        server.runCommand("playanimation @e[type=tp:wisp,tag=near] animation.tp.wisp.lift");
        waitForDrawn(context, wisp, bounds -> Math.abs(bounds.minY - LIFT) < TOLERANCE,
            "/playanimation of animation.tp.wisp.lift on the server never raised the client's tp:wisp by "
                + LIFT + " blocks.");
        waitForDrawn(context, wisp, bounds -> Math.abs(bounds.minY) < TOLERANCE,
            "animation.tp.wisp.lift runs once for two seconds and the client's tp:wisp was still raised after it.");
    }

    private static void groups(ClientGameTestContext context, TestServerContext server, AddonEntity wisp) {
        server.runCommand("event entity @e[type=tp:wisp,tag=near] tp:settle");
        double calmed = WISP_BOX_HEIGHT * CALMED_SCALE;
        Worlds.waitForClient(context, client -> Math.abs(wisp.getBbHeight() - calmed) < 0.001, PATIENCE,
            () -> "Expected tp:settle on the server to scale the client's tp:wisp box to " + calmed
                + " blocks high and found " + wisp.getBbHeight() + ".");

        Object settled = context.computeOnClient(client -> EntityProperties.of(wisp.getId()).get("tp:settled"));
        Assert.that(Boolean.TRUE.equals(settled),
            "Expected tp:settle on the server to set tp:settled to true on the client and found " + settled + ".");
        AABB drawn = context.computeOnClient(client -> Submitted.entity(wisp).box());
        Assert.near(drawn.getYsize(), CUBE_HEIGHT * CALMED_SCALE, TOLERANCE,
            "the drawn height of the client's tp:wisp once the server's event scaled it by " + CALMED_SCALE);
    }

    /**
     * A wisp settled while out of the client's sight arrives settled.
     *
     * It is summoned and settled in a chunk the server holds loaded far from the player, which the
     * client is told nothing about, and only then brought over.
     */
    private static void lateTracked(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("execute at @p run forceload add ~" + FAR + " ~");
        Worlds.waitForServer(context, server, ServerEntityClientGameTest::farChunkLoaded, PATIENCE,
            "The chunk " + FAR + " blocks from the player was force loaded and never loaded.");
        int far = summon(context, server, "~" + FAR + " ~ ~", "far");
        server.runCommand("event entity @e[type=tp:wisp,tag=far] tp:settle");
        Worlds.waitForServer(context, server,
            minecraft -> Math.abs(tagged(minecraft, "far").getBbHeight() - WISP_BOX_HEIGHT * CALMED_SCALE) < 0.001,
            PATIENCE, minecraft -> "Expected tp:settle to scale the far tp:wisp's box to "
                + WISP_BOX_HEIGHT * CALMED_SCALE + " blocks on the server and found "
                + tagged(minecraft, "far").getBbHeight() + ".");
        Assert.that(context.computeOnClient(client -> client.level.getEntity(far)) == null,
            "The client was already tracking a tp:wisp " + FAR + " blocks away, so nothing here is late.");

        server.runCommand("execute at @p run tp @e[type=tp:wisp,tag=far] ~-3 ~ ~3");
        Entity late = waitForClient(context, far);
        Assert.near(late.getBbHeight(), WISP_BOX_HEIGHT * CALMED_SCALE, 0.001,
            "the box height of a tp:wisp the client first saw after the server settled it");
        Object settled = context.computeOnClient(client -> EntityProperties.of(far).get("tp:settled"));
        Assert.that(Boolean.TRUE.equals(settled), "Expected a tp:wisp first seen after it was settled to carry"
            + " tp:settled = true on the client and found " + settled + ".");
    }

    private static void vanillaAnimation(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("execute at @p run summon minecraft:villager ~3 ~ ~3 {NoAI:1b}");
        LivingEntity villager = Entities.waitForClientLiving(context, "minecraft:villager");
        float before = context.computeOnClient(client -> PosedParts.of(villager)).get("head").xScale();
        Assert.near(before, 1, TOLERANCE, "the villager's head scale before any clip is played");

        server.runCommand("playanimation @e[type=minecraft:villager] animation.tp.vanilla.duck");
        Worlds.waitForClient(context, client -> PosedParts.of(villager).get("head").xScale() == 0f, PATIENCE,
            () -> "Expected /playanimation of animation.tp.vanilla.duck on the server to scale the client's "
                + "villager head to 0 and found " + PosedParts.of(villager).get("head").xScale() + ".");
    }

    /** Summons a still tp:wisp relative to the player, and answers its id once the server holds it. */
    private static int summon(ClientGameTestContext context, TestServerContext server, String where, String tag) {
        server.runCommand("execute at @p run summon tp:wisp " + where + " {NoAI:1b,Tags:[\"" + tag + "\"]}");
        Worlds.waitForServer(context, server, minecraft -> tagged(minecraft, tag) != null, PATIENCE,
            "tp:wisp was summoned at " + where + " from the player and never reached the server's world.");
        return server.computeOnServer(minecraft -> tagged(minecraft, tag).getId());
    }

    private static Entity tagged(MinecraftServer server, String tag) {
        for (Entity entity : server.overworld().getAllEntities()) {
            if (entity.entityTags().contains(tag)) return entity;
        }
        return null;
    }

    private static boolean farChunkLoaded(MinecraftServer server) {
        BlockPos player = server.getPlayerList().getPlayers().get(0).blockPosition();
        return server.overworld().isLoaded(player.offset(FAR, 0, 0));
    }

    private static Entity waitForClient(ClientGameTestContext context, int id) {
        Worlds.waitForClient(context, client -> client.level != null && client.level.getEntity(id) != null,
            PATIENCE, () -> "The server's entity " + id + " never reached the client.");
        return context.computeOnClient(client -> client.level.getEntity(id));
    }

    private static void waitForDrawn(ClientGameTestContext context, AddonEntity entity,
        java.util.function.Predicate<AABB> holds, String failure) {
        Worlds.waitForClient(context, client -> {
            AABB bounds = Submitted.entity(entity).box();
            return bounds != null && holds.test(bounds);
        }, PATIENCE, () -> failure + " The model was drawn over " + Submitted.entity(entity).box() + ".");
    }
}
