package at.ioexception.mcaddon.bedrock.animation;

/**
 * One clip `/playanimation` or `Entity.playAnimation` asks an entity to play.
 *
 * Bedrock runs it in an animation controller named by `controller`: a second clip in the same
 * controller replaces the first, clips in different controllers play side by side, and a controller
 * of the entity's own by that name is overridden while the clip plays. Once `stopExpression` holds,
 * or a clip that does not loop has run its length, the clip blends out over `blendOutSeconds` and
 * `nextState` names the clip that follows in the same controller. A name the entity has no clip
 * for, such as `none`, ends it there.
 */
public record PlayedAnimation(String animation, String nextState, float blendOutSeconds,
    String stopExpression, String controller) {
    /** The controller a clip runs in when none is named, so two such clips replace each other. */
    public static final String DEFAULT_CONTROLLER = "__runtime_controller";

    public PlayedAnimation {
        nextState = nextState == null ? "" : nextState;
        stopExpression = stopExpression == null ? "" : stopExpression;
        controller = controller == null || controller.isEmpty() ? DEFAULT_CONTROLLER : controller;
    }

    public PlayedAnimation following() {
        return new PlayedAnimation(nextState, "", 0f, "", controller);
    }
}
