package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.bedrock.aim.AimAssistTargeting;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Objects;

/**
 * The aim assist the server set for this client's player, and the target it settled on.
 *
 * The target is picked once a tick, before the game's own pick, and laid over every pick after it
 * until the next tick: the per-frame pick from 26 on would otherwise throw it away between ticks.
 * It becomes what the player's hand acts on only while it is within reach, as the game's own pick
 * would be; further off it is only marked. Bedrock draws aim assist under its third person cameras
 * alone, which in Java is the camera behind the player.
 */
public final class PlayerAimAssist {
    private static final String BLOCK_RETICLE = "textures/ui/aimassist_block_highlight";
    private static final String ENTITY_RETICLE = "textures/ui/aimassist_entity_highlight";
    private static final int RETICLE_COLOUR = 0xFFFFFFFF;

    private static AimAssistTargeting targeting;
    private static HitResult target;

    private PlayerAimAssist() {
    }

    public static void accept(String body) {
        targeting = AimAssistTargeting.decode(body);
        target = null;
    }

    public static void clear() {
        targeting = null;
        target = null;
    }

    /** The block or entity aim assist settled on this tick, or null. */
    public static HitResult target() {
        return target;
    }

    public static void tick(Minecraft client) {
        target = targeting != null && client.player != null && client.level != null
            && client.options.getCameraType() == CameraType.THIRD_PERSON_BACK
            ? targeting.pick(client.player) : null;
    }

    /** Lays the target over what the game picked, where the player reaches it. */
    public static void pick(Minecraft client) {
        if (target == null || client.player == null) return;
        if (target instanceof EntityHitResult hit && !hit.getEntity().isAlive()) return;

        Vec3 eye = client.player.getEyePosition();
        AABB box = target instanceof EntityHitResult hit ? hit.getEntity().getBoundingBox() : null;
        Vec3 nearest = box == null ? target.getLocation() : new Vec3(Mth.clamp(eye.x, box.minX, box.maxX),
            Mth.clamp(eye.y, box.minY, box.maxY), Mth.clamp(eye.z, box.minZ, box.maxZ));
        if (nearest.distanceTo(eye) > Platform.interactionRange(client.player, box != null)) return;

        client.hitResult = target;
        client.crosshairPickEntity = target instanceof EntityHitResult hit ? hit.getEntity() : null;
    }

    /**
     * Draws the reticle over the target's box: the active add-ons' own image where one ships it,
     * and four corner marks otherwise.
     */
    public static void draw(Platform.Painter painter) {
        Minecraft client = Minecraft.getInstance();
        if (target == null || client.level == null) return;

        AABB box;
        if (target instanceof EntityHitResult hit) {
            box = hit.getEntity().getBoundingBox();
        } else {
            BlockPos position = ((BlockHitResult) target).getBlockPos();
            VoxelShape shape = client.level.getBlockState(position).getShape(client.level, position);
            box = shape.isEmpty() ? new AABB(position) : shape.bounds().move(position);
        }
        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            float[] point = Platform.onScreen(new Vec3((corner & 1) == 0 ? box.minX : box.maxX,
                (corner & 2) == 0 ? box.minY : box.maxY, (corner & 4) == 0 ? box.minZ : box.maxZ),
                painter.width(), painter.height());
            if (point == null) return;
            left = Math.min(left, point[0]);
            top = Math.min(top, point[1]);
            right = Math.max(right, point[0]);
            bottom = Math.max(bottom, point[1]);
        }

        Identifier image = reticle(target instanceof EntityHitResult ? ENTITY_RETICLE : BLOCK_RETICLE);
        if (image != null) {
            painter.image(image, (int) left, (int) top, (int) (right - left), (int) (bottom - top));
            return;
        }
        int size = Math.max(2, (int) Math.min(right - left, bottom - top) / 4);
        int[][] corners = {{(int) left, (int) top, 1, 1}, {(int) right, (int) top, -1, 1},
            {(int) left, (int) bottom, 1, -1}, {(int) right, (int) bottom, -1, -1}};
        for (int[] mark : corners) {
            painter.fill(Math.min(mark[0], mark[0] + mark[2] * size), Math.min(mark[1], mark[1] + mark[3]),
                Math.max(mark[0], mark[0] + mark[2] * size), Math.max(mark[1], mark[1] + mark[3]), RETICLE_COLOUR);
            painter.fill(Math.min(mark[0], mark[0] + mark[2]), Math.min(mark[1], mark[1] + mark[3] * size),
                Math.max(mark[0], mark[0] + mark[2]), Math.max(mark[1], mark[1] + mark[3] * size), RETICLE_COLOUR);
        }
    }

    /** The image an active add-on ships under Bedrock's path, the first add-on's where several do. */
    private static Identifier reticle(String bedrockPath) {
        return ActiveAddons.active().converted().stream().map(pack -> pack.texturePaths().get(bedrockPath))
            .filter(Objects::nonNull).findFirst()
            .map(mapped -> Platform.id(mapped.substring(0, mapped.indexOf(':')),
                "textures/" + mapped.substring(mapped.indexOf(':') + 1) + ".png"))
            .orElse(null);
    }
}
