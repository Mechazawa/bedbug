package at.ioexception.mcaddon.bedrock.aim;

import at.ioexception.mcaddon.bedrock.aim.AimAssistSettings.TargetMode;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How far a point is from winning aim assist's choice, for a player at the origin facing south.
 *
 * The cone reaches its angle either side of the way the player faces, across and up and down apart,
 * and a priority divides the measure the target mode compares.
 */
class AimAssistSettingsTest {
    private static final AimAssistSettings ANGLE = new AimAssistSettings("minecraft:aim_assist_default",
        TargetMode.ANGLE, 30, 10, 8);
    private static final AimAssistSettings DISTANCE = new AimAssistSettings("minecraft:aim_assist_default",
        TargetMode.DISTANCE, 30, 10, 8);

    @Test
    void scoresAnAngleOffTheMiddleOfTheCone() {
        assertEquals(20, ANGLE.score(Vec3.ZERO, 0, 0, sideways(20, 4), 1), 1e-6);
    }

    @Test
    void scoresTheDistanceInDistanceMode() {
        assertEquals(4, DISTANCE.score(Vec3.ZERO, 0, 0, sideways(20, 4), 1), 1e-6);
    }

    /** Microsoft's own example: a priority of six is taken as six times nearer. */
    @Test
    void dividesByThePriority() {
        assertEquals(1, DISTANCE.score(Vec3.ZERO, 0, 0, sideways(0, 6), 6), 1e-6);
    }

    @Test
    void leavesOutWhatIsWiderThanTheCone() {
        assertTrue(Double.isNaN(ANGLE.score(Vec3.ZERO, 0, 0, sideways(35, 4), 1)));
    }

    /** The cone reaches ten degrees up and down here and thirty across, so a lift of fifteen is outside it. */
    @Test
    void measuresUpAndDownApartFromAcross() {
        assertTrue(Double.isNaN(ANGLE.score(Vec3.ZERO, 0, 0, new Vec3(0, Math.tan(Math.toRadians(15)) * 4, 4), 1)));
    }

    @Test
    void leavesOutWhatIsFurtherThanTheDistance() {
        assertTrue(Double.isNaN(DISTANCE.score(Vec3.ZERO, 0, 0, sideways(0, 9), 1)));
    }

    @Test
    void leavesOutAPriorityOfNothing() {
        assertTrue(Double.isNaN(DISTANCE.score(Vec3.ZERO, 0, 0, sideways(0, 4), 0)));
    }

    @Test
    void refusesInBedrocksWords() {
        assertEquals("Aim assist distance must be between 1 and 16",
            new AimAssistSettings("minecraft:aim_assist_default", TargetMode.ANGLE, 50, 50, 17).refusal());
    }

    /** A point `degrees` to the east of south, `distance` away on the level. */
    private static Vec3 sideways(double degrees, double distance) {
        double radians = Math.toRadians(degrees);
        return new Vec3(Math.sin(radians) * distance, 0, Math.cos(radians) * distance);
    }
}
