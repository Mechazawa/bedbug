// Bedrock's ColorPostProcessing grade with its tone mapper off, over a frame Java has finished.
// GradeUniforms.grade is the same arithmetic and documents the block. The shader including this
// declares texCoord and fragColor first, in the form its version of the game compiles.

uniform sampler2D InSampler;

layout(std140) uniform ColourGrade {
    vec4 Contrast[3];
    vec4 Saturation[3];
    vec4 Gain[3];
    vec4 Offset[3];
    vec4 Gamma[3];
    vec4 Temperature[3];
    vec4 Misc;
};

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 band(vec4 shadows, vec4 midtones, vec4 highlights, float luminance, float average) {
    float shadowsEdge = average * Misc.z;
    float highlightsEdge = average * Misc.y;
    if (highlights.w != 0.0 && luminance >= highlightsEdge) return highlights.xyz;
    if (shadows.w != 0.0 && luminance <= shadowsEdge) return shadows.xyz;
    if (shadows.w != 0.0 && luminance < average) {
        return mix(shadows.xyz, midtones.xyz, (luminance - shadowsEdge) / (average - shadowsEdge));
    }
    if (highlights.w != 0.0 && luminance > average) {
        return mix(midtones.xyz, highlights.xyz, (luminance - average) / (highlightsEdge - average));
    }
    return midtones.xyz;
}

vec3 decode(vec3 value) {
    return mix(pow((value + 0.055) / 1.055, vec3(2.4)), value / 12.92, lessThanEqual(value, vec3(0.04045)));
}

vec3 encode(vec3 value) {
    return mix(pow(abs(value), vec3(1.0 / 2.4)) * 1.055 - 0.055, value * 12.92, lessThanEqual(value, vec3(0.0031308)));
}

void main() {
    vec4 frame = texture(InSampler, texCoord);
    vec3 linear = decode(frame.rgb);
    float average = Misc.w;
    float luminance = dot(linear, LUMA);

    vec3 colour = mat3(Temperature[0].xyz, Temperature[1].xyz, Temperature[2].xyz) * linear;

    float pivot = Misc.x * average;
    vec3 contrast = band(Contrast[0], Contrast[1], Contrast[2], luminance, average);
    colour = max(pivot * pow(max(colour, 0.0) / pivot, contrast), 0.0);

    vec3 saturation = band(Saturation[0], Saturation[1], Saturation[2], luminance, average);
    colour = max(mix(vec3(dot(colour, LUMA)), colour, saturation), 0.0);

    colour = max(colour * band(Gain[0], Gain[1], Gain[2], luminance, average), 0.0);
    colour = max(colour + average * band(Offset[0], Offset[1], Offset[2], luminance, average), 0.0);

    vec3 gamma = band(Gamma[0], Gamma[1], Gamma[2], dot(colour, LUMA), 0.18);
    fragColor = vec4(clamp(pow(encode(colour), 2.2 / gamma), 0.0, 1.0), frame.a);
}
