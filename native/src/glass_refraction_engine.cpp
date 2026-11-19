#include "glass_refraction_engine.h"
#include <algorithm>
#include <cmath>

#ifdef __ANDROID__
#include <jni.h>
#include <GLES3/gl3.h>
#endif

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
uniform float uFresnelStrength;
in vec2 vTexCoord;
out vec4 fragColor;

float circleMap(float x) {
    float clamped = clamp(x, 0.0, 1.0);
    return 1.0 - sqrt(1.0 - clamped * clamped);
}

float fresnelSchlick(float cosTheta, float f0) {
    float x = clamp(1.0 - cosTheta, 0.0, 1.0);
    float x2 = x * x;
    return f0 + (1.0 - f0) * (x2 * x2 * x);
}

void main() {
    vec2 fragCoordPx = vTexCoord * uResolution;
    vec2 centeredCoord = fragCoordPx - uButtonCenterPx;
    float dist = length(centeredCoord);
    float sd = dist - uButtonRadiusPx;
    if (sd > 0.0) discard;

    if (-sd >= uRefractionHeightPx) {
        fragColor = texture(uBackdropTexture, vTexCoord);
        return;
    }

    float edgeRatio = clamp(1.0 - (-sd / max(uRefractionHeightPx, 0.001)), 0.0, 1.0);
    float d = circleMap(edgeRatio) * (-uRefractionAmountPx);
    vec2 grad = dist > 0.0001 ? normalize(centeredCoord) : vec2(0.0, -1.0);
    vec2 refractedUv = clamp((fragCoordPx + d * grad) / uResolution, vec2(0.0), vec2(1.0));

    vec4 baseColor;
    if (uChromaticAberration > 0.0001) {
        float disp = uChromaticAberration * ((centeredCoord.x * centeredCoord.y) / max(uButtonRadiusPx * uButtonRadiusPx, 1.0));
        vec2 dispUv = (d * grad * disp) / uResolution;
        float r = texture(uBackdropTexture, clamp(refractedUv + dispUv, vec2(0.0), vec2(1.0))).r;
        float g = texture(uBackdropTexture, refractedUv).g;
        float b = texture(uBackdropTexture, clamp(refractedUv - dispUv, vec2(0.0), vec2(1.0))).b;
        float a = texture(uBackdropTexture, refractedUv).a;
        baseColor = vec4(r, g, b, a);
    } else {
        baseColor = texture(uBackdropTexture, refractedUv);
    }

    float cosTheta = sqrt(max(0.0, 1.0 - edgeRatio * edgeRatio));
    float fresnel = fresnelSchlick(cosTheta, 0.04) * clamp(uFresnelStrength, 0.0, 1.0);
    fragColor = vec4(mix(baseColor.rgb, vec3(1.0), fresnel * 0.45), baseColor.a);
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

float evaluateFresnelSchlick(float cosTheta, float f0) noexcept {
    const float c = std::clamp(cosTheta, 0.0f, 1.0f);
    const float base = std::clamp(f0, 0.0f, 1.0f);
    const float inv = 1.0f - c;
    const float inv2 = inv * inv;
    return base + (1.0f - base) * (inv2 * inv2 * inv);
}

void computeChromaticDispersionOffsets(
    float signedDistancePx,
    float refractionHeightPx,
    float refractionAmountPx,
    float chromaticAberration,
    float outRgbOffsets[3]
) noexcept {
    const float baseOffset = computeRefractionDisplacement(
        signedDistancePx,
        refractionHeightPx,
        refractionAmountPx
    );
    const float ca = std::clamp(chromaticAberration, 0.0f, 0.5f);
    outRgbOffsets[0] = baseOffset * (1.0f + ca);
    outRgbOffsets[1] = baseOffset;
    outRgbOffsets[2] = baseOffset * (1.0f - ca);
}

void stepSpringDamper(
    float position,
    float velocity,
    float target,
    float stiffness,
    float damping,
    float dtSeconds,
    float outPosVel[2]
) noexcept {
    const float dt = std::clamp(dtSeconds, 0.0001f, 0.05f);
    const float k = std::clamp(stiffness, 10.0f, 2000.0f);
    const float c = std::clamp(damping, 1.0f, 200.0f);
    const float force = -k * (position - target) - c * velocity;
    const float nextVel = velocity + force * dt;
    const float nextPos = position + nextVel * dt;
    outPosVel[0] = nextPos;
    outPosVel[1] = nextVel;
}

SanitizedGlassParams sanitizeGlassParams(
    float refractionScale,
    float aberrationScale,
    float fresnelStrength,
    float blurRadiusDp
) noexcept {
    return SanitizedGlassParams{
        std::clamp(std::isfinite(refractionScale) ? refractionScale : 1.0f, 0.5f, 1.5f),
        std::clamp(std::isfinite(aberrationScale) ? aberrationScale : 1.0f, 0.0f, 1.5f),
        std::clamp(std::isfinite(fresnelStrength) ? fresnelStrength : 0.7f, 0.0f, 1.0f),
        std::clamp(std::isfinite(blurRadiusDp) ? blurRadiusDp : 0.6f, 0.0f, 4.0f),
    };
}

const char* getPinLensGlslSource() noexcept {
    return kPinLensGlslFragmentShader;
}

} // namespace digitallock::graphics

#ifdef __ANDROID__
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
        true,
        false
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

JNIEXPORT jfloat JNICALL
Java_com_digitallock_graphics_NativeGlassBridge_nativeEvaluateFresnel(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jfloat cosTheta,
    jfloat f0
) {
    return digitallock::graphics::evaluateFresnelSchlick(cosTheta, f0);
}

JNIEXPORT jfloatArray JNICALL
Java_com_digitallock_graphics_NativeGlassBridge_nativeSanitizeGlassParams(
    JNIEnv* env,
    jobject /* thiz */,
    jfloat refractionScale,
    jfloat aberrationScale,
    jfloat fresnelStrength,
    jfloat blurRadiusDp
) {
    const auto p = digitallock::graphics::sanitizeGlassParams(
        refractionScale,
        aberrationScale,
        fresnelStrength,
        blurRadiusDp
    );
    jfloatArray arr = env->NewFloatArray(4);
    if (arr == nullptr) return nullptr;
    const jfloat values[4] = {
        p.refractionScale,
        p.aberrationScale,
        p.fresnelStrength,
        p.blurRadiusDp
    };
    env->SetFloatArrayRegion(arr, 0, 4, values);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_digitallock_graphics_NativeGlassBridge_nativeOnContextLost(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (handle == 0L) return;
    auto* ctx = reinterpret_cast<digitallock::graphics::GlassEngineContext*>(handle);
    // Do NOT call glDeleteProgram after EGL context loss; just drop the stale handle safely.
    ctx->glProgramId = 0U;
    ctx->contextLost = true;
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
    if (ctx->glProgramId != 0U && !ctx->contextLost) {
        glDeleteProgram(ctx->glProgramId);
        ctx->glProgramId = 0U;
    }
    delete ctx;
}

} // extern "C"
#endif // __ANDROID__
