#version 300 es
// Copyright 2026, compose-miuix-ui contributors & DigitalLock Engineering Team
// SPDX-License-Identifier: Apache-2.0
//
// OpenGL ES 3.0 Fragment Shader for Circular PIN Liquid Glass Refraction & 7-Band Dispersion.
// Mirrors the AGSL RuntimeShader in component/liquid/Lens.kt for GLES surfaces.

precision highp float;

uniform sampler2D uBackdropTexture;
uniform vec2 uResolution;
uniform vec2 uButtonCenterPx;
uniform float uButtonRadiusPx;
uniform float uRefractionHeightPx;
uniform float uRefractionAmountPx;
uniform float uDepthEffect;
uniform float uChromaticAberration;

in vec2 vTexCoord;
out vec4 fragColor;

float circleMap(float x) {
    float clamped = clamp(x, 0.0, 1.0);
    return 1.0 - sqrt(1.0 - clamped * clamped);
}

float sdCircle(vec2 centeredCoord, float radius) {
    return length(centeredCoord) - radius;
}

void main() {
    vec2 fragCoordPx = vTexCoord * uResolution;
    vec2 centeredCoord = fragCoordPx - uButtonCenterPx;
    float sd = sdCircle(centeredCoord, uButtonRadiusPx);

    if (sd > 0.0) {
        discard;
    }

    if (-sd >= uRefractionHeightPx) {
        fragColor = texture(uBackdropTexture, vTexCoord);
        return;
    }

    float d = circleMap(1.0 - (-sd / uRefractionHeightPx)) * (-uRefractionAmountPx);
    vec2 normalDir = length(centeredCoord) > 0.0001 ? normalize(centeredCoord) : vec2(0.0, -1.0);
    vec2 grad = normalize(normalDir + uDepthEffect * normalDir);

    vec2 refractedPx = fragCoordPx + d * grad;
    vec2 refractedUv = refractedPx / uResolution;

    if (uChromaticAberration <= 0.0001) {
        fragColor = texture(uBackdropTexture, refractedUv);
        return;
    }

    float dispersionIntensity = uChromaticAberration *
        ((centeredCoord.x * centeredCoord.y) / (uButtonRadiusPx * uButtonRadiusPx));
    vec2 dispersedUv = (d * grad * dispersionIntensity) / uResolution;

    vec4 color = vec4(0.0);

    vec4 red = texture(uBackdropTexture, refractedUv + dispersedUv);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    vec4 orange = texture(uBackdropTexture, refractedUv + dispersedUv * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    vec4 yellow = texture(uBackdropTexture, refractedUv + dispersedUv * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    vec4 green = texture(uBackdropTexture, refractedUv);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    vec4 cyan = texture(uBackdropTexture, refractedUv - dispersedUv * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    vec4 blue = texture(uBackdropTexture, refractedUv - dispersedUv * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    vec4 purple = texture(uBackdropTexture, refractedUv - dispersedUv);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    fragColor = color;
}
