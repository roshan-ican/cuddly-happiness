package com.example.cameraremotecontroller

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.cameraremotecontroller.ui.theme.CameraRemoteControllerTheme
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

private const val CAM1_URL = "rtsp://192.168.144.25:8554/main.264"
private const val CAM2_URL = "rtsp://192.168.144.26:8554/main.264"

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
    var primaryCamera by remember { mutableStateOf(1) }
    val cam1RtspMs = rememberRtspResponseTime(CAM1_URL)
    val cam2RtspMs = rememberRtspResponseTime(CAM2_URL)
    val primaryName = if (primaryCamera == 1) "CAM 1" else "CAM 2 · REAR"
    val primaryUrl = if (primaryCamera == 1) CAM1_URL else CAM2_URL
    val secondaryName = if (primaryCamera == 1) "CAM 2 · REAR" else "CAM 1"
    val secondaryUrl = if (primaryCamera == 1) CAM2_URL else CAM1_URL

    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        BoxWithConstraints {
            val compact = maxWidth < 900.dp || maxWidth < 550.dp
            Column(modifier = Modifier.fillMaxSize()) {
                StatusHeader(compact, cam1RtspMs, cam2RtspMs)
                Row(modifier = Modifier.fillMaxSize()) {
                    CameraPanel(
                            cameraName = primaryName,
                            modifier = Modifier.weight(1.9f).fillMaxHeight(),
                            streamUrl = primaryUrl,
                            onClick = {},
                    ) {
                        MovementControl(
                                compact = compact,
                                modifier =
                                        Modifier.align(Alignment.BottomStart)
                                                .padding(
                                                        start = if (compact) 12.dp else 20.dp,
                                                        bottom = if (compact) 10.dp else 18.dp,
                                                ),
                        )
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        CameraPanel(
                                cameraName = secondaryName,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                streamUrl = secondaryUrl,
                                onClick = { primaryCamera = if (primaryCamera == 1) 2 else 1 },
                        )
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
                        CameraPanel(
                                cameraName = "CAM 3 · TOOL",
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                streamUrl = null,
                                onClick = {},
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberRtspResponseTime(streamUrl: String): Long? {
    var responseMs by remember(streamUrl) { mutableStateOf<Long?>(null) }

    LaunchedEffect(streamUrl) {
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
private fun StatusHeader(compact: Boolean, cam1ConnectMs: Long?, cam2ConnectMs: Long?) {
    val smallText = if (compact) 9.sp else 11.sp
    val cam1Text = cam1ConnectMs?.let { "$it ms" } ?: "--"
    val cam2Text = cam2ConnectMs?.let { "$it ms" } ?: "--"

    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .height(if (compact) 40.dp else 48.dp)
                            .background(HeaderBackground)
                            .border(1.dp, BorderColor),
            verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatusDot(online = false)
            HeaderText("UDP NOT LINKED", smallText)
            Spacer(modifier = Modifier.weight(1f))
            HeaderText("CAMS", smallText)
            StatusDot(online = cam1ConnectMs != null)
            StatusDot(online = cam2ConnectMs != null)
            StatusDot(online = false)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                    text = "RTSP · C1 $cam1Text · C2 $cam2Text",
                    color = Color.White,
                    fontSize = if (compact) 9.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
            )
        }
        Box(
                modifier = Modifier.weight(1f).padding(end = 7.dp),
                contentAlignment = Alignment.CenterEnd,
        ) {
            Button(
                    onClick = { /* E-Stop transport will be connected later. */},
                    modifier =
                            Modifier.width(if (compact) 92.dp else 126.dp)
                                    .fillMaxHeight()
                                    .padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                        "E-STOP",
                        color = Color.White,
                        fontSize = smallText,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                )
            }
        }
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
            Modifier.size(7.dp).background(
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
        onClick: () -> Unit,
        content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
            modifier =
                    modifier.background(Background)
                            .border(0.5.dp, BorderColor)
                            .clickable(onClick = onClick)
    ) {
        if (streamUrl != null) {
            // Give each stream its own VLC lifecycle when cameras swap positions.
            key(streamUrl) { RtspCameraPreview(streamUrl = streamUrl) }
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
            StatusDot()
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
private fun RtspCameraPreview(streamUrl: String) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var errorMessage by remember(streamUrl) { mutableStateOf<String?>(null) }
    val libVlc = remember {
        LibVLC(
                context,
                arrayListOf(
                        "--rtsp-tcp",
                        "--avcodec-hw=none",
                        "--network-caching=100",
                        "--no-audio",
                ),
        )
    }
    val mediaPlayer = remember { MediaPlayer(libVlc) }

    DisposableEffect(streamUrl) {
        mediaPlayer.setEventListener { event ->
            if (event.type == MediaPlayer.Event.EncounteredError) {
                mainHandler.post { errorMessage = "RTSP STREAM ERROR" }
            }
        }

        val media =
                Media(libVlc, Uri.parse(streamUrl)).apply {
                    setHWDecoderEnabled(false, false)
                    addOption(":rtsp-tcp")
                    addOption(":network-caching=100")
                    addOption(":no-audio")
                }
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()

        onDispose {
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
            libVlc.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                        mediaPlayer.attachViews(layout, null, false, false)
                    }
                },
                modifier = Modifier.fillMaxSize(),
        )
        errorMessage?.let {
            Text(
                    it,
                    color = Red,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun MovementControl(compact: Boolean, modifier: Modifier = Modifier) {
    Text(
            "MOVEMENT",
            modifier = modifier,
            color = Color.White,
            fontSize = if (compact) 9.sp else 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
    )
}
