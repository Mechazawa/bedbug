package at.ioexception.mcaddon.platform;

/**
 * The words Bedrock's `place jigsaw` and `place structure` take after the position. A word the line
 * leaves out takes Bedrock's value, {@link #BEDROCK}. Java's shorter forms drop a jigsaw's blocks, keep
 * its entities and waterlog what a piece puts into water, which is {@link #JAVA}.
 */
public record JigsawSettings(boolean keepJigsaws, boolean includeEntities, boolean waterlog) {
    public static final JigsawSettings JAVA = new JigsawSettings(false, true, true);
    public static final JigsawSettings BEDROCK = new JigsawSettings(false, false, true);
}
