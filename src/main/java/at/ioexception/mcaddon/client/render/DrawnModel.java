package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.entity.EntityQueries;
import at.ioexception.mcaddon.bedrock.model.BedrockBone;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The half of a pack's questions that only a client can answer, for one model on screen.
 *
 * A server holds the same entity and answers the same vocabulary, so everything reachable without a
 * camera, an atlas or a posed skeleton is answered on both sides and lives in `EntityQueries`. What
 * is here is the rest.
 */
public record DrawnModel(EntityAnimator animator, int entityId, boolean attached)
    implements EntityQueries.Drawn {
    @Override
    public BedrockBone bone(String name) {
        return animator.bone(name);
    }

    @Override
    public BonePose poseOf(String name) {
        return animator.poseOf(name);
    }

    @Override
    public Vector3f locator(String name) {
        return animator.locator(name);
    }

    /** An attachable hangs off the model underneath it, and only the table knows which that is. */
    @Override
    public Vector3f rootLocator(String name) {
        EntityAnimator root = attached ? Animators.rootOf(entityId) : animator;
        return root == null ? null : root.locator(name);
    }

    @Override
    public Vec3 cameraPosition() {
        return Platform.cameraPosition();
    }

    /**
     * Both accessors lost their `get` prefix on the way to 26: the camera's own did first and the
     * renderer's followed in 26.2.
     */
    @Override
    public double cameraRotation(boolean vertical) {
        //? if >=26.2 {
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        return vertical ? camera.xRot() : camera.yRot();
        //?} elif >=26 {
        /*Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        return vertical ? camera.xRot() : camera.yRot();
        *///?} else {
        /*Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        return vertical ? camera.getXRot() : camera.getYRot();
        *///?}
    }

    @Override
    public boolean isFirstPerson() {
        return Minecraft.getInstance().options.getCameraType().isFirstPerson();
    }

    @Override
    public int renderDistance() {
        return Minecraft.getInstance().options.renderDistance().get();
    }

    @Override
    public Player localPlayer() {
        return Minecraft.getInstance().player;
    }

    /** The cape moved into the skin record in 1.21 and became a client asset in 26. */
    @Override
    public boolean hasCape(Entity drawn) {
        if (!(drawn instanceof AbstractClientPlayer player)) return false;

        //? if >=26 {
        return player.getSkin().cape() != null;
        //?} elif >=1.21 {
        /*return player.getSkin().capeTexture() != null;
        *///?} else {
        /*return player.getCloakTextureLocation() != null;
        *///?}
    }

    @Override
    public int emitterCount() {
        return AddonEmitters.running();
    }

    /**
     * How many particles the client is drawing.
     *
     * The engine states its counts as the line the debug screen shows, which ends with the total on
     * every version, so the digits at the end of that line are the number.
     */
    @Override
    public double particleCount() {
        String counted = Minecraft.getInstance().particleEngine.countParticles();
        int from = counted.length();
        while (from > 0 && Character.isDigit(counted.charAt(from - 1))) from -= 1;

        return from == counted.length() ? 0 : Double.parseDouble(counted.substring(from));
    }

    @Override
    public boolean isAttachableItem(String item) {
        return !AddonClientAssets.attachablesFor(item).isEmpty();
    }

    @Override
    public Sprite groundSprite(BlockState ground) {
        TextureAtlasSprite sprite = Platform.blockParticleSprite(ground);
        return sprite == null ? null
            : new Sprite(sprite.getX(), sprite.getY(),
                sprite.contents().width(), sprite.contents().height());
    }
}
