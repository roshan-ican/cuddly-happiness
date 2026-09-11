package com.example.cameraremotecontroller

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.cameraremotecontroller.ui.theme.CameraRemoteControllerTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt

// // PC Tunnel URLs (use when PC is bridging the connection)
// private const val CAM1_URL = "rtsp://10.252.176.114:8554/main.264"
// private const val CAM2_URL = "rtsp://10.252.176.114:8555/main.264"

// Real Camera URLs (Uncomment and use when phone is directly on the camera network)
private const val CAM1_URL = "rtspll://192.168.144.25:8554/main.264"
private const val CAM2_URL = "rtspll://192.168.144.26:8554/main.264"

private data class CameraSource(
        val id: Int,
        val name: String,
        val url: String?,
        val rotation: Int = 90,
)

private val DEFAULT_CAMERAS =
        listOf(
                CameraSource(1, "CAM 1", CAM1_URL),
                CameraSource(2, "CAM 2", CAM2_URL),
        )

private const val SHOW_LATENCY_CLOCK = false

private const val SHOW_DECODE_LATENCY = false

private const val PREFS_NAME = "camera_remote_controller"
private const val PREFS_KEY_CAMERAS = "cameras"
private const val PREFS_KEY_LAYOUT = "layout_mode"
private const val CAMERAS_PER_PAGE = 9

private enum class CameraLayoutMode(val slots: Int) {
    SPLIT(2),
    FOCUS_LEFT(3),
    FOCUS_TOP(3),
    AUTO_GRID(4),
}

private fun SharedPreferences.loadCameras(): List<CameraSource> {
    val raw = getString(PREFS_KEY_CAMERAS, null) ?: return DEFAULT_CAMERAS

    val stored =
            raw.lines().mapNotNull { line ->
                val parts = line.split('|')
                val name = parts.getOrNull(0)?.trim().orEmpty()
                val url = parts.getOrNull(1)?.trim().orEmpty()
                val rotation = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 90

                if (name.isEmpty() || url.isEmpty()) null else Triple(name, url, rotation)
            }

    if (stored.isEmpty()) return DEFAULT_CAMERAS

    return stored.mapIndexed { index, (name, url, rotation) ->
        CameraSource(index + 1, name, url, rotation)
    }
}

private fun SharedPreferences.saveCameras(cameras: List<CameraSource>) {
    val raw =
            cameras.filter { it.url != null }.joinToString("\n") {
                "${it.name}|${it.url}|${it.rotation}"
            }

    edit().putString(PREFS_KEY_CAMERAS, raw).apply()
}

private fun normalizeCameraUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    // Everything lands on rtspll://: it is the only player left.
    val candidate =
            when {
                trimmed.startsWith("rtspll://", ignoreCase = true) -> trimmed
                trimmed.startsWith("rtsp://", ignoreCase = true) ->
                        "rtspll://" + trimmed.substring(7)
                trimmed.contains("://") -> return null
                else -> "rtspll://$trimmed"
            }

    return if (parseRtspLowLatencyUrl(candidate) != null) candidate else null
}

private val Background = Color(0xFF111216)
private val HeaderBackground = Color(0xFF090A0D)
private val BorderColor = Color(0xFF2A2D33)
private val Green = Color(0xFF3EDC81)
private val Red = Color(0xFFF34F55)
private val MutedText = Color(0xFF8296A8)

private val SettingsSurface = Color(0xFF1C1C1E)
private val SettingsCard = Color(0xFF2C2C2E)
private val SettingsFill = Color(0xFF3A3A3C)
private val SettingsThumb = Color(0xFF636366)
private val SettingsSeparator = Color(0xFF3D3D41)
private val SettingsSecondary = Color(0xFF98989F)
private val SettingsTertiary = Color(0xFF7C7C80)

private const val CODEC_TAG = "CodecProbe"

private const val H264_MIME = "video/avc"

private fun logH264DecoderCapabilities() {
    val codecs =
            try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            } catch (e: Exception) {
                Log.w(CODEC_TAG, "codec list unavailable", e)
                return
            }

    val decoders =
            codecs.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(H264_MIME, true) }
            }

    if (decoders.isEmpty()) {
        Log.w(CODEC_TAG, "no $H264_MIME decoder on this device")
        return
    }

    Log.i(CODEC_TAG, "api=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL}")

    decoders.forEach { info ->
        val lowLatency =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        info.getCapabilitiesForType(H264_MIME)
                                .isFeatureSupported(
                                        MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency,
                                )
                                .toString()
                    } catch (_: Exception) {
                        "error"
                    }
                } else {
                    "needs-api-30"
                }

        Log.i(
                CODEC_TAG,
                "${info.name} hardware=${info.isHardwareAccelerated} " +
                        "vendor=${info.isVendor} lowLatency=$lowLatency",
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logH264DecoderCapabilities()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { CameraRemoteControllerTheme { ControllerDashboard() } }
    }
}

@Composable
private fun KeepWifiLowLatency() {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val lock =
                wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                        "camera-remote-controller:stream",
                )

        lock.setReferenceCounted(false)
        lock.acquire()

        onDispose { if (lock.isHeld) lock.release() }
    }
}

@Composable
fun ControllerDashboard() {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    KeepWifiLowLatency()

    val cameras = remember {
        mutableStateListOf<CameraSource>().apply { addAll(prefs.loadCameras()) }
    }

    var primaryId by remember { mutableStateOf(cameras.firstOrNull()?.id ?: 0) }
    var showSettings by remember { mutableStateOf(false) }
    var layoutMode by remember {
        mutableStateOf(
                prefs.getString(PREFS_KEY_LAYOUT, null)?.let {
                    runCatching { CameraLayoutMode.valueOf(it) }.getOrNull()
                } ?: CameraLayoutMode.AUTO_GRID
        )
    }
    var pageIndex by remember { mutableStateOf(0) }
    var columnRatio by remember { mutableStateOf(0.65f) }
    var rowRatio by remember { mutableStateOf(0.5f) }

    var floatingId by remember { mutableStateOf<Int?>(null) }
    var stowedLeft by remember { mutableStateOf<Boolean?>(null) }
    var floatX by remember { mutableStateOf(0f) }
    var floatY by remember { mutableStateOf(0f) }
    var floatScale by remember { mutableStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var floatWasMain by remember { mutableStateOf(false) }

    val pageCount = ((cameras.size + CAMERAS_PER_PAGE - 1) / CAMERAS_PER_PAGE).coerceAtLeast(1)
    val pageCameras = cameras.drop(pageIndex * CAMERAS_PER_PAGE).take(CAMERAS_PER_PAGE)
    val floating = pageCameras.firstOrNull { it.id == floatingId }
    val split = pageCameras.filter { it.id != floating?.id }
    val main = split.firstOrNull { it.id == primaryId } ?: split.firstOrNull()
    val docked = split.filter { it.id != main?.id }

    LaunchedEffect(cameras.size, pageIndex) {
        if (pageIndex >= pageCount) pageIndex = pageCount - 1
        if (cameras.none { it.id == floatingId }) {
            floatingId = null
            stowedLeft = null
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
            if (SHOW_LATENCY_CLOCK) {
                LatencyClock(modifier = Modifier.align(Alignment.BottomStart).zIndex(10f))
            }

            val screenW = maxWidth
            val screenH = maxHeight
            val margin = 12.dp

            val floatBaseW = minOf(screenW, screenH) * 0.32f
            val floatW = floatBaseW * floatScale
            // Match the pane to the rotated video so the fitted surface leaves no bars.
            val floatUpright = floating?.rotation?.let { it == 90 || it == 270 } ?: true
            val floatH =
                    minOf(
                            if (floatUpright) floatW * 16f / 9f else floatW * 9f / 16f,
                            screenH * 0.8f,
                    )
            val canFloat = pageCameras.size >= 2
            val orderedSplit = listOfNotNull(main) + docked
            val visibleSplit = orderedSplit
            val paneCount =
                    if (floating != null) visibleSplit.size.coerceAtLeast(1)
                    else maxOf(visibleSplit.size, layoutMode.slots)

            fun templateBounds(index: Int, count: Int = paneCount): PaneBounds {
                if (count <= 1) return PaneBounds(0.dp, 0.dp, screenW, screenH)
                val screenAspect = screenW.value / screenH.value.coerceAtLeast(1f)
                if (layoutMode == CameraLayoutMode.AUTO_GRID || layoutMode == CameraLayoutMode.SPLIT) {
                    val columns =
                            if (count == 2) (if (screenW >= screenH) 2 else 1)
                            else gridColumns(count, screenW.value, screenH.value, screenAspect)
                    val rows = ceil(count.toFloat() / columns).toInt()
                    val cellW = screenW / columns
                    val cellH = screenH / rows
                    val column = index % columns
                    val row = index / columns
                    return PaneBounds(cellW * column, cellH * row, cellW, cellH)
                }

                val secondaryCount = count - 1
                if (layoutMode == CameraLayoutMode.FOCUS_LEFT) {
                    val firstW = screenW * columnRatio
                    if (index == 0) return PaneBounds(0.dp, 0.dp, firstW, screenH)
                    val rightW = screenW - firstW
                    val columns =
                            gridColumns(secondaryCount, rightW.value, screenH.value, screenAspect)
                    val rows = ceil(secondaryCount.toFloat() / columns).toInt()
                    val slot = index - 1
                    val cellW = rightW / columns
                    val cellH = screenH / rows
                    return PaneBounds(
                            firstW + cellW * (slot % columns),
                            cellH * (slot / columns),
                            cellW,
                            cellH,
                    )
                } else {
                    val firstH = screenH * columnRatio
                    if (index == 0) return PaneBounds(0.dp, 0.dp, screenW, firstH)
                    val bottomH = screenH - firstH
                    val columns =
                            gridColumns(secondaryCount, screenW.value, bottomH.value, screenAspect)
                    val rows = ceil(secondaryCount.toFloat() / columns).toInt()
                    val slot = index - 1
                    val cellW = screenW / columns
                    val cellH = bottomH / rows
                    return PaneBounds(
                            cellW * (slot % columns),
                            firstH + cellH * (slot / columns),
                            cellW,
                            cellH,
                    )
                }
            }

            val floatBounds =
                    PaneBounds(
                            x =
                                    when (stowedLeft) {
                                        true -> margin - floatW
                                        false -> screenW - margin
                                        null -> floatX.dp
                                    },
                            y = floatY.dp,
                            w = floatW,
                            h = floatH,
                    )

            fun settleFloat() {
                dragging = false
                val edgeSlack = floatW.value * 0.5f
                when {
                    floatX < -edgeSlack -> stowedLeft = true
                    floatX > screenW.value - edgeSlack -> stowedLeft = false
                    else -> {
                        stowedLeft = null
                        floatX =
                                if (floatX + floatW.value / 2f < screenW.value / 2f) margin.value
                                else screenW.value - floatW.value - margin.value
                        floatY =
                                floatY.coerceIn(
                                        margin.value,
                                        (screenH.value - floatH.value - margin.value).coerceAtLeast(
                                                margin.value
                                        ),
                                )
                    }
                }
            }

            fun dock() {
                if (floatWasMain) floatingId?.let { primaryId = it }
                floatingId = null
                stowedLeft = null
                floatWasMain = false
            }

            pageCameras.filter { it.id == floatingId || visibleSplit.any { visible -> visible.id == it.id } }
                    .sortedBy { it.id == floatingId }
                    .forEach { cam ->
                key(cam.id) {
                    val isFloat = cam.id == floatingId
                    val isMain = !isFloat && cam.id == main?.id
                    var videoTransform by remember(cam.id) { mutableStateOf(VideoTransform()) }
                    var activePointers by remember(cam.id) { mutableStateOf(0) }
                    var paneDragActive by remember(cam.id) { mutableStateOf(false) }

                    val bounds =
                            when {
                                isFloat -> floatBounds
                                else -> templateBounds(visibleSplit.indexOfFirst { it.id == cam.id })
                            }

                    fun minimize() {
                        floatWasMain = isMain
                        if (isMain) docked.firstOrNull()?.let { primaryId = it.id }
                        floatingId = cam.id
                        stowedLeft = null
                        floatX = screenW.value - floatW.value - margin.value
                        floatY = margin.value + 44f
                    }

                    val resizeFloat by rememberUpdatedState<(Float) -> Unit>({ zoom ->
                        val oldScale = floatScale
                        val newScale = (oldScale * zoom).coerceIn(0.65f, 1.65f)
                        if (newScale != oldScale) {
                            val oldW = floatW.value
                            val oldH = floatH.value
                            val newW = floatBaseW.value * newScale
                            val newH =
                                    minOf(
                                            if (floatUpright) newW * 16f / 9f else newW * 9f / 16f,
                                            screenH.value * 0.8f,
                                    )
                            floatX =
                                    (floatX + (oldW - newW) / 2f).coerceIn(
                                            margin.value,
                                            (screenW.value - newW - margin.value).coerceAtLeast(margin.value),
                                    )
                            floatY =
                                    (floatY + (oldH - newH) / 2f).coerceIn(
                                            margin.value,
                                            (screenH.value - newH - margin.value).coerceAtLeast(margin.value),
                                    )
                            floatScale = newScale
                            stowedLeft = null
                        }
                    })

                    // Keep the detector alive when the pane changes roles mid-gesture.
                    val startDrag by rememberUpdatedState<(androidx.compose.ui.geometry.Offset) -> Unit>({ touch ->
                        if (!isFloat) {
                            minimize()
                            floatX = bounds.x.value + touch.x / context.resources.displayMetrics.density - floatW.value / 2f
                            floatY = bounds.y.value + touch.y / context.resources.displayMetrics.density - floatH.value / 2f
                        }
                        dragging = true
                    })
                    val finishDrag by rememberUpdatedState { settleFloat() }
                    val currentTransform by rememberUpdatedState(videoTransform)
                    val currentIsFloat by rememberUpdatedState(isFloat)
                    val currentFloatScale by rememberUpdatedState(floatScale)
                    val pointerTracker =
                            Modifier.pointerInput(cam.id) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val count = event.changes.count { it.pressed }
                                        if (activePointers > 0 && count == 0 && paneDragActive) {
                                            paneDragActive = false
                                            finishDrag()
                                        }
                                        activePointers = count
                                    }
                                }
                            }
                    val transformGestures =
                            Modifier.pointerInput(cam.id, cam.rotation) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    val before = currentTransform
                                    if (currentIsFloat && (activePointers >= 2 || zoom != 1f)) {
                                        if (paneDragActive) {
                                            paneDragActive = false
                                            finishDrag()
                                        }
                                        resizeFloat(zoom)
                                    } else if (activePointers >= 2 || zoom != 1f || before.scale > 1f) {
                                        if (paneDragActive) {
                                            paneDragActive = false
                                            finishDrag()
                                        }
                                        val normalizedX = pan.x / size.width.coerceAtLeast(1)
                                        val normalizedY = pan.y / size.height.coerceAtLeast(1)
                                        val rotated =
                                                rotateGestureIntoVideo(
                                                        cam.rotation,
                                                        normalizedX,
                                                        normalizedY,
                                                )
                                        videoTransform =
                                                updateVideoTransform(
                                                        before,
                                                        zoom,
                                                        rotated.first,
                                                        rotated.second,
                                                )
                                    } else if (canFloat) {
                                        if (!paneDragActive) {
                                            startDrag(centroid)
                                            paneDragActive = true
                                        }
                                        floatX += pan.x.toDp().value
                                        floatY += pan.y.toDp().value
                                    }
                                }
                            }

                    val floatSkin =
                            if (isFloat)
                                    Modifier.clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                            else Modifier

                    CameraPanel(
                            cameraName = cam.name,
                            modifier =
                                    paneModifier(bounds, animate = false)
                                             .zIndex(if (isFloat) 2f else 0f)
                                             .then(floatSkin)
                                             .then(pointerTracker)
                                             .then(transformGestures),
                            streamUrl = cam.url,
                            rotation = cam.rotation,
                            floating = isFloat,
                            videoTransform = videoTransform,
                            onClick = {
                                when {
                                    isFloat -> dock()
                                    canFloat -> minimize()
                                }
                            },
                            onDoubleClick = { videoTransform = VideoTransform() },
                    ) {
                        if (isFloat) {
                            Box(
                                    modifier =
                                            Modifier.align(Alignment.BottomEnd)
                                                    .size(56.dp)
                                                    .pointerInput(cam.id) {
                                                        detectDragGestures { change, dragAmount ->
                                                            change.consume()
                                                            val density =
                                                                    context.resources.displayMetrics.density
                                                            val deltaDp =
                                                                    (dragAmount.x + dragAmount.y) /
                                                                            (2f * density)
                                                            val nextScale =
                                                                    currentFloatScale +
                                                                            deltaDp /
                                                                                    floatBaseW.value
                                                            resizeFloat(nextScale / currentFloatScale)
                                                        }
                                                    },
                            )
                        }
                    }
                }
            }

            repeat((paneCount - visibleSplit.size).coerceAtLeast(0)) { placeholderIndex ->
                CameraPanel(
                        cameraName = "CAM ${visibleSplit.size + placeholderIndex + 1}",
                        modifier =
                                paneModifier(
                                        templateBounds(visibleSplit.size + placeholderIndex),
                                        animate = false,
                                ),
                        streamUrl = null,
                        onClick = { showSettings = true },
                )
            }

            if ((layoutMode == CameraLayoutMode.FOCUS_LEFT || layoutMode == CameraLayoutMode.FOCUS_TOP) &&
                            paneCount > 1
            ) {
                LayoutDividers(
                        slots = 2,
                        isLandscape = layoutMode == CameraLayoutMode.FOCUS_LEFT,
                        screenW = screenW,
                        screenH = screenH,
                        columnRatio = columnRatio,
                        rowRatio = rowRatio,
                        onColumnRatioChange = { columnRatio = it.coerceIn(0.35f, 0.8f) },
                        onRowRatioChange = {},
                        modifier = Modifier.zIndex(1f),
                )
            }

            stowedLeft?.let { left ->
                StowPill(
                        modifier =
                                Modifier.offset(
                                                x = if (left) 0.dp else screenW - 15.dp,
                                                y =
                                                        (floatY.dp + floatH / 2f - 27.dp).coerceIn(
                                                                margin,
                                                                (screenH - 54.dp - margin)
                                                                        .coerceAtLeast(margin),
                                                        ),
                                        )
                                        .zIndex(3f),
                        left = left,
                        onClick = {
                            stowedLeft = null
                            floatX =
                                    if (left) margin.value
                                    else screenW.value - floatW.value - margin.value
                        },
                )
            }

            SettingsButton(
                    modifier = Modifier.align(Alignment.TopEnd).padding(11.dp).zIndex(4f),
                    onClick = { showSettings = true },
            )
            if (pageCount > 1) {
                PageSelector(
                        page = pageIndex,
                        pageCount = pageCount,
                        onPrevious = {
                            floatingId = null
                            pageIndex = (pageIndex - 1).coerceAtLeast(0)
                        },
                        onNext = {
                            floatingId = null
                            pageIndex = (pageIndex + 1).coerceAtMost(pageCount - 1)
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(11.dp).zIndex(4f),
                )
            }
        }
    }

    if (showSettings) {
        CameraSettingsDialog(
                cameras = cameras,
                selectedLayout = layoutMode,
                onDismiss = { showSettings = false },
                onSetLayout = {
                    layoutMode = it
                    floatingId = null
                    stowedLeft = null
                    prefs.edit().putString(PREFS_KEY_LAYOUT, it.name).apply()
                },
                onAdd = { name, url ->
                    val id = (cameras.maxOfOrNull { it.id } ?: 0) + 1
                    cameras.add(CameraSource(id, name, url))
                    prefs.saveCameras(cameras)
                },
                onRemove = { camera ->
                    cameras.remove(camera)
                    prefs.saveCameras(cameras)
                    if (primaryId == camera.id) primaryId = cameras.firstOrNull()?.id ?: 0
                },
                onRename = { camera, newName ->
                    val index = cameras.indexOfFirst { it.id == camera.id }
                    if (index >= 0) {
                        cameras[index] = camera.copy(name = newName)
                        prefs.saveCameras(cameras)
                    }
                },
                onSetRotation = { camera, rotation ->
                    val index = cameras.indexOfFirst { it.id == camera.id }
                    if (index >= 0) {
                        cameras[index] = camera.copy(rotation = rotation)
                        prefs.saveCameras(cameras)
                    }
                },
                onSetUrl = { camera, newUrl ->
                    val index = cameras.indexOfFirst { it.id == camera.id }
                    if (index >= 0) {
                        cameras[index] = camera.copy(url = newUrl)
                        prefs.saveCameras(cameras)
                    }
                },
        )
    }
}

private data class PaneBounds(val x: Dp, val y: Dp, val w: Dp, val h: Dp)

private fun gridColumns(count: Int, width: Float, height: Float, targetAspect: Float): Int =
        (1..count)
                .filter { columns ->
                    val rows = ceil(count.toFloat() / columns).toInt()
                    (columns - 1) * rows < count && columns * (rows - 1) < count
                }
                .minBy { columns ->
                    val rows = ceil(count.toFloat() / columns).toInt()
                    abs(ln((width / columns) / (height.coerceAtLeast(1f) / rows) / targetAspect))
                }

@Composable
private fun paneModifier(bounds: PaneBounds, animate: Boolean): Modifier {
    val spec: AnimationSpec<Dp> =
            if (animate)
                    spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                    )
            else snap()

    val x by animateDpAsState(targetValue = bounds.x, animationSpec = spec, label = "paneX")
    val y by animateDpAsState(targetValue = bounds.y, animationSpec = spec, label = "paneY")
    val w by animateDpAsState(targetValue = bounds.w, animationSpec = spec, label = "paneW")
    val h by animateDpAsState(targetValue = bounds.h, animationSpec = spec, label = "paneH")

    return Modifier.offset(x, y).size(w, h)
}

@Composable
private fun LayoutDividers(
        slots: Int,
        isLandscape: Boolean,
        screenW: Dp,
        screenH: Dp,
        columnRatio: Float,
        rowRatio: Float,
        onColumnRatioChange: (Float) -> Unit,
        onRowRatioChange: (Float) -> Unit,
        modifier: Modifier = Modifier,
) {
    if (slots <= 1) return
    val density = LocalDensity.current
    val handle = 18.dp
    val line = 2.dp

    @Composable
    fun verticalDivider(x: Dp, y: Dp, height: Dp, onDrag: (Float) -> Unit) {
        var dragDelta by remember { mutableStateOf(0f) }
        Box(
                modifier =
                        modifier.offset(x - handle / 2f, y)
                                .offset { IntOffset(dragDelta.roundToInt(), 0) }
                                .size(handle, height)
                                .pointerInput(slots, x) {
                                    detectDragGestures(
                                            onDragEnd = {
                                                onDrag(dragDelta)
                                                dragDelta = 0f
                                            },
                                            onDragCancel = { dragDelta = 0f },
                                    ) { change, amount ->
                                        change.consume()
                                        dragDelta += amount.x
                                    }
                                },
                contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.requiredWidth(line).fillMaxSize().background(BorderColor))
        }
    }

    @Composable
    fun horizontalDivider(x: Dp, y: Dp, width: Dp, onDrag: (Float) -> Unit) {
        var dragDelta by remember { mutableStateOf(0f) }
        Box(
                modifier =
                        modifier.offset(x, y - handle / 2f)
                                .offset { IntOffset(0, dragDelta.roundToInt()) }
                                .size(width, handle)
                                .pointerInput(slots, y) {
                                    detectDragGestures(
                                            onDragEnd = {
                                                onDrag(dragDelta)
                                                dragDelta = 0f
                                            },
                                            onDragCancel = { dragDelta = 0f },
                                    ) { change, amount ->
                                        change.consume()
                                        dragDelta += amount.y
                                    }
                                },
                contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize().requiredHeight(line).background(BorderColor))
        }
    }

    val widthPx = with(density) { screenW.toPx() }
    val heightPx = with(density) { screenH.toPx() }
    if (slots == 2) {
        if (isLandscape) {
            verticalDivider(screenW * columnRatio, 0.dp, screenH) { delta ->
                onColumnRatioChange(columnRatio + delta / widthPx)
            }
        } else {
            horizontalDivider(0.dp, screenH * columnRatio, screenW) { delta ->
                onColumnRatioChange(columnRatio + delta / heightPx)
            }
        }
        return
    }

    if (slots == 3) {
        if (isLandscape) {
            verticalDivider(screenW * columnRatio, 0.dp, screenH) { delta ->
                onColumnRatioChange(columnRatio + delta / widthPx)
            }
            horizontalDivider(
                    screenW * columnRatio,
                    screenH * rowRatio,
                    screenW * (1f - columnRatio),
            ) { delta -> onRowRatioChange(rowRatio + delta / heightPx) }
        } else {
            horizontalDivider(0.dp, screenH * columnRatio, screenW) { delta ->
                onColumnRatioChange(columnRatio + delta / heightPx)
            }
            verticalDivider(
                    screenW * rowRatio,
                    screenH * columnRatio,
                    screenH * (1f - columnRatio),
            ) { delta -> onRowRatioChange(rowRatio + delta / widthPx) }
        }
        return
    }

    verticalDivider(screenW * columnRatio, 0.dp, screenH) { delta ->
        onColumnRatioChange(columnRatio + delta / widthPx)
    }
    horizontalDivider(0.dp, screenH * rowRatio, screenW) { delta ->
        onRowRatioChange(rowRatio + delta / heightPx)
    }
}

private val CameraLayoutMode.label: String
    get() =
            when (this) {
                CameraLayoutMode.SPLIT -> "Split"
                CameraLayoutMode.AUTO_GRID -> "Grid"
                CameraLayoutMode.FOCUS_LEFT -> "Side"
                CameraLayoutMode.FOCUS_TOP -> "Stacked"
            }

@Composable
private fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val progress by
            animateFloatAsState(
                    targetValue = if (pressed) 1f else 0f,
                    animationSpec = spring(dampingRatio = 1f, stiffness = 1500f),
                    label = "press",
            )
    return graphicsLayer {
                scaleX = 1f - 0.04f * progress
                scaleY = 1f - 0.04f * progress
                alpha = 1f - 0.3f * progress
            }
            .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
            )
}

@Composable
private fun LayoutSegmentedControl(
        selected: CameraLayoutMode,
        onSelect: (CameraLayoutMode) -> Unit,
        modifier: Modifier = Modifier,
) {
    val modes = CameraLayoutMode.entries
    BoxWithConstraints(
            modifier =
                    modifier.fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SettingsCard)
                            .padding(2.dp),
    ) {
        val segmentW = maxWidth / modes.size
        val thumbX by
                animateDpAsState(
                        targetValue = segmentW * modes.indexOf(selected),
                        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
                        label = "layoutThumb",
                )
        Box(
                Modifier.offset(x = thumbX)
                        .size(segmentW, maxHeight)
                        .background(SettingsThumb, RoundedCornerShape(8.dp)),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            modes.forEach { mode ->
                val isSelected = mode == selected
                val tint = if (isSelected) Color.White else SettingsSecondary
                Row(
                        modifier = Modifier.weight(1f).fillMaxHeight().pressable { onSelect(mode) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                ) {
                    LayoutGlyph(mode = mode, color = tint)
                    Spacer(Modifier.width(7.dp))
                    Text(
                            mode.label,
                            color = tint,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutGlyph(mode: CameraLayoutMode, color: Color) {
    val gap = 1.5.dp
    val pane = Modifier.background(color, RoundedCornerShape(1.5.dp))
    Box(modifier = Modifier.size(width = 20.dp, height = 14.dp)) {
        when (mode) {
            CameraLayoutMode.SPLIT ->
                    Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        Box(Modifier.weight(1f).fillMaxSize().then(pane))
                        Box(Modifier.weight(1f).fillMaxSize().then(pane))
                    }
            CameraLayoutMode.AUTO_GRID ->
                    Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        repeat(2) {
                            Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(gap),
                            ) {
                                Box(Modifier.weight(1f).fillMaxWidth().then(pane))
                                Box(Modifier.weight(1f).fillMaxWidth().then(pane))
                            }
                        }
                    }
            CameraLayoutMode.FOCUS_LEFT ->
                    Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        Box(Modifier.weight(1.55f).fillMaxSize().then(pane))
                        Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            Box(Modifier.weight(1f).fillMaxWidth().then(pane))
                            Box(Modifier.weight(1f).fillMaxWidth().then(pane))
                        }
                    }
            CameraLayoutMode.FOCUS_TOP ->
                    Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        Box(Modifier.weight(1.45f).fillMaxWidth().then(pane))
                        Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            Box(Modifier.weight(1f).fillMaxSize().then(pane))
                            Box(Modifier.weight(1f).fillMaxSize().then(pane))
                        }
                    }
        }
    }
}

@Composable
private fun PageSelector(
        page: Int,
        pageCount: Int,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
        modifier: Modifier = Modifier,
) {
    Row(
            modifier =
                    modifier.clip(RoundedCornerShape(7.dp))
                            .background(HeaderBackground)
                            .border(1.dp, BorderColor, RoundedCornerShape(7.dp)),
            verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPrevious, enabled = page > 0) { Text("PREV", fontSize = 10.sp) }
        Text(
                "${page + 1} / $pageCount",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
        )
        TextButton(onClick = onNext, enabled = page < pageCount - 1) {
            Text("NEXT", fontSize = 10.sp)
        }
    }
}

@Composable
private fun StowPill(modifier: Modifier, left: Boolean, onClick: () -> Unit) {
    val shape =
            if (left) RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
            else RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)

    Box(
            modifier =
                    modifier.size(width = 15.dp, height = 54.dp)
                            .clip(shape)
                            .background(HeaderBackground)
                            .border(1.dp, BorderColor, shape)
                            .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
    ) {
        Text(
                if (left) ">" else "<",
                color = MutedText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SettingsButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
            modifier =
                    modifier.size(28.dp)
                            .background(color = HeaderBackground, shape = CircleShape)
                            .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
    ) { Text("⚙", color = MutedText, fontSize = 14.sp) }
}

@Composable
private fun CameraSettingsDialog(
        cameras: List<CameraSource>,
        selectedLayout: CameraLayoutMode,
        onDismiss: () -> Unit,
        onSetLayout: (CameraLayoutMode) -> Unit,
        onAdd: (String, String) -> Unit,
        onRemove: (CameraSource) -> Unit,
        onRename: (CameraSource, String) -> Unit,
        onSetRotation: (CameraSource, Int) -> Unit,
        onSetUrl: (CameraSource, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val normalized = normalizeCameraUrl(url)
        if (normalized == null) {
            error = "Enter a valid RTSP or SIYI address"
            return
        }
        onAdd(name.trim().ifEmpty { "CAM ${cameras.size + 1}" }, normalized)
        name = ""
        url = ""
        error = null
    }

    Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val configuration = LocalConfiguration.current
        val wide = configuration.screenWidthDp >= 600
        val shape = RoundedCornerShape(20.dp)
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window

        LaunchedEffect(dialogWindow) {
            dialogWindow?.let { window ->
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }

        @Composable
        fun layoutSection() =
                SettingsLayoutSection(selected = selectedLayout, onSelect = onSetLayout)

        @Composable
        fun camerasSection() =
                SettingsCamerasSection(
                        cameras = cameras,
                        onRename = onRename,
                        onSetRotation = onSetRotation,
                        onRemove = onRemove,
                )

        @Composable
        fun addSection() =
                SettingsAddSection(
                        name = name,
                        url = url,
                        error = error,
                        namePlaceholder = "CAM ${cameras.size + 1}",
                        onNameChange = { name = it },
                        onUrlChange = {
                            url = it
                            error = null
                        },
                        onSubmit = ::submit,
                )

        Column(
                modifier =
                        Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                                .widthIn(max = if (wide) 720.dp else 420.dp)
                                .fillMaxWidth()
                                .heightIn(max = (configuration.screenHeightDp * 0.92f).dp)
                                .clip(shape)
                                .background(SettingsSurface)
                                .border(0.5.dp, Color.White.copy(alpha = 0.08f), shape),
        ) {
            Row(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                        "Settings",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                        modifier = Modifier.weight(1f),
                )
                Text(
                        "Done",
                        color = Green,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                                Modifier.pressable(onClick = onDismiss)
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }

            if (wide) {
                Row(
                        modifier =
                                Modifier.weight(1f, fill = false)
                                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        layoutSection()
                        addSection()
                    }
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        camerasSection()
                    }
                }
            } else {
                Column(
                        modifier =
                                Modifier.weight(1f, fill = false)
                                        .verticalScroll(rememberScrollState())
                                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    layoutSection()
                    camerasSection()
                    addSection()
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(text: String, trailing: String? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp)) {
        Text(
                text.uppercase(),
                color = SettingsSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Text(it, color = SettingsSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SettingsCard),
            content = content,
    )
}

@Composable
private fun SettingsDivider() {
    Box(
            Modifier.padding(start = 16.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(SettingsSeparator),
    )
}

@Composable
private fun SettingsLayoutSection(
        selected: CameraLayoutMode,
        onSelect: (CameraLayoutMode) -> Unit,
) {
    Column {
        SettingsSectionHeader("Layout")
        LayoutSegmentedControl(selected = selected, onSelect = onSelect)
    }
}

@Composable
private fun SettingsCamerasSection(
        cameras: List<CameraSource>,
        onRename: (CameraSource, String) -> Unit,
        onSetRotation: (CameraSource, Int) -> Unit,
        onRemove: (CameraSource) -> Unit,
) {
    Column {
        SettingsSectionHeader("Cameras", trailing = "${cameras.size}")
        SettingsGroup {
            if (cameras.isEmpty()) {
                Text(
                        "No cameras yet",
                        color = SettingsSecondary,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
            cameras.forEachIndexed { index, camera ->
                key(camera.id) {
                    if (index > 0) SettingsDivider()
                    CameraSettingsRow(
                            camera = camera,
                            onRename = { onRename(camera, it) },
                            onRotate = { onSetRotation(camera, (camera.rotation + 90) % 360) },
                            onRemove = { onRemove(camera) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraSettingsRow(
        camera: CameraSource,
        onRename: (String) -> Unit,
        onRotate: () -> Unit,
        onRemove: () -> Unit,
) {
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicTextField(
                    value = camera.name,
                    onValueChange = onRename,
                    singleLine = true,
                    textStyle =
                            TextStyle(
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                            ),
                    cursorBrush = SolidColor(Green),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
            )
            Text(
                    camera.url?.removePrefix("rtspll://") ?: "No address",
                    color = SettingsSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
                "${camera.rotation}°",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier =
                        Modifier.pressable(onClick = onRotate)
                                .widthIn(min = 54.dp)
                                .background(SettingsFill, CircleShape)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Box(
                modifier = Modifier.size(44.dp).pressable(onClick = onRemove),
                contentAlignment = Alignment.Center,
        ) {
            Box(
                    modifier = Modifier.size(26.dp).background(Red.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
            ) {
                Box(
                        Modifier.size(width = 11.dp, height = 2.dp)
                                .background(Red, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

@Composable
private fun SettingsAddSection(
        name: String,
        url: String,
        error: String?,
        namePlaceholder: String,
        onNameChange: (String) -> Unit,
        onUrlChange: (String) -> Unit,
        onSubmit: () -> Unit,
) {
    val enabled = url.isNotBlank()
    Column {
        SettingsSectionHeader("Add Camera")
        SettingsGroup {
            SettingsInputRow(
                    label = "Name",
                    value = name,
                    placeholder = namePlaceholder,
                    onValueChange = onNameChange,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            SettingsDivider()
            SettingsInputRow(
                    label = "Address",
                    value = url,
                    placeholder = "192.168.144.25:8554/main.264",
                    onValueChange = onUrlChange,
                    keyboardOptions =
                            KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Done,
                            ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            )
        }
        error?.let {
            Text(
                    it,
                    color = Red,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
                modifier =
                        Modifier.fillMaxWidth()
                                .height(44.dp)
                                .pressable(enabled = enabled, onClick = onSubmit)
                                .background(
                                        if (enabled) Green else SettingsCard,
                                        RoundedCornerShape(12.dp),
                                ),
                contentAlignment = Alignment.Center,
        ) {
            Text(
                    "Add Camera",
                    color = if (enabled) Color.Black else SettingsTertiary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SettingsInputRow(
        label: String,
        value: String,
        placeholder: String,
        onValueChange: (String) -> Unit,
        keyboardOptions: KeyboardOptions,
        keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val focusRequester = remember { FocusRequester() }
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .height(46.dp)
                            .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                            ) { focusRequester.requestFocus() }
                            .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.width(72.dp))
        BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Green),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                    placeholder,
                                    color = SettingsTertiary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
        )
    }
}

@Composable
private fun HeaderText(text: String, fontSize: androidx.compose.ui.unit.TextUnit) {
    Text(
            text,
            color = MutedText,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun StatusDot(online: Boolean = true) {
    Box(
            modifier =
                    Modifier.size(7.dp)
                            .background(
                                    color = if (online) Green else Red,
                                    shape = CircleShape,
                            ),
    )
}

@Composable
private fun CameraPanel(
        cameraName: String,
        modifier: Modifier,
        streamUrl: String?,
        rotation: Int = 0,
        floating: Boolean = false,
        videoTransform: VideoTransform = VideoTransform(),
        onClick: () -> Unit,
        onDoubleClick: () -> Unit = {},
        content: @Composable BoxScope.() -> Unit = {},
) {
    var playing by remember(streamUrl) { mutableStateOf(false) }

    val online = streamUrl != null && playing

    Box(
            modifier =
                    modifier.clipToBounds().background(Background).combinedClickable(
                            onClick = onClick,
                            onDoubleClick = onDoubleClick,
                    ),
    ) {
        if (streamUrl != null) {
            key(streamUrl) {
                RtspUdpCameraPreview(
                        streamUrl = streamUrl,
                        rotation = rotation,
                        floating = floating,
                        videoTransform = videoTransform,
                        onPlayingChange = { playing = it },
                )
            }
        } else {
            Text(
                    "NO CAMERA",
                    color = MutedText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center),
            )
        }

        Row(
                modifier = Modifier.align(Alignment.TopStart).padding(11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatusDot(online = online)
            Text(
                    cameraName,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
            )
        }
        content()
    }
}

@Composable
private fun LatencyClock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { now = System.currentTimeMillis() }
        }
    }

    val seconds = (now / 1000) % 100
    val millis = now % 1000

    Text(
            text = String.format(Locale.US, "%02d.%03d", seconds, millis),
            color = Color.Black,
            fontSize = 44.sp,
            fontFamily = FontFamily.Monospace,
            modifier = modifier.background(Color.White).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

private class PreviewCallbacks(
        val onPlaying: () -> Unit,
        val onLatency: (Long) -> Unit,
        val onDisconnected: (String) -> Unit,
)

private interface PreviewPlayer {
    fun start()

    fun stop()
}

@Composable
private fun RtspUdpCameraPreview(
        streamUrl: String,
        rotation: Int = 0,
        floating: Boolean = false,
        videoTransform: VideoTransform = VideoTransform(),
        onPlayingChange: (Boolean) -> Unit = {},
) {
    val endpoint = remember(streamUrl) { parseRtspLowLatencyUrl(streamUrl) } ?: return

    DirectSurfacePreview(
            streamUrl = streamUrl,
            rotation = rotation,
            floating = floating,
            videoTransform = videoTransform,
            connectingLabel = "CONNECTING RTSP/UDP...",
            onPlayingChange = onPlayingChange,
    ) { surface, callbacks ->
        val player =
                RtspUdpPlayer(
                        endpoint = endpoint,
                        surface = surface,
                        cropToSurface = false,
                        listener =
                                object : RtspUdpPlayer.Listener {
                                    override fun onPlaying() = callbacks.onPlaying()

                                    override fun onDecoderLatency(latencyMs: Long) =
                                            callbacks.onLatency(latencyMs)

                                    override fun onDisconnected(message: String) =
                                            callbacks.onDisconnected("RECONNECTING")
                                },
                )
        object : PreviewPlayer {
            override fun start() = player.start()

            override fun stop() = player.stop()
        }
    }
}

@Composable
private fun DirectSurfacePreview(
        streamUrl: String,
        rotation: Int,
        floating: Boolean,
        videoTransform: VideoTransform,
        connectingLabel: String,
        onPlayingChange: (Boolean) -> Unit,
        createPlayer: (Surface, PreviewCallbacks) -> PreviewPlayer,
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val player = remember(streamUrl) { arrayOfNulls<PreviewPlayer>(1) }
    var errorMessage by remember(streamUrl) { mutableStateOf<String?>(null) }
    var isPlaying by remember(streamUrl) { mutableStateOf(false) }
    var decoderLatencyMs by remember(streamUrl) { mutableStateOf<Long?>(null) }

    DisposableEffect(streamUrl) {
        onDispose {
            player[0]?.stop()
            player[0] = null
            onPlayingChange(false)
        }
    }

    BoxWithConstraints(
            modifier = Modifier.fillMaxSize().background(Color.Black).clipToBounds(),
            contentAlignment = Alignment.Center,
    ) {
        fun attach(surface: Surface) {
            val callbacks =
                    PreviewCallbacks(
                            onPlaying = {
                                mainHandler.post {
                                    if (!isPlaying) {
                                        isPlaying = true
                                        errorMessage = null
                                        onPlayingChange(true)
                                    }
                                }
                            },
                            onLatency = { latency ->
                                if (SHOW_DECODE_LATENCY) {
                                    mainHandler.post { decoderLatencyMs = latency }
                                }
                            },
                            onDisconnected = { message ->
                                mainHandler.post {
                                    isPlaying = false
                                    errorMessage = message
                                    onPlayingChange(false)
                                }
                            },
                    )

            val created = createPlayer(surface, callbacks)
            player[0]?.stop()
            player[0] = created
            created.start()
        }

        fun detach() {
            player[0]?.stop()
            player[0] = null
            isPlaying = false
            onPlayingChange(false)
        }

        AndroidView(
                factory = { context ->
                    GlVideoSurfaceView(context).also { view ->
                        view.setFloatingLayer(floating)
                        view.onOutputSurfaceAvailable = ::attach
                        view.onOutputSurfaceDestroyed = ::detach
                    }
                },
                update = { view ->
                    view.setFloatingLayer(floating)
                    view.setVideoTransform(
                            rotationDegrees = rotation,
                            fill = !floating,
                            scale = videoTransform.scale,
                            panX = videoTransform.panX,
                            panY = videoTransform.panY,
                    )
                    if (floating) {
                        val radius = 8f * view.resources.displayMetrics.density
                        view.outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) {
                                outline.setRoundRect(0, 0, view.width, view.height, radius)
                            }
                        }
                        view.clipToOutline = true
                        view.invalidateOutline()
                    } else if (view.clipToOutline) {
                        view.outlineProvider = ViewOutlineProvider.BACKGROUND
                        view.clipToOutline = false
                        view.invalidateOutline()
                    }
                },
                modifier = Modifier.fillMaxSize(),
        )

        if (!isPlaying) {
            Text(
                    text = errorMessage ?: connectingLabel,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center),
            )
        }

        if (isPlaying && SHOW_DECODE_LATENCY) {
            decoderLatencyMs?.let { latency ->
                Text(
                        text = "DEC ${latency}ms",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier =
                                Modifier.align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
