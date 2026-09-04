package com.example.cameraremotecontroller

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

// // PC Tunnel URLs (use when PC is bridging the connection)
// private const val CAM1_URL = "rtsp://10.252.176.114:8554/main.264"
// private const val CAM2_URL = "rtsp://10.252.176.114:8555/main.264"

// Real Camera URLs (Uncomment and use when phone is directly on the camera network)
private const val CAM1_URL = "rtsp://192.168.144.25:8554/main.264"
private const val CAM2_URL = "rtsp://192.168.144.26:8554/main.264"

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

private fun normalizeRtspUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val withScheme = if (trimmed.contains("://")) trimmed else "rtsp://$trimmed"
    if (!withScheme.startsWith("rtsp://")) return null

    return try {
        val uri = URI(withScheme)
        if (uri.host.isNullOrBlank()) null else withScheme
    } catch (_: Exception) {
        null
    }
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
            val screenW = maxWidth
            val screenH = maxHeight
            val margin = 12.dp

            val floatW = minOf(screenW, screenH) * 0.32f
            val floatH = minOf(floatW * 16f / 9f, screenH * 0.8f)
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

                    val gestures =
                            if (isFloat) {
                                Modifier.pointerInput(cam.id) {
                                    detectDragGestures(
                                            onDragStart = { dragging = true },
                                            onDragEnd = { settleFloat() },
                                            onDragCancel = { settleFloat() },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                floatX += amount.x.toDp().value
                                                floatY += amount.y.toDp().value
                                            },
                                    )
                                }
                            } else if (canFloat) {
                                Modifier.pointerInput(cam.id, isMain) {
                                    detectDragGesturesAfterLongPress(
                                            onDragStart = { touch ->
                                                floatX =
                                                        bounds.x.value + touch.x.toDp().value -
                                                                floatW.value / 2f
                                                floatY =
                                                        bounds.y.value + touch.y.toDp().value -
                                                                floatH.value / 2f
                                                stowedLeft = null
                                                floatWasMain = isMain
                                                if (isMain) {
                                                    docked.firstOrNull()?.let { primaryId = it.id }
                                                }

                                                floatingId = cam.id
                                                dragging = true
                                            },
                                            onDragEnd = { settleFloat() },
                                            onDragCancel = { settleFloat() },
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
                            onClick = {
                                when {
                                    isFloat -> dock()
                                    !isMain -> primaryId = cam.id
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
                            label = { Text("RTSP URL OR IP", fontSize = 11.sp) },
                            placeholder = {
                                Text("192.168.144.27:8554/main.264", fontSize = 11.sp)
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
                            val normalized = normalizeRtspUrl(url)

                            if (normalized == null) {
                                error = "ENTER A VALID RTSP ADDRESS"
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

private object Vlc {
    private val options =
            arrayListOf(
                    "--no-audio",
                    "--network-caching=0",
                    "--avcodec-threads=1",
                    "--clock-synchro=-1",
                    "--clock-jitter=0",
                    "--drop-late-frames",
                    "--skip-frames",
                    // UDP over TCP: a torn but current frame beats a clean but stale one.
                    "--no-rtsp-tcp",
            )

    @Volatile private var instance: LibVLC? = null

    fun get(context: Context): LibVLC =
            instance
                    ?: synchronized(this) {
                        instance
                                ?: LibVLC(context.applicationContext, options).also {
                                    instance = it
                                }
                    }
}

private const val PROBE_INTERVAL_MS = 5_000L

private const val PROBE_FAILURES_BEFORE_OFFLINE = 3

private const val RECONNECT_BACKOFF_MS = 1_500L

private const val PROBE_SOCKET_TIMEOUT_MS = 1_000

private data class RtspProbe(val latencyMs: Long?, val reachable: Boolean)

private class RtspPinger(private val streamUrl: String, private val address: InetSocketAddress) {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var cseq = 0

    // Two passes so a server-side idle timeout costs a reconnect, not a false offline.
    fun ping(): Long? {
        repeat(2) {
            try {
                if (socket == null) open()
                return exchange()
            } catch (_: Exception) {
                close()
            }
        }
        return null
    }

    private fun open() {
        val fresh = Socket()
        fresh.tcpNoDelay = true
        fresh.soTimeout = PROBE_SOCKET_TIMEOUT_MS
        fresh.connect(address, PROBE_SOCKET_TIMEOUT_MS)

        socket = fresh
        reader = fresh.getInputStream().bufferedReader(Charsets.US_ASCII)
        writer = fresh.getOutputStream().bufferedWriter(Charsets.US_ASCII)
    }

    private fun exchange(): Long {
        val out = writer ?: throw IOException("no writer")
        val input = reader ?: throw IOException("no reader")

        cseq++

        val startedAt = System.nanoTime()

        out.write("OPTIONS $streamUrl RTSP/1.0\r\n")
        out.write("CSeq: $cseq\r\n")
        out.write("User-Agent: CameraRemoteController\r\n\r\n")
        out.flush()

        val status = input.readLine() ?: throw IOException("closed")
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000

        if (!status.startsWith("RTSP/")) throw IOException("unexpected: $status")

        while (true) {
            val line = input.readLine() ?: throw IOException("closed")
            if (line.isEmpty()) break
        }

        return elapsed
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }

        socket = null
        reader = null
        writer = null
    }
}

@Composable
private fun rememberRtspProbe(streamUrl: String?): RtspProbe {
    var probe by remember(streamUrl) { mutableStateOf(RtspProbe(null, streamUrl != null)) }

    LaunchedEffect(streamUrl) {
        if (streamUrl == null) {
            probe = RtspProbe(null, false)
            return@LaunchedEffect
        }

        val uri = URI(streamUrl)
        val port = if (uri.port >= 0) uri.port else 554
        val socketAddress =
                withContext(Dispatchers.IO) {
                    try {
                        InetSocketAddress(uri.host, port)
                    } catch (_: Exception) {
                        null
                    }
                }

        if (socketAddress == null) {
            probe = RtspProbe(null, false)
            return@LaunchedEffect
        }

        val pinger = RtspPinger(streamUrl, socketAddress)
        var consecutiveFailures = 0

        try {
            while (isActive) {
                val latency = withContext(Dispatchers.IO) { pinger.ping() }

                if (latency == null) consecutiveFailures++ else consecutiveFailures = 0

                probe =
                        RtspProbe(
                                latencyMs = latency,
                                reachable = consecutiveFailures < PROBE_FAILURES_BEFORE_OFFLINE,
                        )

                delay(PROBE_INTERVAL_MS)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { pinger.close() }
        }
    }

    return probe
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
        onClick: () -> Unit,
        content: @Composable BoxScope.() -> Unit = {},
) {
    val probe = rememberRtspProbe(streamUrl)

    var playing by remember(streamUrl) { mutableStateOf(false) }

    val online = streamUrl != null && (playing || probe.reachable)

    Box(modifier = modifier.background(Background).clickable(onClick = onClick)) {
        if (streamUrl != null) {
            // Rebuild on the 0/non-0 boundary: SurfaceView and TextureView are chosen at attach.
            key(streamUrl, rotation != 0) {
                RtspCameraPreview(
                        streamUrl = streamUrl,
                        rotation = rotation,
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

        if (streamUrl != null && !playing && !probe.reachable) {
            Box(
                    modifier = Modifier.fillMaxSize().background(Background),
                    contentAlignment = Alignment.Center,
            ) {
                Text(
                        "CAMERA OFFLINE",
                        color = Red,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                )
            }
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
            probe.latencyMs?.let { latency ->
                Text(
                        "· ${latency}ms",
                        color = MutedText,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                )
            }
        }
        content()
    }
}

@Composable
private fun RtspCameraPreview(
        streamUrl: String,
        rotation: Int = 0,
        onPlayingChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val libVlc = remember { Vlc.get(context) }
    val mediaPlayer = remember(streamUrl) { MediaPlayer(libVlc) }

    var errorMessage by remember(streamUrl) { mutableStateOf<String?>(null) }
    var isPlaying by remember(streamUrl) { mutableStateOf(false) }

    var attempt by remember(streamUrl) { mutableStateOf(0) }

    var sourceAspect by remember(streamUrl) { mutableStateOf(16f / 9f) }

    LaunchedEffect(isPlaying) { onPlayingChange(isPlaying) }

    DisposableEffect(streamUrl) {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing ->
                        mainHandler.post {
                            isPlaying = true
                            errorMessage = null
                        }
                MediaPlayer.Event.Vout ->
                        mainHandler.post {
                            val track = mediaPlayer.currentVideoTrack

                            if (track != null && track.width > 0 && track.height > 0) {
                                sourceAspect = track.width.toFloat() / track.height.toFloat()
                            }
                            mediaPlayer.setVideoScale(MediaPlayer.ScaleType.SURFACE_FILL)
                            mediaPlayer.updateVideoSurfaces()
                        }
                MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached ->
                        mainHandler.post {
                            isPlaying = false
                            errorMessage = "RECONNECTING..."
                            attempt++
                        }
            }
        }

        onDispose {
            mediaPlayer.setEventListener(null)
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
        }
    }

    LaunchedEffect(streamUrl, attempt) {
        if (attempt > 0) delay(RECONNECT_BACKOFF_MS)

        val media =
                Media(libVlc, Uri.parse(streamUrl)).apply {
                    setHWDecoderEnabled(true, false)
                    addOption(":avcodec-skiploopfilter=4")
                    addOption(":avcodec-skip-frame=0")
                    addOption(":avcodec-fast")
                    addOption(":mediacodec-dr=1")
                    addOption(":network-caching=0")
                    addOption(":avcodec-threads=1")
                    addOption(":clock-synchro=-1")
                    addOption(":clock-jitter=0")
                    addOption(":no-audio")
                    addOption(":no-rtsp-tcp")
                }

        mediaPlayer.media = media
        media.release()

        mediaPlayer.play()
    }

    BoxWithConstraints(
            modifier = Modifier.fillMaxSize().background(Color.Black).clipToBounds(),
            contentAlignment = Alignment.Center,
    ) {
        val swapped = rotation == 90 || rotation == 270

        val displayAspect = if (swapped) 1f / sourceAspect else sourceAspect
        val panelAspect = maxWidth / maxHeight

        val wider = displayAspect > panelAspect
        val screenWidth: Dp = if (wider) maxHeight * displayAspect else maxWidth
        val screenHeight: Dp = if (wider) maxHeight else maxWidth / displayAspect

        val videoModifier =
                Modifier.requiredWidth(if (swapped) screenHeight else screenWidth)
                        .requiredHeight(if (swapped) screenWidth else screenHeight)
                        .graphicsLayer { rotationZ = rotation.toFloat() }

        AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                        mediaPlayer.attachViews(layout, null, false, rotation != 0)
                    }
                },
                modifier =
                        videoModifier.onSizeChanged { size ->
                            if (size.width > 0 && size.height > 0) {
                                mainHandler.post { mediaPlayer.updateVideoSurfaces() }
                            }
                        },
        )

        if (!isPlaying && errorMessage == null) {
            Text(
                    text = "CONNECTING...",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center),
            )
        }

        errorMessage?.let { message ->
            Text(
                    text = message,
                    color = Red,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier =
                            Modifier.align(Alignment.Center)
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .padding(4.dp),
            )
        }
    }
}
