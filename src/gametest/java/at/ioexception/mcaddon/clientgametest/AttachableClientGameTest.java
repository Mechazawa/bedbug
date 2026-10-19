package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.Submitted;
import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.Comparator;

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
 * - The client's own player and a husk, which Java draws with models of its own, wear `tp:topper`
 *   and hold `tp:baton`. Java draws neither item, and each attachable hangs off the part of Java's
 *   model its binding names: the topper, pivoted at 28 pixels and modelled on top of a humanoid
 *   head, sits there and turns with the head, and the baton sits in the right hand.
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

    /** Java draws a player at fifteen sixteenths of its model, from a model origin 1.501 blocks up. */
    private static final double PLAYER_SCALE = 0.9375;
    private static final double MODEL_ORIGIN = 1.501;

    /** The player's neck, which its head turns about, and the top of its head 8 pixels above. */
    private static final double PLAYER_NECK = MODEL_ORIGIN * PLAYER_SCALE;
    private static final double PLAYER_HEAD_TOP = (MODEL_ORIGIN + 8 / 16.0) * PLAYER_SCALE;

    /**
     * Bedrock's `rightItem`, 7 pixels down the right arm and 1 pixel out and back from it, with the
     * arm raised 18 degrees the way Java holds an arm carrying an item. Java bobs an arm by up to a
     * tenth of a radian, which moves the hand by less than a pixel.
     */
    private static final double PLAYER_HAND_HEIGHT = (MODEL_ORIGIN - 8.966 / 16) * PLAYER_SCALE;
    private static final double PLAYER_HAND_REACH = Math.hypot(6, 1.212) / 16 * PLAYER_SCALE;
    private static final double HAND_TOLERANCE = 0.05;

    private static final float LOOK_UP = 50;
    private static final float LOOK_ASIDE = 40;
    private static final double ANGLE_TOLERANCE = 2;

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
            onAPlayer(context, world);
            onAMob(context, world);
        }
    }

    /**
     * A player wearing `tp:topper` and holding `tp:baton`, each drawn by its attachable alone and
     * hung off the part of the player's model its binding named.
     */
    private static void onAPlayer(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("item replace entity @a armor.head with tp:topper");
        world.getServer().runCommand("item replace entity @a weapon.mainhand with tp:baton");
        Worlds.waitForClient(context, client -> carries(client.player, EquipmentSlot.HEAD, "tp:topper")
                && carries(client.player, EquipmentSlot.MAINHAND, "tp:baton"), 100,
            () -> "tp:topper and tp:baton never reached the client's own player.");

        Submitted level = posed(context, 0, 0);
        Assert.that(level.items().isEmpty(), () -> "tp:topper's and tp:baton's attachables draw both items,"
            + " and Java drew " + level.items().size() + " item models on the player as well.");
        Assert.that(level.geometry().size() == 2, () -> "Expected tp:topper and tp:baton drawn on the player"
            + " and found " + level.geometry().size() + " pieces of geometry.");

        Assert.near(topper(level).box().minY, PLAYER_HEAD_TOP, TOLERANCE,
            "the bottom of tp:topper above the player's feet, modelled on the top of a humanoid head");
        Assert.near(Math.toDegrees(fromHead(topper(posed(context, -LOOK_UP, 0))).angle(fromHead(topper(level)))),
            LOOK_UP, ANGLE_TOLERANCE, "the angle tp:topper turned through while the player looked up");
        Assert.near(Math.toDegrees(across(topper(posed(context, 0, LOOK_ASIDE))).angle(across(topper(level)))),
            LOOK_ASIDE, ANGLE_TOLERANCE, "the angle tp:topper turned through while the player looked aside");

        Vec3 hand = middle(baton(level));
        Assert.near(hand.y, PLAYER_HAND_HEIGHT, HAND_TOLERANCE,
            "the height of tp:baton above the player's feet, hung off the right hand");
        Assert.near(Math.hypot(hand.x, hand.z), PLAYER_HAND_REACH, HAND_TOLERANCE,
            "how far tp:baton is from the middle of the player, hung off the right hand");
    }

    /** A husk, which Java draws with its zombie model, wearing and holding the same two items. */
    private static void onAMob(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("summon minecraft:husk ~ ~ ~-4 {NoAI:1b,PersistenceRequired:1b}");
        world.getServer().runCommand("item replace entity @e[type=minecraft:husk] armor.head with tp:topper");
        world.getServer().runCommand("item replace entity @e[type=minecraft:husk] weapon.mainhand with tp:baton");
        LivingEntity husk = Entities.waitForClientLiving(context, "minecraft:husk");
        Worlds.waitForClient(context, client -> carries(husk, EquipmentSlot.HEAD, "tp:topper")
                && carries(husk, EquipmentSlot.MAINHAND, "tp:baton"), 100,
            () -> "tp:topper and tp:baton never reached the client's copy of the husk.");

        Submitted dressed = context.computeOnClient(client -> Submitted.living(husk));
        Assert.that(dressed.items().isEmpty(), () -> "tp:topper's and tp:baton's attachables draw both items,"
            + " and Java drew " + dressed.items().size() + " item models on the husk as well.");
        Assert.that(dressed.geometry().size() == 2, () -> "Expected tp:topper and tp:baton drawn on the husk"
            + " and found " + dressed.geometry().size() + " pieces of geometry.");
    }

    /** One frame of the client's own player, its head turned from its body by the angles given. */
    private static Submitted posed(ClientGameTestContext context, float pitch, float headTurn) {
        return context.computeOnClient(client -> {
            LocalPlayer player = client.player;
            player.setXRot(pitch);
            player.xRotO = pitch;
            player.yBodyRot = 0;
            player.yBodyRotO = 0;
            player.yHeadRot = headTurn;
            player.yHeadRotO = headTurn;
            return Submitted.living(player);
        });
    }

    private static boolean carries(LivingEntity entity, EquipmentSlot slot, String item) {
        return entity != null
            && BuiltInRegistries.ITEM.getKey(entity.getItemBySlot(slot).getItem()).toString().equals(item);
    }

    /** The topper is two cubes and the baton one, so the piece with more corners is the topper. */
    private static Submitted.Geometry topper(Submitted frame) {
        return frame.geometry().stream().max(Comparator.comparingInt(piece -> piece.vertices().size())).orElseThrow();
    }

    private static Submitted.Geometry baton(Submitted frame) {
        return frame.geometry().stream().min(Comparator.comparingInt(piece -> piece.vertices().size())).orElseThrow();
    }

    private static Vec3 middle(Submitted.Geometry piece) {
        return new Vec3(
            piece.vertices().stream().mapToDouble(Submitted.Vertex::x).average().orElse(0),
            piece.vertices().stream().mapToDouble(Submitted.Vertex::y).average().orElse(0),
            piece.vertices().stream().mapToDouble(Submitted.Vertex::z).average().orElse(0));
    }

    /** From the player's neck, which the head turns about, to the middle of a piece. */
    private static Vector3f fromHead(Submitted.Geometry piece) {
        Vec3 middle = middle(piece);
        return new Vector3f((float) middle.x, (float) (middle.y - PLAYER_NECK), (float) middle.z);
    }

    /** The same, seen from above. */
    private static Vector3f across(Submitted.Geometry piece) {
        Vector3f from = fromHead(piece);
        return new Vector3f(from.x, 0, from.z);
    }

    private static void worn(ClientGameTestContext context, TestSingleplayerContext world, AddonEntity sprite,
        AddonEntity warden) {
        world.getServer().runCommand("item replace entity @e[type=tp:sprite] armor.head with tp:visor");
        world.getServer().runCommand("item replace entity @e[type=tp:warden] armor.head with tp:visor");
        Submitted dressed = Frames.waitFor(context, sprite, frame -> frame.geometry().size() == 4,
            "tp:sprite enables attachables and wore tp:visor on its head, and the visor was never drawn.");

        AABB visor = dressed.geometry().get(3).box();
        Assert.near(reach(dressed.geometry().get(3)), VISOR_REACH, TOLERANCE, "the reach of tp:visor's cube");
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
        world.getServer().runCommand("bedrock event entity @e[type=tp:prism] tp:prism_mark");
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
