package com.digitallock.graphics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * JNI bridge to the C++20 / OpenGL ES 3.0 native refraction module (`libdigitallock_glass.so`).
 * Used for native lens profile verification, Fresnel/dispersion math, safe parameter sanitization,
 * and deterministic native resource teardown (including EGL context loss recovery).
 */
object NativeGlassBridge {
    private var nativeLibraryLoaded = false

    init {
        try {
            System.loadLibrary("digitallock_glass")
            nativeLibraryLoaded = true
        } catch (_: UnsatisfiedLinkError) {
            nativeLibraryLoaded = false
        }
    }

    val isAvailable: Boolean get() = nativeLibraryLoaded

    external fun nativeInitEngine(densityScale: Float): Long
    external fun nativeComputeRefractionOffset(
        handle: Long,
        signedDistancePx: Float,
        refractionHeightPx: Float,
        refractionAmountPx: Float,
    ): Float
    external fun nativeEvaluateFresnel(cosTheta: Float, f0: Float): Float
    external fun nativeSanitizeGlassParams(
        refractionScale: Float,
        aberrationScale: Float,
        fresnelStrength: Float,
        blurRadiusDp: Float,
    ): FloatArray?
    external fun nativeOnContextLost(handle: Long)
    external fun nativeGetGlslFragmentShader(): String
    external fun nativeReleaseEngine(handle: Long)

    fun sanitizeParamsSafely(
        refractionScale: Float,
        aberrationScale: Float,
        fresnelStrength: Float,
        blurRadiusDp: Float,
    ): FloatArray {
        if (nativeLibraryLoaded) {
            try {
                nativeSanitizeGlassParams(
                    refractionScale,
                    aberrationScale,
                    fresnelStrength,
                    blurRadiusDp,
                )?.let { if (it.size == 4) return it }
            } catch (_: Throwable) {
            }
        }
        return floatArrayOf(
            refractionScale.coerceIn(0.5f, 1.5f),
            aberrationScale.coerceIn(0.0f, 1.5f),
            fresnelStrength.coerceIn(0.0f, 1.0f),
            blurRadiusDp.coerceIn(0.0f, 4.0f),
        )
    }
}

/**
 * Manages the lifecycle of the Liquid Glass rendering pipeline so that:
 * 1. Shader/backdrop passes are paused immediately when the lock screen is not visible (`ON_STOP`).
 * 2. EGL context loss is handled without crashing (`onContextLost()`).
 * 3. Native GPU/JNI handles are deterministically freed in `ON_DESTROY` with zero memory leaks.
 */
class GlassEffectController(densityScale: Float) {
    private var nativeHandle: Long = if (NativeGlassBridge.isAvailable) {
        NativeGlassBridge.nativeInitEngine(densityScale)
    } else {
        0L
    }

    private val _isRenderingActive = mutableStateOf(true)
    val isRenderingActive: State<Boolean> get() = _isRenderingActive

    fun onStart() {
        _isRenderingActive.value = true
    }

    fun onStop() {
        _isRenderingActive.value = false
    }

    fun onContextLost() {
        if (nativeHandle != 0L && NativeGlassBridge.isAvailable) {
            NativeGlassBridge.nativeOnContextLost(nativeHandle)
        }
    }

    fun onDestroy() {
        _isRenderingActive.value = false
        if (nativeHandle != 0L && NativeGlassBridge.isAvailable) {
            NativeGlassBridge.nativeReleaseEngine(nativeHandle)
            nativeHandle = 0L
        }
    }
}

@Composable
fun rememberLifecycleAwareGlassController(densityScale: Float): GlassEffectController {
    val controller = remember(densityScale) { GlassEffectController(densityScale) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> controller.onStart()
                Lifecycle.Event.ON_STOP -> controller.onStop()
                Lifecycle.Event.ON_DESTROY -> controller.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.onDestroy()
        }
    }
    return controller
}
