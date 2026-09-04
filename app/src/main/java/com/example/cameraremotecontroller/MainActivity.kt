package com.example.cameraremotecontroller

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.cameraremotecontroller.ui.theme.CameraRemoteControllerTheme
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
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

/** One camera feed. [url] is null for an empty placeholder slot. */
private data class CameraSource(val id: Int, val name: String, val url: String?)

private val DEFAULT_CAMERAS =
        listOf(
                CameraSource(1, "CAM 1", CAM1_URL),
                CameraSource(2, "CAM 2", CAM2_URL),
        )

private const val PREFS_NAME = "camera_remote_controller"
private const val PREFS_KEY_CAMERAS = "cameras"

/*
 * Cameras are persisted as "name|url" records separated by newlines, so a
 * camera added on the tablet survives a restart. Anything unparseable is
 * skipped rather than crashing the dashboard.
 */
private fun SharedPreferences.loadCameras(): List<CameraSource> {
    val raw = getString(PREFS_KEY_CAMERAS, null) ?: return DEFAULT_CAMERAS

    val stored =
            raw.lines().mapNotNull { line ->
                val name = line.substringBefore('|', "").trim()
                val url = line.substringAfter('|', "").trim()

                if (name.isEmpty() || url.isEmpty()) null else name to url
            }

    if (stored.isEmpty()) return DEFAULT_CAMERAS

    return stored.mapIndexed { index, (name, url) -> CameraSource(index + 1, name, url) }
}

private fun SharedPreferences.saveCameras(cameras: List<CameraSource>) {
    val raw = cameras.filter { it.url != null }.joinToString("\n") { "${it.name}|${it.url}" }

    edit().putString(PREFS_KEY_CAMERAS, raw).apply()
}

/** Accepts rtsp://host[:port][/path]; the host is the part users get wrong. */
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { CameraRemoteControllerTheme { ControllerDashboard() } }
    }
}

@Composable
fun ControllerDashboard() {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    val cameras = remember {
        mutableStateListOf<CameraSource>().apply { addAll(prefs.loadCameras()) }
    }

    var primaryId by remember { mutableStateOf(cameras.firstOrNull()?.id ?: 0) }
    var showSettings by remember { mutableStateOf(false) }

    val primary = cameras.firstOrNull { it.id == primaryId } ?: cameras.firstOrNull()
    val secondaries = cameras.filter { it.id != primary?.id }

    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (isLandscape) {

                // ========================================
                // LANDSCAPE
                // Primary LEFT + secondaries stacked RIGHT
                // ========================================

                Row(modifier = Modifier.fillMaxSize()) {
                    CameraPanel(
                            cameraName = primary?.name ?: "NO CAMERA",
                            modifier = Modifier.weight(1.9f).fillMaxHeight(),
                            streamUrl = primary?.url,
                            rotation = 90,
                            onClick = {},
                    )

                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (secondaries.isEmpty()) {
                            CameraPanel(
                                    cameraName = "CAM 2",
                                    modifier = Modifier.fillMaxSize(),
                                    streamUrl = null,
                                    onClick = {},
                            )
                        } else {
                            secondaries.forEachIndexed { index, camera ->
                                if (index > 0) {
                                    Box(
                                            modifier =
                                                    Modifier.fillMaxWidth()
                                                            .height(1.dp)
                                                            .background(BorderColor)
                                    )
                                }

                                DraggableCameraPanel(
                                        camera = camera,
                                        isLandscape = true,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        onClick = { primaryId = camera.id },
                                )
                            }
                        }
                    }
                }
            } else {

                // ========================================
                // PORTRAIT
                // Primary TOP + secondaries in a row below
                // ========================================

                Column(modifier = Modifier.fillMaxSize()) {
                    CameraPanel(
                            cameraName = primary?.name ?: "NO CAMERA",
                            modifier = Modifier.weight(1.5f).fillMaxWidth(),
                            streamUrl = primary?.url,
                            rotation = 90,
                            onClick = {},
                    )

                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (secondaries.isEmpty()) {
                            CameraPanel(
                                    cameraName = "CAM 2",
                                    modifier = Modifier.fillMaxSize(),
                                    streamUrl = null,
                                    onClick = {},
                            )
                        } else {
                            secondaries.forEachIndexed { index, camera ->
                                if (index > 0) {
                                    Box(
                                            modifier =
                                                    Modifier.fillMaxHeight()
                                                            .width(1.dp)
                                                            .background(BorderColor)
                                    )
                                }

                                DraggableCameraPanel(
                                        camera = camera,
                                        isLandscape = false,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                        onClick = { primaryId = camera.id },
                                )
                            }
                        }
                    }
                }
            }

            SettingsButton(
                    modifier = Modifier.align(Alignment.TopEnd).padding(11.dp),
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

                    // Removing the feed on screen promotes whatever is left.
                    if (primaryId == camera.id) primaryId = cameras.firstOrNull()?.id ?: 0
                },
        )
    }
}

/**
 * A secondary feed. Long-press and drag repositions it; a plain tap still
 * promotes it into the primary panel.
 */
@Composable
private fun DraggableCameraPanel(
        camera: CameraSource,
        isLandscape: Boolean,
        modifier: Modifier,
        onClick: () -> Unit,
) {
    var offsetX by remember(camera.id) { mutableStateOf(0f) }
    var offsetY by remember(camera.id) { mutableStateOf(0f) }
    var dragging by remember(camera.id) { mutableStateOf(false) }

    /*
     * Snap back to the slot on a flip, so a panel dragged in landscape does
     * not reappear displaced in portrait. This is an effect rather than a
     * remember() key on purpose: keying the state on the orientation also
     * tore down the gesture detector below during the rotation
     * recomposition, which left the panel undraggable in portrait.
     */
    LaunchedEffect(isLandscape) {
        offsetX = 0f
        offsetY = 0f
        dragging = false
    }

    CameraPanel(
            cameraName = camera.name,
            modifier =
                    modifier.offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                            // A dragged panel floats above its neighbours.
                            .zIndex(if (dragging) 1f else 0f)
                            .pointerInput(camera.id) {
                                detectDragGesturesAfterLongPress(
                                        onDragStart = { dragging = true },
                                        onDragEnd = { dragging = false },
                                        onDragCancel = { dragging = false },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            offsetX += dragAmount.x
                                            offsetY += dragAmount.y
                                        },
                                )
                            },
            streamUrl = camera.url,
            rotation = 90,
            onClick = onClick,
    )
}

@Composable
private fun SettingsButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
            modifier =
                    modifier.size(28.dp)
                            .background(color = HeaderBackground, shape = CircleShape)
                            .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
    ) {
        Text("⚙", color = MutedText, fontSize = 14.sp)
    }
}

@Composable
private fun CameraSettingsDialog(
        cameras: List<CameraSource>,
        onDismiss: () -> Unit,
        onAdd: (String, String) -> Unit,
        onRemove: (CameraSource) -> Unit,
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
                                Text(
                                        camera.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                        camera.url ?: "-",
                                        color = MutedText,
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
                ) {
                    Text("ADD CAMERA")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } },
    )
}

@Composable
private fun rememberRtspResponseTime(streamUrl: String?): Long? {
    var responseMs by remember(streamUrl) { mutableStateOf<Long?>(null) }

    LaunchedEffect(streamUrl) {
        if (streamUrl == null) {
            responseMs = null
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
            responseMs = null
            return@LaunchedEffect
        }

        while (isActive) {
            responseMs =
                    withContext(Dispatchers.IO) {
                        measureRtspResponseTime(streamUrl, socketAddress)
                    }
            delay(3_000)
        }
    }

    return responseMs
}

private fun measureRtspResponseTime(streamUrl: String, socketAddress: InetSocketAddress): Long? {
    return try {
        val startedAt = System.nanoTime()
        Socket().use { socket ->
            socket.soTimeout = 1_000
            socket.connect(socketAddress, 1_000)
            socket.getOutputStream().bufferedWriter(Charsets.US_ASCII).apply {
                write("OPTIONS $streamUrl RTSP/1.0\r\n")
                write("CSeq: 1\r\n")
                write("User-Agent: CameraRemoteController\r\n\r\n")
                flush()
            }

            // The first response byte proves that the RTSP server answered.
            if (socket.getInputStream().read() < 0) return null
        }
        (System.nanoTime() - startedAt) / 1_000_000
    } catch (_: Exception) {
        null
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
        onClick: () -> Unit,
        content: @Composable BoxScope.() -> Unit = {},
) {
    val latencyMs = rememberRtspResponseTime(streamUrl)

    // The RTSP probe below polls every few seconds, so it is the liveness signal.
    val online = streamUrl != null && latencyMs != null

    /*
     * An unreachable camera keeps its slot rather than collapsing the grid,
     * but VLC will not recover a stream that died under it. Bumping this
     * generation rebuilds the player once the probe sees the camera again.
     * wasOnline starts true so the very first successful probe does not
     * pointlessly restart a player that is already running.
     */
    var streamGeneration by remember(streamUrl) { mutableStateOf(0) }
    var wasOnline by remember(streamUrl) { mutableStateOf(true) }

    LaunchedEffect(online) {
        if (online && !wasOnline) streamGeneration++
        wasOnline = online
    }

    Box(modifier = modifier.background(Background).clickable(onClick = onClick)) {
        if (streamUrl != null) {
            // Give each stream its own VLC lifecycle when cameras swap positions.
            key(streamUrl, streamGeneration) {
                RtspCameraPreview(streamUrl = streamUrl, rotation = rotation)
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

        if (streamUrl != null && !online) {
            // Fully opaque: a translucent cover let the player's own "RTSP STREAM ERROR"
            // text show through faintly behind "OFFLINE", showing two duelling messages.
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
            if (latencyMs != null) {
                Text(
                        "· ${latencyMs}ms",
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
private fun RtspCameraPreview(streamUrl: String, rotation: Int = 0) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var errorMessage by remember(streamUrl) { mutableStateOf<String?>(null) }

    var isPlaying by remember(streamUrl) { mutableStateOf(false) }

    /*
     * Displayed aspect ratio (width / height) of the decoded video,
     * already accounting for the transform filter's rotation.
     * Null until VLC reports the first frame.
     */
    var videoAspect by
            remember(streamUrl, rotation) {
                /*
                 * Seeded with the cameras' native 16:9 (rotated when the
                 * transform filter is on) so there is never a frame where
                 * the size is unknown -- an unknown size used to fall back
                 * to VLC's own letterboxed scaling, and which camera lost
                 * that race varied run to run. Corrected below if a stream
                 * ever reports something else.
                 */
                mutableStateOf(if (rotation == 90 || rotation == 270) 9f / 16f else 16f / 9f)
            }

    /*
     * IMPORTANT:
     * Rotate inside VLC.
     *
     * Do NOT rotate VLCVideoLayout with Compose graphicsLayer.
     */
    val libVlc =
            remember(streamUrl, rotation) {
                val options =
                        arrayListOf(
                                "--rtsp-tcp",
                                "--avcodec-hw=none",
                                "--network-caching=300",
                                "--no-audio"
                        )

                if (rotation == 90 || rotation == 180 || rotation == 270) {
                    options.add("--video-filter=transform")
                    options.add("--transform-type=$rotation")
                }

                LibVLC(context, options)
            }

    val mediaPlayer = remember(streamUrl, rotation) { MediaPlayer(libVlc) }

    DisposableEffect(streamUrl, rotation) {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    mainHandler.post { isPlaying = true }
                }
                MediaPlayer.Event.Vout -> {

                    /*
                     * Recalculate the video surface after VLC
                     * knows the real stream dimensions.
                     */
                    mainHandler.post {
                        val track = mediaPlayer.currentVideoTrack

                        if (track != null && track.width > 0 && track.height > 0) {
                            val sourceAspect = track.width.toFloat() / track.height.toFloat()

                            videoAspect =
                                    if (rotation == 90 || rotation == 270) 1f / sourceAspect
                                    else sourceAspect
                        }


                        /*
                         * SURFACE_FILL stretches the video to the layout.
                         * That is safe here because we give the layout the
                         * correct aspect ratio ourselves (see videoAspect
                         * below), so nothing is actually distorted.
                         */
                        mediaPlayer.setVideoScale(MediaPlayer.ScaleType.SURFACE_FILL)

                        mediaPlayer.updateVideoSurfaces()
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    mainHandler.post { errorMessage = "RTSP STREAM ERROR" }
                }
                MediaPlayer.Event.Buffering -> {
                    if (event.buffering >= 100f) {
                        mainHandler.post { isPlaying = true }
                    }
                }
            }
        }

        val media =
                Media(libVlc, Uri.parse(streamUrl)).apply {
                    setHWDecoderEnabled(false, false)

                    addOption(":rtsp-tcp")
                    addOption(":network-caching=300")
                    addOption(":no-audio")
                }

        mediaPlayer.media = media
        media.release()

        /*
         * THIS is what makes the video behave like
         * ContentScale.Crop.
         *
         * It preserves aspect ratio and lets VLC crop
         * the excess to cover the panel.
         */
        mediaPlayer.setVideoScale(MediaPlayer.ScaleType.SURFACE_FIT_SCREEN)

        mediaPlayer.play()

        onDispose {
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
            libVlc.release()
        }
    }

    BoxWithConstraints(
            modifier = Modifier.fillMaxSize().background(Color.Black).clipToBounds(),
            contentAlignment = Alignment.Center
    ) {
        /*
         * Cover, not contain: scale the video layout up until it fills
         * the panel on both axes and let clipToBounds() crop the excess.
         * Letting VLC scale it only ever produced letterboxing, because
         * the rotated 720x1280 frame does not match the panel shape.
         */
        val panelAspect = maxWidth / maxHeight

        /*
         * requiredWidth/requiredHeight, NOT width/height: the plain
         * modifiers are coerced by the parent's constraints, which clamped
         * the overflowing axis back to the panel and defeated the whole
         * point of covering. The required* variants ignore the incoming
         * constraints, and clipToBounds() above crops the excess.
         */
        val videoModifier =
                if (videoAspect > panelAspect) {
                    // Video is wider than the panel: match height, overflow width.
                    Modifier.requiredHeight(maxHeight).requiredWidth(maxHeight * videoAspect)
                } else {
                    // Video is taller than the panel: match width, overflow height.
                    Modifier.requiredWidth(maxWidth).requiredHeight(maxWidth / videoAspect)
                }

        AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->

                        /*
                         * false = no subtitles
                         * false = SurfaceView instead of TextureView
                         */
                        mediaPlayer.attachViews(layout, null, false, false)
                    }
                },
                /*
                 * The layout changes size on rotation and whenever the
                 * cover calculation above changes. VLC does not notice on
                 * its own, so push the new bounds into it.
                 */
                modifier =
                        videoModifier.onSizeChanged { size ->
                            if (size.width > 0 && size.height > 0) {
                                mainHandler.post { mediaPlayer.updateVideoSurfaces() }
                            }
                        }
        )

        if (!isPlaying && errorMessage == null) {
            Text(
                    text = "CONNECTING...",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center)
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
                                    .padding(4.dp)
            )
        }
    }
}
