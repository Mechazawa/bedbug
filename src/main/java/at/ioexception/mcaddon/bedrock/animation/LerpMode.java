package at.ioexception.mcaddon.bedrock.animation;

/** How a channel moves between two keyframes. */
public enum LerpMode {
    LINEAR,
    /** A Catmull-Rom spline through the neighbouring keyframes, which is what Bedrock calls smooth. */
    CATMULLROM;

    public static LerpMode of(String name) {
        return "catmullrom".equalsIgnoreCase(name) ? CATMULLROM : LINEAR;
    }
}
