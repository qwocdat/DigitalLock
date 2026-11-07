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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import component.liquid.LayerBackdrop
import component.liquid.layerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class BackgroundTextureSource {
    USER_SELECTED_URI,
    MEDIA_PROJECTION_FRAME,
    PROCEDURAL_LIQUID_DEPTH,
}

object BackdropCaptureStore {
    private const val CAPTURED_FILE_NAME = "captured_lock_backdrop.png"

    private val _capturedBitmapFlow = MutableStateFlow<Bitmap?>(null)
    val capturedBitmapFlow: StateFlow<Bitmap?> = _capturedBitmapFlow.asStateFlow()

    private val _isMediaProjectionActive = MutableStateFlow(false)
    val isMediaProjectionActive: StateFlow<Boolean> = _isMediaProjectionActive.asStateFlow()

    fun setMediaProjectionActive(active: Boolean) {
        _isMediaProjectionActive.value = active
    }

    fun updateCapturedBitmap(context: Context, bitmap: Bitmap) {
        _capturedBitmapFlow.value = bitmap
        try {
            val file = File(context.applicationContext.filesDir, CAPTURED_FILE_NAME)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)
            }
        } catch (_: Throwable) {
        }
    }

    fun loadPersistedBitmapIfNeeded(context: Context): Bitmap? {
        _capturedBitmapFlow.value?.let { return it }
        return try {
            val file = File(context.applicationContext.filesDir, CAPTURED_FILE_NAME)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.also {
                    _capturedBitmapFlow.value = it
                }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }
}

object LockBackgroundRenderer {

    @Composable
    fun SharedRefractionBackground(
        sharedBackdrop: LayerBackdrop,
        customWallpaperUri: String?,
        mediaProjectionBitmap: Bitmap?,
        modifier: Modifier = Modifier,
        content: @Composable (BackgroundTextureSource) -> Unit,
    ) {
        val context = LocalContext.current
        var userBitmap by remember { mutableStateOf<Bitmap?>(null) }
        val storedProjectionBitmap by BackdropCaptureStore.capturedBitmapFlow.collectAsState()

        LaunchedEffect(Unit) {
            if (storedProjectionBitmap == null) {
                withContext(Dispatchers.IO) {
                    BackdropCaptureStore.loadPersistedBitmapIfNeeded(context)
                }
            }
        }

        LaunchedEffect(customWallpaperUri) {
            if (customWallpaperUri.isNullOrBlank()) {
                userBitmap = null
            } else {
                userBitmap = withContext(Dispatchers.IO) {
                    decodeSampledBitmapFromUri(context, Uri.parse(customWallpaperUri), 1440, 3200)
                }
            }
        }

        val effectiveProjectionBitmap = mediaProjectionBitmap ?: storedProjectionBitmap

        val activeSource = when {
            effectiveProjectionBitmap != null -> BackgroundTextureSource.MEDIA_PROJECTION_FRAME
            userBitmap != null -> BackgroundTextureSource.USER_SELECTED_URI
            else -> BackgroundTextureSource.PROCEDURAL_LIQUID_DEPTH
        }

        Box(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(sharedBackdrop),
            ) {
                when (activeSource) {
                    BackgroundTextureSource.MEDIA_PROJECTION_FRAME -> {
                        effectiveProjectionBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

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

                    BackgroundTextureSource.PROCEDURAL_LIQUID_DEPTH -> {
                        ProceduralLiquidDepthCanvas()
                    }
                }
            }

            content(activeSource)
        }
    }

    @Composable
    private fun ProceduralLiquidDepthCanvas() {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF010613),
                        Color(0xFF030D24),
                        Color(0xFF061534),
                    ),
                ),
            )

            // Upper-right cobalt/sapphire lobe matching 96372.jpg
            val upperWave = Path().apply {
                moveTo(w * 0.36f, 0f)
                cubicTo(
                    w * 0.25f, h * 0.22f,
                    w * 0.35f, h * 0.50f,
                    w * 0.88f, h * 0.66f,
                )
                cubicTo(
                    w * 0.96f, h * 0.36f,
                    w * 0.94f, h * 0.12f,
                    w * 0.82f, 0f,
                )
                close()
            }
            drawPath(
                path = upperWave,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0B1C48),
                        Color(0xFF2B4D9B),
                        Color(0xFF567DD4),
                        Color(0xFF152964),
                    ),
                    start = Offset(w * 0.32f, h * 0.08f),
                    end = Offset(w * 0.92f, h * 0.62f),
                ),
            )

            // Lower-left emerald/teal lobe intersecting the PIN grid matching 96372.jpg
            val emeraldWave = Path().apply {
                moveTo(0f, h * 0.33f)
                cubicTo(
                    w * 0.45f, h * 0.39f,
                    w * 0.78f, h * 0.63f,
                    w * 0.63f, h * 0.85f,
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
                        Color(0xFF03282A),
                        Color(0xFF0F685C),
                        Color(0xFF2EA98B),
                        Color(0xFF031F25),
                    ),
                    start = Offset(0f, h * 0.35f),
                    end = Offset(w * 0.70f, h * 0.80f),
                ),
            )

            // Bottom sweeping blue-violet lobe matching 96372.jpg
            val bottomSweep = Path().apply {
                moveTo(0f, h * 0.94f)
                cubicTo(
                    w * 0.38f, h * 0.80f,
                    w * 0.74f, h * 0.66f,
                    w, h * 0.55f,
                )
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(
                path = bottomSweep,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1D3670),
                        Color(0xFF3B5691),
                        Color(0xFF5A73A8),
                    ),
                    startY = h * 0.55f,
                    endY = h,
                ),
            )
        }
    }

    fun captureSingleFrameFromMediaProjection(
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int,
        stopProjectionAfterCapture: Boolean = false,
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
                    if (stopProjectionAfterCapture) {
                        try {
                            mediaProjection.stop()
                        } catch (_: Throwable) {
                        }
                    }
                }
            }, mainHandler)
        } catch (_: Throwable) {
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
