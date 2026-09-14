package at.ioexception.mcaddon.bedrock.model;

/**
 * How a drawing pass moves its texture under the model, in whole texture widths.
 *
 * Bedrock calls this `uv_anim`. The scale stretches the texture and the offset slides it, in that
 * order. One add-on uses the offset to pick one message out of the tall strip of pre-rendered sign
 * writing, so there it is a fixed choice rather than anything that animates.
 */
public record UvShift(float u, float v, float scaleU, float scaleV) {
    public static final UvShift NONE = new UvShift(0f, 0f, 1f, 1f);

    public float mapU(float u) {
        return u * scaleU + this.u;
    }

    public float mapV(float v) {
        return v * scaleV + this.v;
    }
}
