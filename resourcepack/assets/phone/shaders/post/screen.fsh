#version 330
#extension GL_ARB_separate_shader_objects : require

// =====================================================================================================================
// phone:screen and phone:flash  -  the look of the world BEHIND the phone UI.
//
// What it does
//   phone:screen  cools the picture toward the colour of a phone screen and darkens the corners while a phone
//                 dialog is open, so the dialog reads as a lit screen held in front of the world.
//   phone:flash   the same shader with Flash > 0: a short white flash when the camera takes a picture.
//
// How it is triggered
//   The plugin adds/removes the effect id on the player with Paper's Player.postEffects() (the API behind the
//   vanilla 26.3 /posteffect command). Nothing in the world or HUD is used as a trigger.
//
// What it cannot do
//   Minecraft 26.3 runs post effects on the 3D frame BEFORE it draws the HUD, the held item and screens. This
//   shader therefore never touches the dialog itself, the hotbar or the hand - only the world behind them.
// =====================================================================================================================

const float VIGNETTE_INNER = 0.30;      // distance from the centre where the darkening starts (0.707 = the corners)
const float VIGNETTE_OUTER = 0.75;     // distance where it reaches full strength

uniform sampler2D InSampler;

// Sizes of the input and output targets, filled in by the game for every post pass.
layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

// Values from the post_effect JSON (screen.json / flash.json).
layout(std140) uniform ScreenConfig {
    vec4 Tint;          // rgb = colour the picture is pulled toward, a = how much (0..1)
    float Vignette;     // 0..1 darkening toward the corners
    float Flash;        // 0..1 white mixed over everything
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;

    // tint: keep the brightness of the pixel, move its colour toward Tint.rgb
    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(col, luma * Tint.rgb * 1.15, Tint.a);

    // vignette: an ellipse that follows the shape of the screen
    col *= 1.0 - Vignette * smoothstep(VIGNETTE_INNER, VIGNETTE_OUTER, length(texCoord - 0.5));

    col = mix(col, vec3(1.0), Flash);
    fragColor = vec4(col, 1.0);
}
