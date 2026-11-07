// Copyright 2026, compose-miuix-ui contributors & DigitalLock Engineering Team
// SPDX-License-Identifier: Apache-2.0

package component.liquid

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader as AndroidRuntimeShader
import android.graphics.Shader as AndroidShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

@Stable
interface Backdrop {
    val isCoordinatesDependent: Boolean
    val offsetResidualX: Float get() = 0f
    val offsetResidualY: Float get() = 0f

    fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    )
}

@Stable
class CombinedBackdrop(
    val first: Backdrop,
    val second: Backdrop,
) : Backdrop {

    override val isCoordinatesDependent: Boolean = first.isCoordinatesDependent || second.isCoordinatesDependent

    override val offsetResidualX: Float get() = first.offsetResidualX
    override val offsetResidualY: Float get() = first.offsetResidualY

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        with(first) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        with(second) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
    }
}

@Composable
fun rememberCombinedBackdrop(first: Backdrop, second: Backdrop): Backdrop =
    remember(first, second) { CombinedBackdrop(first, second) }

@Stable
class LayerBackdrop : Backdrop {
    override val isCoordinatesDependent: Boolean = true
    internal var recordedLayer: GraphicsLayer? = null
    internal var layerPositionInWindow: Offset = Offset.Zero

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        val layer = recordedLayer ?: return
        val targetPos = coordinates?.positionInWindow() ?: Offset.Zero
        val dx = layerPositionInWindow.x - targetPos.x
        val dy = layerPositionInWindow.y - targetPos.y
        drawContext.canvas.save()
        drawContext.canvas.translate(dx, dy)
        drawLayer(layer)
        drawContext.canvas.restore()
    }
}

@Composable
fun rememberLayerBackdrop(): LayerBackdrop = remember { LayerBackdrop() }

fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier =
    this then LayerBackdropRecordElement(backdrop)

private data class LayerBackdropRecordElement(
    val backdrop: LayerBackdrop,
) : ModifierNodeElement<LayerBackdropRecordNode>() {
    override fun create(): LayerBackdropRecordNode = LayerBackdropRecordNode(backdrop)
    override fun update(node: LayerBackdropRecordNode) {
        node.backdrop = backdrop
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
    }
}

private class LayerBackdropRecordNode(
    var backdrop: LayerBackdrop,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var internalLayer: GraphicsLayer? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        backdrop.layerPositionInWindow = coordinates.positionInWindow()
    }

    override fun onAttach() {
        internalLayer = requireGraphicsContext().createGraphicsLayer().also {
            backdrop.recordedLayer = it
        }
    }

    override fun onDetach() {
        internalLayer?.let {
            if (backdrop.recordedLayer === it) {
                backdrop.recordedLayer = null
            }
            requireGraphicsContext().releaseGraphicsLayer(it)
            internalLayer = null
        }
    }

    override fun ContentDrawScope.draw() {
        val layer = internalLayer
        if (layer != null) {
            layer.record {
                this@draw.drawContent()
            }
            drawLayer(layer)
        } else {
            drawContent()
        }
    }
}

@Immutable
data class LightPosition(
    val x: Float,
    val y: Float,
    val z: Float,
)

@Immutable
data class LightSource(
    val position: LightPosition,
    val color: Color,
    val intensity: Float,
)

@Immutable
data class BloomStroke(
    val color: Color,
    val innerBlurRadius: Dp,
    val primaryLight: LightSource,
    val secondaryLight: LightSource,
    val dualPeak: Boolean = true,
)

@Immutable
data class Highlight(
    val width: Dp,
    val alpha: Float,
    val style: Any,
)

@Immutable
data class DeviceTilt(
    val gravityX: Float = 0f,
    val gravityY: Float = -1f,
    val gravityZ: Float = 0f,
)

@Composable
fun rememberDeviceTilt(): State<DeviceTilt> {
    val context = LocalContext.current
    val tiltState = remember { mutableStateOf(DeviceTilt()) }
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val v = event?.values ?: return
                if (v.size >= 2) {
                    tiltState.value = DeviceTilt(
                        gravityX = (v[0] / 9.81f).coerceIn(-1f, 1f),
                        gravityY = (-v[1] / 9.81f).coerceIn(-1f, 1f),
                        gravityZ = if (v.size >= 3) (v[2] / 9.81f).coerceIn(-1f, 1f) else 0f,
                    )
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensorManager != null && gravitySensor != null) {
            sensorManager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }
    return tiltState
}

class RuntimeShaderUniformScope(private val shader: AndroidRuntimeShader) {
    fun setFloatUniform(name: String, v1: Float) {
        shader.setFloatUniform(name, v1)
    }
    fun setFloatUniform(name: String, v1: Float, v2: Float) {
        shader.setFloatUniform(name, v1, v2)
    }
    fun setFloatUniform(name: String, values: FloatArray) {
        shader.setFloatUniform(name, values)
    }
}

class BackdropEffectScope(
    val size: Size,
    val layoutDirection: LayoutDirection,
    val shape: Shape,
    private val densityImpl: Density,
    val downscaleFactor: Int = 1,
    private val shaderCache: MutableMap<String, AndroidRuntimeShader>,
) : Density by densityImpl {

    var padding: Float = 0f
    private var chainedEffect: AndroidRenderEffect? = null

    internal fun buildRenderEffect(): RenderEffect? =
        chainedEffect?.asComposeRenderEffect()

    internal fun appendEffect(next: AndroidRenderEffect) {
        val current = chainedEffect
        chainedEffect = if (current == null) {
            next
        } else {
            AndroidRenderEffect.createChainEffect(next, current)
        }
    }

    internal fun getOrCreateShader(key: String, shaderString: String): AndroidRuntimeShader {
        return shaderCache.getOrPut(key) { AndroidRuntimeShader(shaderString) }
    }
}

fun BackdropEffectScope.isRuntimeShaderSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

fun BackdropEffectScope.colorControls(
    brightness: Float,
    contrast: Float,
    saturation: Float,
) {
    val satMatrix = ColorMatrix().apply { setSaturation(saturation) }
    val scale = contrast
    val translate = (-0.5f * scale + 0.5f + brightness) * 255f
    val contrastMatrix = ColorMatrix(
        floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    satMatrix.postConcat(contrastMatrix)
    val filterEffect = AndroidRenderEffect.createColorFilterEffect(
        ColorMatrixColorFilter(satMatrix)
    )
    appendEffect(filterEffect)
}

fun BackdropEffectScope.blur(radiusX: Float, radiusY: Float) {
    if (radiusX <= 0f && radiusY <= 0f) return
    val blurEffect = AndroidRenderEffect.createBlurEffect(
        radiusX.coerceAtLeast(0.1f),
        radiusY.coerceAtLeast(0.1f),
        AndroidShader.TileMode.CLAMP,
    )
    appendEffect(blurEffect)
}

fun BackdropEffectScope.runtimeShaderEffect(
    key: String,
    shaderString: String,
    uniformShaderName: String,
    block: RuntimeShaderUniformScope.() -> Unit,
) {
    if (!isRuntimeShaderSupported()) return
    // Reuse cached compiled RuntimeShader per node to eliminate per-frame compilation lag!
    val runtimeShader = getOrCreateShader(key, shaderString)
    RuntimeShaderUniformScope(runtimeShader).apply(block)
    val effect = AndroidRenderEffect.createRuntimeShaderEffect(
        runtimeShader,
        uniformShaderName,
    )
    appendEffect(effect)
}

fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    highlight: (() -> Highlight)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
): Modifier = this then DrawBackdropElement(
    backdrop = backdrop,
    shapeProvider = shape,
    effectsBlock = effects,
    highlightProvider = highlight,
    layerBlock = layerBlock,
    onDrawSurface = onDrawSurface,
)

private data class DrawBackdropElement(
    val backdrop: Backdrop,
    val shapeProvider: () -> Shape,
    val effectsBlock: BackdropEffectScope.() -> Unit,
    val highlightProvider: (() -> Highlight)?,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    val onDrawSurface: (DrawScope.() -> Unit)?,
) : ModifierNodeElement<DrawBackdropNode>() {
    override fun create(): DrawBackdropNode = DrawBackdropNode(
        backdrop = backdrop,
        shapeProvider = shapeProvider,
        effectsBlock = effectsBlock,
        highlightProvider = highlightProvider,
        layerBlock = layerBlock,
        onDrawSurface = onDrawSurface,
    )

    override fun update(node: DrawBackdropNode) {
        node.backdrop = backdrop
        node.shapeProvider = shapeProvider
        node.effectsBlock = effectsBlock
        node.highlightProvider = highlightProvider
        node.layerBlock = layerBlock
        node.onDrawSurface = onDrawSurface
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "drawBackdrop"
    }
}

private class DrawBackdropNode(
    var backdrop: Backdrop,
    var shapeProvider: () -> Shape,
    var effectsBlock: BackdropEffectScope.() -> Unit,
    var highlightProvider: (() -> Highlight)?,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
    var onDrawSurface: (DrawScope.() -> Unit)?,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private var effectLayer: GraphicsLayer? = null
    private var currentCoords: LayoutCoordinates? = null
    private val clipPath = Path()
    private val shaderCache = mutableMapOf<String, AndroidRuntimeShader>()

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        currentCoords = coordinates
    }

    override fun onAttach() {
        effectLayer = requireGraphicsContext().createGraphicsLayer().apply {
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }

    override fun onDetach() {
        shaderCache.clear()
        effectLayer?.let {
            requireGraphicsContext().releaseGraphicsLayer(it)
            effectLayer = null
        }
        currentCoords = null
    }

    override fun ContentDrawScope.draw() {
        val currentShape = shapeProvider()
        val outline = currentShape.createOutline(size, layoutDirection, this)
        clipPath.reset()
        when (outline) {
            is Outline.Rectangle -> clipPath.addRect(outline.rect)
            is Outline.Rounded -> clipPath.addRoundRect(outline.roundRect)
            is Outline.Generic -> clipPath.addPath(outline.path)
        }

        val scope = BackdropEffectScope(
            size = size,
            layoutDirection = layoutDirection,
            shape = currentShape,
            densityImpl = this,
            downscaleFactor = 1,
            shaderCache = shaderCache,
        ).apply(effectsBlock)

        val layer = effectLayer
        if (layer != null) {
            layer.renderEffect = scope.buildRenderEffect()
            layer.record {
                with(backdrop) {
                    drawBackdrop(
                        density = this@draw,
                        coordinates = currentCoords,
                        layerBlock = layerBlock,
                        downscaleFactor = 1,
                    )
                }
            }
            drawContext.canvas.save()
            drawContext.canvas.clipPath(clipPath)
            drawLayer(layer)
            onDrawSurface?.invoke(this)
            drawHighlightRim(highlightProvider?.invoke(), outline)
            drawContext.canvas.restore()
        } else {
            onDrawSurface?.invoke(this)
        }

        drawContent()
    }

    private fun DrawScope.drawHighlightRim(highlight: Highlight?, outline: Outline) {
        if (highlight == null || highlight.alpha <= 0f) return
        val style = highlight.style as? BloomStroke ?: return
        val strokeWidthPx = highlight.width.toPx().coerceAtLeast(1f)
        val primaryX = (style.primaryLight.position.x.coerceIn(0f, 1f)) * size.width
        val primaryY = (style.primaryLight.position.y.coerceIn(0f, 1f)) * size.height
        val secondaryX = (style.secondaryLight.position.x.coerceIn(0f, 1f)) * size.width
        val secondaryY = (style.secondaryLight.position.y.coerceIn(0f, 1f)) * size.height

        val rimBrush = Brush.linearGradient(
            colors = listOf(
                style.primaryLight.color.copy(
                    alpha = (0.78f * style.primaryLight.intensity * highlight.alpha).coerceIn(0f, 1f)
                ),
                style.color.copy(alpha = (style.color.alpha * highlight.alpha).coerceIn(0f, 1f)),
                style.secondaryLight.color.copy(
                    alpha = (0.62f * style.secondaryLight.intensity * highlight.alpha).coerceIn(0f, 1f)
                ),
            ),
            start = Offset(primaryX, primaryY),
            end = Offset(secondaryX, secondaryY),
        )

        when (outline) {
            is Outline.Rounded -> {
                val r = outline.roundRect.topLeftCornerRadius
                drawRoundRect(
                    brush = rimBrush,
                    cornerRadius = r,
                    style = Stroke(width = strokeWidthPx),
                )
            }
            is Outline.Rectangle -> {
                drawRect(
                    brush = rimBrush,
                    style = Stroke(width = strokeWidthPx),
                )
            }
            is Outline.Generic -> {
                drawPath(
                    path = outline.path,
                    brush = rimBrush,
                    style = Stroke(width = strokeWidthPx),
                )
            }
        }
    }
}
