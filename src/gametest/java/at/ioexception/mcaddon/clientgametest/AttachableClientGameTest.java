package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.Submitted;
import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.AABB;

/**
 * What an entity wears and holds is drawn where its own model says, and only where it allows it.
 *
 * `tp:visor`'s attachable binds its root bone with `q.item_slot_to_bone_name(c.item_slot)`, so worn
 * on the head it hangs off the wearer's `helmet` bone and held it hangs off `rightItem`. Its one
 * cube is nine pixels across and three tall, starting at its own pivot.
 *
 * - `tp:sprite` enables attachables, holds items at twice the size and draws them at full brightness.
 * - `tp:warden` draws the same geometry and declares none of that, so it wears nothing and holds an
 *   item at the ordinary size in the light around it.
 * - `tp:prism` enables attachables and hides armour, so the visor is drawn in its hand and not on its
 *   head; a held item is drawn in place of nothing only while its `hide_held_items` expression,
 *   `q.mark_variant == 1`, is false.
 */
public final class AttachableClientGameTest implements FabricClientGameTest {
    private static final double TOLERANCE = 0.01;

    /** How far the visor's corner reaches from the bone it hangs off, across the diagonal of its cube. */
    private static final double VISOR_REACH = Math.sqrt(2) * 4.5 / 16;

    /** The young sprite's helmet bone, and how far its idle animation moves it up and down. */
    private static final double SPRITE_HELMET = 14 / 16.0;
    private static final double SPRITE_BOB = 0.5 / 16.0;

    /** The round prism's rightItem bone, which is where its hand is. */
    private static final double PRISM_HAND = 2 / 16.0;

    private static final int FULL_BRIGHT = 0xF000F0;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("summon tp:sprite ~2 ~ ~3 {NoAI:1b}");
            world.getServer().runCommand("summon tp:warden ~-2 ~ ~3 {NoAI:1b}");
            world.getServer().runCommand("summon tp:prism ~ ~ ~5 {NoAI:1b}");
            AddonEntity sprite = Entities.waitForClient(context, "tp:sprite");
            AddonEntity warden = Entities.waitForClient(context, "tp:warden");
            AddonEntity prism = Entities.waitForClient(context, "tp:prism");

            worn(context, world, sprite, warden);
            held(context, world, sprite, warden);
            prism(context, world, prism);
        }
    }

    private static void worn(ClientGameTestContext context, TestSingleplayerContext world, AddonEntity sprite,
        AddonEntity warden) {
        world.getServer().runCommand("item replace entity @e[type=tp:sprite] armor.head with tp:visor");
        world.getServer().runCommand("item replace entity @e[type=tp:warden] armor.head with tp:visor");
        Submitted dressed = Frames.waitFor(context, sprite, frame -> frame.geometry().size() == 3,
            "tp:sprite enables attachables and wore tp:visor on its head, and the visor was never drawn.");

        AABB visor = dressed.geometry().get(2).box();
        Assert.near(reach(dressed.geometry().get(2)), VISOR_REACH, TOLERANCE, "the reach of tp:visor's cube");
        Assert.near(visor.minY, SPRITE_HELMET, SPRITE_BOB + TOLERANCE,
            "the bottom of tp:visor above tp:sprite's feet, hung off its helmet bone");

        waitForSlot(context, warden, EquipmentSlot.HEAD, "tp:visor");
        int pieces = context.computeOnClient(client -> Submitted.entity(warden).geometry().size());
        Assert.that(pieces == 1, "tp:warden does not enable attachables, and " + pieces
            + " pieces of geometry were drawn for it wearing tp:visor.");
    }

    private static void held(ClientGameTestContext context, TestSingleplayerContext world, AddonEntity sprite,
        AddonEntity warden) {
        world.getServer().runCommand("item replace entity @e[type=tp:sprite] weapon.mainhand with minecraft:stick");
        world.getServer().runCommand("item replace entity @e[type=tp:warden] weapon.mainhand with minecraft:stick");
        Submitted large = Frames.waitFor(context, sprite, frame -> frame.items().size() == 1,
            "tp:sprite has a rightItem bone and held a stick, and the stick was never drawn.");
        Submitted plain = Frames.waitFor(context, warden, frame -> frame.items().size() == 1,
            "tp:warden has a rightItem bone and held a stick, and the stick was never drawn.");

        Assert.near(large.items().get(0).scale() / plain.items().get(0).scale(), 2, TOLERANCE,
            "how much larger tp:sprite's held_item_scale of 2 draws the stick than tp:warden's default");
        Assert.that(large.items().get(0).light() == FULL_BRIGHT, "tp:sprite's held_item_ignores_lighting draws"
            + " its stick at full brightness, and it was drawn at " + Integer.toHexString(large.items().get(0).light())
            + ".");
        Assert.that(plain.items().get(0).light() == plain.light(), "tp:warden draws its stick in the light"
            + " around it, " + Integer.toHexString(plain.light()) + ", and it was drawn at "
            + Integer.toHexString(plain.items().get(0).light()) + ".");
    }

    private static void prism(ClientGameTestContext context, TestSingleplayerContext world, AddonEntity prism) {
        Frames.waitFor(context, prism, frame -> frame.geometry().size() == 2,
            "tp:prism draws one pass and its glowing layer, and never both.");
        world.getServer().runCommand("item replace entity @e[type=tp:prism] armor.head with tp:visor");
        waitForSlot(context, prism, EquipmentSlot.HEAD, "tp:visor");
        int pieces = context.computeOnClient(client -> Submitted.entity(prism).geometry().size());
        Assert.that(pieces == 2, "tp:prism hides armour, and " + pieces
            + " pieces of geometry were drawn for it wearing tp:visor on its head.");

        world.getServer().runCommand("item replace entity @e[type=tp:prism] weapon.mainhand with tp:visor");
        Submitted holding = Frames.waitFor(context, prism, frame -> frame.geometry().size() == 3,
            "tp:prism enables attachables and held tp:visor, and the visor was never drawn in its hand.");
        Assert.near(holding.geometry().get(2).box().minY, PRISM_HAND, TOLERANCE,
            "the bottom of tp:visor above tp:prism's feet, hung off its rightItem bone");
        Assert.that(holding.items().isEmpty(), "tp:visor's attachable draws the held visor, and an item was"
            + " drawn in the same hand as well.");

        world.getServer().runCommand("item replace entity @e[type=tp:prism] weapon.mainhand with minecraft:stick");
        Frames.waitFor(context, prism, frame -> frame.items().size() == 1,
            "tp:prism held a stick while its hide_held_items expression is false, and the stick was never drawn.");
        world.getServer().runCommand("event entity @e[type=tp:prism] tp:prism_mark");
        Frames.waitFor(context, prism, frame -> frame.items().isEmpty(),
            "tp:prism's hide_held_items expression became true and its stick was still drawn.");
    }

    private static void waitForSlot(ClientGameTestContext context, AddonEntity entity, EquipmentSlot slot,
        String item) {
        Worlds.waitForClient(context, client -> BuiltInRegistries.ITEM
                .getKey(entity.getItemBySlot(slot).getItem()).toString().equals(item), 100,
            () -> item + " never reached the client's copy of the entity.");
    }

    private static double reach(Submitted.Geometry geometry) {
        AABB box = geometry.box();
        double middleX = (box.minX + box.maxX) / 2;
        double middleZ = (box.minZ + box.maxZ) / 2;
        return geometry.vertices().stream()
            .mapToDouble(vertex -> Math.hypot(vertex.x() - middleX, vertex.z() - middleZ))
            .max().orElse(0);
    }
}
