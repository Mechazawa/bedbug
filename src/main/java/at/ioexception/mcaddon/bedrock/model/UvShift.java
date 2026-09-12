package at.ioexception.mcaddon.bedrock.model;

/**
 * How far a drawing pass slides its texture under the model, in whole texture widths.
 *
 * Bedrock calls this `uv_anim`. The add-on uses it to pick one message out of the tall strip of
 * pre-rendered sign writing, so it is a fixed choice rather than anything that animates.
 */
public record UvShift(float u, float v) {
    public static final UvShift NONE = new UvShift(0f, 0f);
}
