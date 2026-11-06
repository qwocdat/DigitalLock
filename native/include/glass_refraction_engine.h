#ifndef DIGITALLOCK_GLASS_REFRACTION_ENGINE_H
#define DIGITALLOCK_GLASS_REFRACTION_ENGINE_H

#include <cstdint>

namespace digitallock::graphics {

struct GlassEngineContext {
    float densityScale;
    uint32_t glProgramId;
    bool initialized;
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

const char* getPinLensGlslSource() noexcept;

} // namespace digitallock::graphics

#endif // DIGITALLOCK_GLASS_REFRACTION_ENGINE_H
