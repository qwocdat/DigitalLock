#include "glass_refraction_engine.h"
#include <jni.h>
#include <cmath>
#include <algorithm>
#include <GLES3/gl3.h>

namespace digitallock::graphics {

static const char* kPinLensGlslFragmentShader = R"GLSL(#version 300 es
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

void main() {
    vec2 fragCoordPx = vTexCoord * uResolution;
    vec2 centeredCoord = fragCoordPx - uButtonCenterPx;
    float sd = length(centeredCoord) - uButtonRadiusPx;
    if (sd > 0.0) discard;
    if (-sd >= uRefractionHeightPx) {
        fragColor = texture(uBackdropTexture, vTexCoord);
        return;
    }
    float d = circleMap(1.0 - (-sd / uRefractionHeightPx)) * (-uRefractionAmountPx);
    vec2 grad = length(centeredCoord) > 0.0001 ? normalize(centeredCoord) : vec2(0.0, -1.0);
    vec2 refractedUv = (fragCoordPx + d * grad) / uResolution;
    fragColor = texture(uBackdropTexture, refractedUv);
}
)GLSL";

float evaluateCircleMap(float normalizedDepth) noexcept {
    const float x = std::clamp(normalizedDepth, 0.0f, 1.0f);
    return 1.0f - std::sqrt(1.0f - x * x);
}

float computeRefractionDisplacement(
    float signedDistancePx,
    float refractionHeightPx,
    float refractionAmountPx
) noexcept {
    if (refractionHeightPx <= 0.0f || refractionAmountPx <= 0.0f) {
        return 0.0f;
    }
    if (-signedDistancePx >= refractionHeightPx || signedDistancePx > 0.0f) {
        return 0.0f;
    }
    const float ratio = 1.0f - (-signedDistancePx / refractionHeightPx);
    return -evaluateCircleMap(ratio) * refractionAmountPx;
}

const char* getPinLensGlslSource() noexcept {
    return kPinLensGlslFragmentShader;
}

} // namespace digitallock::graphics

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_digitallock_graphics_NativeGlassBridge_nativeInitEngine(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jfloat densityScale
) {
    auto* ctx = new digitallock::graphics::GlassEngineContext{
        densityScale > 0.0f ? densityScale : 1.0f,
        0U,
        true
    };
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jfloat JNICALL
Java_com_digitallock_graphics_NativeGlassBridge_nativeComputeRefractionOffset(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jfloat signedDistancePx,
    jfloat refractionHeightPx,
    jfloat refractionAmountPx
) {
    if (handle == 0L) return 0.0f;
    return digitallock::graphics::computeRefractionDisplacement(
        signedDistancePx,
        refractionHeightPx,
        refractionAmountPx
    );
}

JNIEXPORT jstring JNICALL
Java_com_digitallock_graphics_NativeGlassBridge_nativeGetGlslFragmentShader(
    JNIEnv* env,
    jobject /* thiz */
) {
    return env->NewStringUTF(digitallock::graphics::getPinLensGlslSource());
}

JNIEXPORT void JNICALL
Java_com_digitallock_graphics_NativeGlassBridge_nativeReleaseEngine(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (handle == 0L) return;
    auto* ctx = reinterpret_cast<digitallock::graphics::GlassEngineContext*>(handle);
    if (ctx->glProgramId != 0U) {
        glDeleteProgram(ctx->glProgramId);
        ctx->glProgramId = 0U;
    }
    delete ctx;
}

} // extern "C"
