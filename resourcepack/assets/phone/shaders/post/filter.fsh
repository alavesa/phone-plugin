#version 330
#extension GL_ARB_separate_shader_objects : require

// Camera filter (phone:filter_<name>): see camera/Filters.java, which runs the same maths on saved pictures.
uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform FilterConfig {
    vec4 Tint;
    float Gray;
    float Sepia;
    float Saturation;
    float Contrast;
    float Bright;
    float Vignette;
    float Pixel;
};

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 uv = texCoord;
    if (Pixel > 1.0) {
        vec2 cells = OutSize / Pixel;
        uv = (floor(uv * cells) + 0.5) / cells;
    }
    vec3 c = texture(InSampler, uv).rgb;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(c, vec3(l), Gray);
    c = mix(c, vec3(l * 1.07, l * 0.74, l * 0.43), Sepia);
    float l2 = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(l2), c, Saturation);
    c = (c - 0.5) * Contrast + 0.5;
    c = c * Tint.rgb + Bright;
    float d = length((texCoord - 0.5) * vec2(OutSize.x / OutSize.y, 1.0));
    c *= 1.0 - Vignette * smoothstep(0.3, 0.9, d);
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
