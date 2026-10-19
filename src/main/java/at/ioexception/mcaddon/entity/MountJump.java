package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * What a rider's jump key does to a mount: `minecraft:dash_action` lunges it and
 * `minecraft:can_power_jump` jumps it as high as the rider charged the jump.
 *
 * Java moves a ridden mount on its rider's client and zeroes any motion the server writes while a
 * player steers, so the impulse is applied there, in `tickRidden`, as Java's horse and camel apply
 * theirs. The client hears of a jump from the rider's own input through {@link #press}; the server
 * hears of it from the rider's riding jump command through {@link #heard}, checks the cooldown again
 * and publishes what a pack's Molang reads about the dash. Each side counts the cooldown itself.
 */
final class MountJump {
    /** How far forward a power jump pushes a mount moving forward, at a full charge: Java's horse. */
    private static final double LEAP_REACH = 0.4;

    private final AddonEntity mount;
    private int cooldown;
    private int wait;
    private float pendingJump;
    private boolean pendingDash;

    MountJump(AddonEntity mount) {
        this.mount = mount;
    }

    /** Whether the rider's jump key belongs to the mount, which is what puts the jump bar up. */
    boolean canJump() {
        return mount.declaresNow("minecraft:dash_action") || mount.declaresNow("minecraft:can_power_jump");
    }

    /** Ticks until the mount may dash again, during which the rider's client charges nothing. */
    int cooldown() {
        return cooldown;
    }

    /**
     * The rider let go of the jump key on the client that moves the mount, having charged it to
     * {@code charge} hundredths.
     *
     * A dash is the same however long the key was held, because Bedrock states its momentum outright.
     */
    void press(int charge) {
        if (mount.declaresNow("minecraft:dash_action")) {
            if (cooldown > 0 || !canDashHere()) return;
            pendingDash = true;
            startCooldown();
        } else if (mount.declaresNow("minecraft:can_power_jump")) {
            pendingJump = scale(Math.max(0, charge));
        }
    }

    /** The server's half of a press, where the cooldown that decides `query.can_dash` is kept. */
    void heard() {
        if (!mount.declaresNow("minecraft:dash_action") || cooldown > 0) return;

        startCooldown();
        mount.setValue("can_dash", false);
        mount.setValue("dash_cooldown_progress", 0d);
    }

    /** Applies a jump or a dash the rider asked for, on the side that moves the mount. */
    void ride(Player rider, Vec3 input) {
        if (pendingDash) {
            pendingDash = false;
            mount.setDeltaMovement(mount.getDeltaMovement().add(dash(rider)));
        }
        if (!mount.onGround()) return;

        if (pendingJump > 0) leap(input);
        pendingJump = 0;
    }

    void tick() {
        if (cooldown == 0) return;

        cooldown--;
        if (mount.level().isClientSide()) return;

        if (cooldown == 0) {
            mount.setValue("can_dash", true);
            mount.setValue("dash_cooldown_progress", 1d);
        } else if (cooldown % Goals.TICKS_PER_SECOND == 0) {
            mount.setValue("dash_cooldown_progress", (double) (wait - cooldown) / wait);
        }
    }

    private void startCooldown() {
        wait = Goals.ticks(mount.liveComponent("minecraft:dash_action"), "cooldown_time", 1);
        cooldown = wait;
    }

    /** A dash leaves the ground or, where `can_dash_underwater` allows it, the water. */
    private boolean canDashHere() {
        return mount.isInWater()
            ? Goals.flag(mount.liveComponent("minecraft:dash_action"), "can_dash_underwater", false)
            : mount.onGround();
    }

    /**
     * The push a dash gives.
     *
     * `horizontal_momentum` is a multiple of the mount's movement speed: Mojang's camel states 20
     * over a speed of 0.09, 1.8 blocks a tick, where Java's camel dashes at 2.
     * `vertical_momentum` is blocks a tick upward, the 0.6 both editions lift a camel by. Facing the
     * `entity` takes the mount's yaw alone; facing the `passenger` takes where the rider looks, pitch
     * included.
     */
    private Vec3 dash(Player rider) {
        JsonObject dash = mount.liveComponent("minecraft:dash_action");
        Vec3 heading = "passenger".equals(Goals.text(dash, "direction"))
            ? rider.getLookAngle()
            : Vec3.directionFromRotation(0, mount.getYRot());
        double speed = Goals.number(dash, "horizontal_momentum", 1)
            * mount.getAttributeValue(Attributes.MOVEMENT_SPEED);
        return heading.scale(speed).add(0, Goals.number(dash, "vertical_momentum", 1), 0);
    }

    /** A power jump, as high as the charge takes the jump strength and pushed on if moving forward. */
    private void leap(Vec3 input) {
        float boost = mount.getJumpBoostPower();
        double rise = (mount.getJumpPower() - boost) * pendingJump + boost;
        Vec3 moving = mount.getDeltaMovement();
        mount.setDeltaMovement(moving.x, rise, moving.z);
        if (input.z <= 0) return;

        double facing = Math.toRadians(mount.getYRot());
        mount.setDeltaMovement(mount.getDeltaMovement().add(-Math.sin(facing) * LEAP_REACH * pendingJump, 0,
            Math.cos(facing) * LEAP_REACH * pendingJump));
    }

    /** The share of a full jump a charge of so many hundredths is worth, the way Java's horse counts it. */
    private static float scale(int charge) {
        return charge >= 90 ? 1 : 0.4f + 0.4f * charge / 90;
    }
}
