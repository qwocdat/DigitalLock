package com.digitallock.graphics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop

/**
 * Identifies the active background texture source for the Liquid Glass PIN screen.
 *
 * Per Android 14+ platform restrictions, `WallpaperManager.getDrawable()` is NEVER used
 * as a guaranteed lockscreen wallpaper provider because it throws `SecurityException` without
 * privileged storage permissions and does not expose live lockscreen layers.
 */
enum class BackgroundTextureSource {
    USER_SELECTED_URI,
    MEDIA_PROJECTION_FRAME,
    PROCEDURAL_LIQUID_DEPTH,
}

data class LockBackgroundState(
    val activeSource: BackgroundTextureSource = BackgroundTextureSource.PROCEDURAL_LIQUID_DEPTH,
    val customBitmap: Bitmap? = null,
    val statusNote: String = "Using procedural high-depth Liquid Glass background.",
)

/**
 * Renders the single shared background source into [sharedBackdrop] via `Modifier.layerBackdrop`.
 * All 12 PIN buttons and status capsules refract this single backdrop texture without creating
 * redundant background captures.
 */
object LockBackgroundRenderer {

    @Composable
    fun SharedRefractionBackground(
        sharedBackdrop: LayerBackdrop,
        customWallpaperUri: String?,
        mediaProjectionBitmap: Bitmap?,
        ambientPhase: Float,
        modifier: Modifier = Modifier,
        content: @Composable (BackgroundTextureSource) -> Unit,
    ) {
        val context = LocalContext.current
        var userBitmap by remember { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(customWallpaperUri) {
            if (customWallpaperUri.isNullOrBlank()) {
                userBitmap?.recycle()
                userBitmap = null
            } else {
                userBitmap = withContext(Dispatchers.IO) {
                    decodeSampledBitmapFromUri(context, Uri.parse(customWallpaperUri), 1440, 3200)
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                userBitmap = null
            }
        }

        val activeSource = when {
            userBitmap != null -> BackgroundTextureSource.USER_SELECTED_URI
            mediaProjectionBitmap != null -> BackgroundTextureSource.MEDIA_PROJECTION_FRAME
            else -> BackgroundTextureSource.PROCEDURAL_LIQUID_DEPTH
        }

        Box(modifier = modifier.fillMaxSize()) {
            // Single shared capture pass recorded into `sharedBackdrop`
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(sharedBackdrop),
            ) {
                when (activeSource) {
                    BackgroundTextureSource.USER_SELECTED_URI -> {
                        userBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    BackgroundTextureSource.MEDIA_PROJECTION_FRAME -> {
                        mediaProjectionBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    BackgroundTextureSource.PROCEDURAL_LIQUID_DEPTH -> {
                        ProceduralLiquidDepthCanvas(ambientPhase = ambientPhase)
                    }
                }
            }

            // Foreground PIN UI layer that samples and refracts `sharedBackdrop`
            content(activeSource)
        }
    }

    /**
     * Renders the deep indigo/cobalt/emerald curved wave background inspired by the reference
     * iOS 27 Liquid Glass lockscreen (`96325.jpg`). High-contrast curved geometric ridges
     * sit directly behind the 3x4 PIN grid so every circular lens visibly bends the boundary curves.
     */
    @Composable
    private fun ProceduralLiquidDepthCanvas(ambientPhase: Float) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val shiftX = (ambientPhase - 0.5f) * w * 0.03f
            val shiftY = (ambientPhase - 0.5f) * h * 0.02f

            // Base midnight abyss gradient
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF020714),
                        Color(0xFF040E27),
                        Color(0xFF071638),
                    ),
                ),
            )

            // Upper-right cobalt/sapphire sculpted wave lobe
            val upperWave = Path().apply {
                moveTo(w * 0.36f + shiftX, 0f)
                cubicTo(
                    w * 0.24f - shiftX, h * 0.22f,
                    w * 0.34f + shiftX, h * 0.52f,
                    w * 0.92f, h * 0.68f + shiftY,
                )
                lineTo(w, h * 0.85f)
                lineTo(w, 0f)
                close()
            }
            drawPath(
                path = upperWave,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0B1C48),
                        Color(0xFF284893),
                        Color(0xFF4F74C8),
                        Color(0xFF172C69),
                    ),
                    start = Offset(w * 0.30f, h * 0.10f),
                    end = Offset(w * 0.95f, h * 0.65f),
                ),
            )

            // Luminous specular crest along the cobalt wave boundary
            val cobaltRim = Path().apply {
                moveTo(w * 0.36f + shiftX, 0f)
                cubicTo(
                    w * 0.24f - shiftX, h * 0.22f,
                    w * 0.34f + shiftX, h * 0.52f,
                    w * 0.92f, h * 0.68f + shiftY,
                )
                cubicTo(
                    w * 0.38f + shiftX, h * 0.50f,
                    w * 0.28f - shiftX, h * 0.22f,
                    w * 0.40f + shiftX, 0f,
                )
                close()
            }
            drawPath(
                path = cobaltRim,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF6B92E8).copy(alpha = 0.55f),
                        Color(0xFFA5C4FF).copy(alpha = 0.85f),
                        Color(0xFF3A5FB0).copy(alpha = 0.35f),
                    ),
                ),
            )

            // Lower-left deep emerald/teal diagonal wave intersecting the PIN keypad
            val emeraldWave = Path().apply {
                moveTo(0f, h * 0.34f + shiftY)
                cubicTo(
                    w * 0.46f + shiftX, h * 0.38f - shiftY,
                    w * 0.78f - shiftX, h * 0.64f + shiftY,
                    w * 0.62f, h * 0.86f,
                )
                cubicTo(
                    w * 0.42f, h * 0.94f,
                    w * 0.14f, h * 0.96f,
                    0f, h * 0.95f,
                )
                close()
            }
            drawPath(
                path = emeraldWave,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF042D2E),
                        Color(0xFF0E6358),
                        Color(0xFF289E82),
                        Color(0xFF042229),
                    ),
                    start = Offset(0f, h * 0.36f),
                    end = Offset(w * 0.72f, h * 0.82f),
                ),
            )

            // Bright seafoam-cyan ridge across buttons 1, 4, 5, 8 so lens refraction is unmistakably visible
            val emeraldCrest = Path().apply {
                moveTo(0f, h * 0.34f + shiftY)
                cubicTo(
                    w * 0.46f + shiftX, h * 0.38f - shiftY,
                    w * 0.78f - shiftX, h * 0.64f + shiftY,
                    w * 0.62f, h * 0.86f,
                )
                cubicTo(
                    w * 0.74f - shiftX, h * 0.63f + shiftY,
                    w * 0.43f + shiftX, h * 0.41f - shiftY,
                    0f, h * 0.37f + shiftY,
                )
                close()
            }
            drawPath(
                path = emeraldCrest,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF4ADBC2).copy(alpha = 0.75f),
                        Color(0xFF98FFE8).copy(alpha = 0.92f),
                        Color(0xFF1E7E6C).copy(alpha = 0.35f),
                    ),
                    start = Offset(w * 0.05f, h * 0.35f),
                    end = Offset(w * 0.68f, h * 0.78f),
                ),
            )

            // Bottom sweeping indigo-slate base wave
            val bottomSweep = Path().apply {
                moveTo(0f, h * 0.93f)
                cubicTo(
                    w * 0.35f, h * 0.78f,
                    w * 0.72f, h * 0.65f,
                    w, h * 0.56f,
                )
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(
                path = bottomSweep,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1E3770).copy(alpha = 0.88f),
                        Color(0xFF39528A),
                        Color(0xFF536B9E),
                    ),
                    startY = h * 0.56f,
                    endY = h,
                ),
            )
        }
    }

    /**
     * Captures a single consenting screen texture frame from an active [MediaProjection] session
     * and immediately releases the [VirtualDisplay] and [ImageReader] so no continuous background
     * recording occurs. Respects Android 14+ single-use token lifecycle via `MediaProjection.Callback`.
     */
    fun captureSingleFrameFromMediaProjection(
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int,
        onFrameCaptured: (Bitmap?) -> Unit,
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val imageReader = ImageReader.newInstance(
            width.coerceAtLeast(360),
            height.coerceAtLeast(640),
            PixelFormat.RGBA_8888,
            2,
        )
        var virtualDisplay: VirtualDisplay? = null
        var captured = false

        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader.close()
            }
        }
        mediaProjection.registerCallback(projectionCallback, mainHandler)

        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "DigitalLockBackdropCapture",
                width.coerceAtLeast(360),
                height.coerceAtLeast(640),
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                mainHandler,
            )

            imageReader.setOnImageAvailableListener({ reader ->
                if (captured) return@setOnImageAvailableListener
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                captured = true
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * image.width
                    val rawBitmap = Bitmap.createBitmap(
                        image.width + rowPadding / pixelStride,
                        image.height,
                        Bitmap.Config.ARGB_8888,
                    )
                    rawBitmap.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
                    if (cropped != rawBitmap) rawBitmap.recycle()
                    onFrameCaptured(cropped)
                } catch (_: Throwable) {
                    onFrameCaptured(null)
                } finally {
                    image.close()
                    virtualDisplay?.release()
                    virtualDisplay = null
                    reader.close()
                    mediaProjection.unregisterCallback(projectionCallback)
                    mediaProjection.stop()
                }
            }, mainHandler)
        } catch (_: SecurityException) {
            // Android 14+ throws SecurityException if a token is reused; fall back cleanly.
            virtualDisplay?.release()
            imageReader.close()
            mediaProjection.unregisterCallback(projectionCallback)
            onFrameCaptured(null)
        }
    }

    private fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int,
    ): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            var inSampleSize = 1
            if (boundsOptions.outHeight > reqHeight || boundsOptions.outWidth > reqWidth) {
                val halfHeight = boundsOptions.outHeight / 2
                val halfWidth = boundsOptions.outWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                this.inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
