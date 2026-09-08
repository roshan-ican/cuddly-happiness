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
import android.graphics.SurfaceTexture
import android.graphics.Outline
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.cameraremotecontroller.ui.theme.CameraRemoteControllerTheme
import java.util.Locale

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

    var floatingId by remember { mutableStateOf<Int?>(null) }
    var stowedLeft by remember { mutableStateOf<Boolean?>(null) }
    var floatX by remember { mutableStateOf(0f) }
    var floatY by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var floatWasMain by remember { mutableStateOf(false) }

    val floating = cameras.firstOrNull { it.id == floatingId }
    val split = cameras.filter { it.id != floating?.id }
    val main = split.firstOrNull { it.id == primaryId } ?: split.firstOrNull()
    val docked = split.filter { it.id != main?.id }

    LaunchedEffect(cameras.size) {
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

            val floatW = minOf(screenW, screenH) * 0.32f
            // Match the pane to the rotated video so the fitted surface leaves no bars.
            val floatUpright = floating?.rotation?.let { it == 90 || it == 270 } ?: true
            val floatH =
                    minOf(
                            if (floatUpright) floatW * 16f / 9f else floatW * 9f / 16f,
                            screenH * 0.8f,
                    )
            val canFloat = cameras.size >= 2
            val showPlaceholder = cameras.size < 2
            val splitCount = if (showPlaceholder) 1 else docked.size

            val mainBounds =
                    when {
                        splitCount == 0 -> PaneBounds(0.dp, 0.dp, screenW, screenH)
                        isLandscape -> PaneBounds(0.dp, 0.dp, screenW * (1.9f / 2.9f), screenH)
                        else -> PaneBounds(0.dp, 0.dp, screenW, screenH * (1.5f / 2.5f))
                    }

            fun dockedBounds(index: Int): PaneBounds {
                val slot = index.coerceAtLeast(0)
                return if (isLandscape) {
                    val h = screenH / splitCount
                    PaneBounds(mainBounds.w, h * slot, screenW - mainBounds.w, h)
                } else {
                    val w = screenW / splitCount
                    PaneBounds(w * slot, mainBounds.h, w, screenH - mainBounds.h)
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

            cameras.forEach { cam ->
                key(cam.id) {
                    val isFloat = cam.id == floatingId
                    val isMain = !isFloat && cam.id == main?.id

                    val bounds =
                            when {
                                isFloat -> floatBounds
                                isMain -> mainBounds
                                else -> dockedBounds(docked.indexOfFirst { it.id == cam.id })
                            }

                    fun minimize() {
                        floatWasMain = isMain
                        if (isMain) docked.firstOrNull()?.let { primaryId = it.id }
                        floatingId = cam.id
                        stowedLeft = null
                        floatX = screenW.value - floatW.value - margin.value
                        floatY = margin.value
                    }

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
                    val gestures = if (canFloat) {
                        Modifier.pointerInput(cam.id) {
                            detectDragGestures(
                                onDragStart = { startDrag(it) },
                                onDragEnd = { finishDrag() },
                                onDragCancel = { finishDrag() },
                                onDrag = { change, amount ->
                                    change.consume()
                                    floatX += amount.x.toDp().value
                                    floatY += amount.y.toDp().value
                                },
                            )
                        }
                    } else Modifier

                    val floatSkin =
                            if (isFloat)
                                    Modifier.clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                            else Modifier

                    CameraPanel(
                            cameraName = cam.name,
                            modifier =
                                    paneModifier(bounds, animate = !(isFloat && dragging))
                                            .zIndex(if (isFloat) 2f else 0f)
                                            .then(floatSkin)
                                            .then(gestures),
                            streamUrl = cam.url,
                            rotation = cam.rotation,
                            floating = isFloat,
                            onClick = {
                                when {
                                    isFloat -> dock()
                                    canFloat -> minimize()
                                }
                            },
                    )
                }
            }

            if (showPlaceholder) {
                CameraPanel(
                        cameraName = "CAM 2",
                        modifier = paneModifier(dockedBounds(0), animate = true),
                        streamUrl = null,
                        onClick = {},
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
        }
    }

    if (showSettings) {
        CameraSettingsDialog(
                cameras = cameras,
                onDismiss = { showSettings = false },
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
        onDismiss: () -> Unit,
        onAdd: (String, String) -> Unit,
        onRemove: (CameraSource) -> Unit,
        onRename: (CameraSource, String) -> Unit,
        onSetRotation: (CameraSource, Int) -> Unit,
        onSetUrl: (CameraSource, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                        "CAMERAS",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cameras.forEach { camera ->
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                OutlinedTextField(
                                        value = camera.name,
                                        onValueChange = { onRename(camera, it) },
                                        singleLine = true,
                                        textStyle =
                                                androidx.compose.ui.text.TextStyle(
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.Monospace,
                                                ),
                                        modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                        camera.url ?: "-",
                                        color = MutedText,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                )
                            }

                            TextButton(
                                    onClick = {
                                        onSetRotation(camera, (camera.rotation + 90) % 360)
                                    },
                            ) {
                                Text(
                                        "${camera.rotation}°",
                                        color = if (camera.rotation == 0) Green else MutedText,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                )
                            }

                            TextButton(onClick = { onRemove(camera) }) {
                                Text("REMOVE", color = Red, fontSize = 10.sp)
                            }
                        }
                    }

                    OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("NAME", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("CAMERA ADDRESS", fontSize = 11.sp) },
                            placeholder = {
                                Text("192.168.144.25:8554/main.264", fontSize = 11.sp)
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                    )

                    error?.let {
                        Text(it, color = Red, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(
                        onClick = {
                            val normalized = normalizeCameraUrl(url)

                            if (normalized == null) {
                                error = "ENTER A VALID RTSP OR SIYI ADDRESS"
                            } else {
                                val label = name.trim().ifEmpty { "CAM ${cameras.size + 1}" }

                                onAdd(label, normalized)
                                name = ""
                                url = ""
                                error = null
                            }
                        }
                ) { Text("ADD CAMERA") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } },
    )
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
        onClick: () -> Unit,
        content: @Composable BoxScope.() -> Unit = {},
) {
    var playing by remember(streamUrl) { mutableStateOf(false) }

    val online = streamUrl != null && playing

    Box(modifier = modifier.clipToBounds().background(Background).clickable(onClick = onClick)) {
        if (streamUrl != null) {
            key(streamUrl, rotation != 0) {
                RtspUdpCameraPreview(
                        streamUrl = streamUrl,
                        rotation = rotation,
                        floating = floating,
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
        onPlayingChange: (Boolean) -> Unit = {},
) {
    val endpoint = remember(streamUrl) { parseRtspLowLatencyUrl(streamUrl) } ?: return

    DirectSurfacePreview(
            streamUrl = streamUrl,
            rotation = rotation,
            floating = floating,
            connectingLabel = "CONNECTING RTSP/UDP...",
            onPlayingChange = onPlayingChange,
    ) { surface, callbacks ->
        val player =
                RtspUdpPlayer(
                        endpoint = endpoint,
                        surface = surface,
                        cropToSurface = rotation == 0,
                        listener =
                                object : RtspUdpPlayer.Listener {
                                    override fun onPlaying() = callbacks.onPlaying()

                                    override fun onDecoderLatency(latencyMs: Long) =
                                            callbacks.onLatency(latencyMs)

                                    override fun onDisconnected(message: String) =
                                            callbacks.onDisconnected("RTSP/UDP RECONNECTING")
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
        val sourceAspect = 16f / 9f
        val swapped = rotation == 90 || rotation == 270
        val displayAspect = if (swapped) 1f / sourceAspect else sourceAspect
        val panelAspect = maxWidth / maxHeight
        // TextureView participates in pane clipping and stacking while moving or resizing.
        // Fit the floating preview; fill the larger panes with the existing crop.
        val cover = if (floating) displayAspect < panelAspect else displayAspect > panelAspect
        val screenWidth: Dp = if (cover) maxHeight * displayAspect else maxWidth
        val screenHeight: Dp = if (cover) maxHeight else maxWidth / displayAspect
        val videoModifier =
                Modifier.requiredWidth(if (swapped) screenHeight else screenWidth)
                        .requiredHeight(if (swapped) screenWidth else screenHeight)
                        .graphicsLayer { rotationZ = rotation.toFloat() }

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

        if (rotation == 0) {
            // Keep the native surface exactly inside the pane. Let the decoder crop
            // its image instead of enlarging a separate compositor layer beyond it.
            AndroidView(
                    factory = { context ->
                        SurfaceView(context).also { view ->
                            view.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) = attach(holder.surface)
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                                override fun surfaceDestroyed(holder: SurfaceHolder) = detach()
                            })
                        }
                    },
                    update = { view ->
                        // Compose zIndex alone cannot order native video surfaces.
                        if (floating) view.setZOrderMediaOverlay(true)
                        else view.setZOrderOnTop(false)
                        val radius = if (floating) 8f * view.resources.displayMetrics.density else 0f
                        view.outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) {
                                outline.setRoundRect(0, 0, view.width, view.height, radius)
                            }
                        }
                        view.clipToOutline = true
                        view.invalidateOutline()
                    },
                    modifier = Modifier.fillMaxSize(),
            )
        } else {
            AndroidView(
                    factory = { context ->
                        TextureView(context).also { view ->
                            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                private var outputSurface: Surface? = null

                                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                    outputSurface = Surface(texture).also { attach(it) }
                                }

                                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                    detach()
                                    outputSurface?.release()
                                    outputSurface = null
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                            }
                        }
                    },
                    modifier = videoModifier,
            )
        }

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
