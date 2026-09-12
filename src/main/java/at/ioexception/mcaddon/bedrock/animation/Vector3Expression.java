package at.ioexception.mcaddon.bedrock.animation;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import org.joml.Vector3f;

/**
 * Three Molang expressions, one per axis. A keyframe value is never just three numbers.
 *
 * `this` inside a keyframe means the axis's own current value, so each component is evaluated with
 * its own notion of self.
 */
public record Vector3Expression(MolangExpression x, MolangExpression y, MolangExpression z) {
    public static final Vector3Expression ZERO =
        new Vector3Expression(MolangExpression.ZERO, MolangExpression.ZERO, MolangExpression.ZERO);

    public Vector3f evaluate(MolangEnvironment environment, Vector3f current, Vector3f into) {
        environment.setSelf(current.x);
        float evaluatedX = x.asFloat(environment);
        environment.setSelf(current.y);
        float evaluatedY = y.asFloat(environment);
        environment.setSelf(current.z);
        float evaluatedZ = z.asFloat(environment);
        return into.set(evaluatedX, evaluatedY, evaluatedZ);
    }
}
