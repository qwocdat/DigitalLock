#ifndef DIGITALLOCK_GLASS_REFRACTION_ENGINE_H
#define DIGITALLOCK_GLASS_REFRACTION_ENGINE_H

#include <cstdint>

namespace digitallock::graphics {

struct GlassEngineContext {
    float densityScale;
    uint32_t glProgramId;
    bool initialized;
    bool contextLost;
};

struct SanitizedGlassParams {
    float refractionScale;
    float aberrationScale;
    float fresnelStrength;
    float blurRadiusDp;
};

/**
 * Evaluates the circular meniscus lens mapping function `1.0 - sqrt(1.0 - x^2)`
 * matching `circleMap` in `Lens.kt` and `pin_lens_refraction.glsl`.
 */
float evaluateCircleMap(float normalizedDepth) noexcept;

/**
 * Computes the radial UV displacement in pixels at a given negative signed distance
 * from the circular PIN button boundary.
 */
float computeRefractionDisplacement(
    float signedDistancePx,
    float refractionHeightPx,
    float refractionAmountPx
) noexcept;

/**
 * Evaluates Schlick's Fresnel reflectance approximation for Liquid Glass rim highlights.
 */
float evaluateFresnelSchlick(float cosTheta, float f0) noexcept;

/**
 * Computes 3-channel (R, G, B) radial dispersion offsets in pixels with zero heap allocation.
 */
void computeChromaticDispersionOffsets(
    float signedDistancePx,
    float refractionHeightPx,
    float refractionAmountPx,
    float chromaticAberration,
    float outRgbOffsets[3]
) noexcept;

/**
 * Advances a critically/under-damped spring-damper system by `dtSeconds` with zero allocation.
 */
void stepSpringDamper(
    float position,
    float velocity,
    float target,
    float stiffness,
    float damping,
    float dtSeconds,
    float outPosVel[2]
) noexcept;

/**
 * Clamps and sanitizes untrusted remote shader parameters to safe GPU bounds.
 */
SanitizedGlassParams sanitizeGlassParams(
    float refractionScale,
    float aberrationScale,
    float fresnelStrength,
    float blurRadiusDp
) noexcept;

const char* getPinLensGlslSource() noexcept;

} // namespace digitallock::graphics

#endif // DIGITALLOCK_GLASS_REFRACTION_ENGINE_H
