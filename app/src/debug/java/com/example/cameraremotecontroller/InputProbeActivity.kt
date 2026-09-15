package com.example.cameraremotecontroller

import android.hardware.input.InputManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class InputProbeActivity :
    ComponentActivity(),
    InputManager.InputDeviceListener {
    private val log = mutableStateListOf<String>()
    private val devices = mutableStateListOf<String>()
    private val seen = mutableStateMapOf<String, String>()
    private val axisRanges = HashMap<String, Pair<Float, Float>>()
    private val keyCounts = HashMap<String, Int>()
    private val lastAxis = HashMap<Long, Float>()
    private var channels by mutableStateOf(List(16) { 0 })
    private var channelMovedAt by mutableStateOf(List(16) { 0L })
    private var rcStatus by mutableStateOf("RC: waiting")
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private lateinit var inputManager: InputManager
    private val rcClient = SiyiRcChannelClient { state -> onRcState(state) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        inputManager = getSystemService(InputManager::class.java)
        enumerateDevices()
        setContent { ProbeScreen() }
    }

    override fun onStart() {
        super.onStart()
        inputManager.registerInputDeviceListener(this, null)
        if (intent.getBooleanExtra("raw", false)) startRawSniffer() else rcClient.start()
    }

    override fun onStop() {
        rawRunning = false
        rcClient.stop()
        inputManager.unregisterInputDeviceListener(this)
        super.onStop()
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        append("DEVICE ADDED id=$deviceId")
        enumerateDevices()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        append("DEVICE REMOVED id=$deviceId")
        enumerateDevices()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        append("DEVICE CHANGED id=$deviceId")
        enumerateDevices()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action =
            when (event.action) {
                KeyEvent.ACTION_DOWN -> "DOWN"
                KeyEvent.ACTION_UP -> "UP"
                else -> "ACTION${event.action}"
            }
        val name = KeyEvent.keyCodeToString(event.keyCode)
        val device = event.device?.name ?: "id=${event.deviceId}"
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val count = (keyCounts[name] ?: 0) + 1
            keyCounts[name] = count
            seen["KEY $name"] = "x$count  scan=${event.scanCode}  [$device]"
        }
        if (event.repeatCount == 0) {
            append(
                "KEY $action $name code=${event.keyCode} scan=${event.scanCode} " +
                    "src=${sourcesToString(event.source)} [$device]",
            )
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val device = event.device
        val axes = device?.motionRanges?.map { it.axis }?.distinct() ?: (0..47).toList()
        val deviceName = device?.name ?: "id=${event.deviceId}"
        val changes = StringBuilder()
        for (axis in axes) {
            val value = event.getAxisValue(axis)
            val key = (event.deviceId.toLong() shl 32) or (event.source.toLong() shl 8) or axis.toLong()
            val previous = lastAxis[key]
            if (previous != null && abs(previous - value) < AXIS_EPSILON) continue
            if (previous == null && value == 0f) {
                lastAxis[key] = value
                continue
            }
            lastAxis[key] = value
            val axisName = MotionEvent.axisToString(axis)
            changes.append(' ').append(axisName).append('=').append("%.3f".format(Locale.US, value))
            val rangeKey = "AXIS $axisName [$deviceName]"
            val range = axisRanges[rangeKey]
            val updated =
                if (range == null) value to value else minOf(range.first, value) to maxOf(range.second, value)
            axisRanges[rangeKey] = updated
            seen[rangeKey] = "%.3f .. %.3f".format(Locale.US, updated.first, updated.second)
        }
        if (changes.isNotEmpty()) {
            append("MOTION src=${sourcesToString(event.source)} [$deviceName]$changes")
        }
        return if (event.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK)) true else super.dispatchGenericMotionEvent(event)
    }

    @Volatile private var rawRunning = false

    private fun startRawSniffer() {
        rawRunning = true
        Thread {
            val remote = java.net.InetSocketAddress("192.168.144.20", 19856)
            java.net.DatagramSocket(0).use { udp ->
                udp.soTimeout = 1000
                val pollMs = intent.getIntExtra("pollMs", 0)
                val enable = buildSiyiRcChannelRequest(intent.getIntExtra("freq", 5))
                udp.send(java.net.DatagramPacket(enable, enable.size, remote))
                if (pollMs > 0) udp.soTimeout = pollMs
                val buffer = ByteArray(512)
                var last: ByteArray? = null
                var total = 0
                var distinct = 0
                var windowStart = SystemClock.uptimeMillis()
                var lastPoll = SystemClock.uptimeMillis()
                while (rawRunning) {
                    if (pollMs > 0 && SystemClock.uptimeMillis() - lastPoll >= pollMs) {
                        udp.send(java.net.DatagramPacket(enable, enable.size, remote))
                        lastPoll = SystemClock.uptimeMillis()
                    }
                    try {
                        val packet = java.net.DatagramPacket(buffer, buffer.size)
                        udp.receive(packet)
                        val bytes = packet.data.copyOf(packet.length)
                        total++
                        val payload = if (bytes.size >= 40) bytes.copyOfRange(8, 40) else bytes
                        if (last == null || !payload.contentEquals(last)) {
                            distinct++
                            Log.i(TAG, "RAW len=${bytes.size} from=${packet.address.hostAddress}:${packet.port} ${bytes.joinToString("") { "%02x".format(it) }}")
                            last = payload
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        if (pollMs == 0) {
                            Log.i(TAG, "RAW timeout")
                            udp.send(java.net.DatagramPacket(enable, enable.size, remote))
                        }
                    }
                    val now = SystemClock.uptimeMillis()
                    if (now - windowStart >= 2000) {
                        Log.i(TAG, "RAW window packets=$total distinct=$distinct")
                        total = 0
                        distinct = 0
                        windowStart = now
                    }
                }
                val disable = buildSiyiRcChannelRequest(0)
                udp.send(java.net.DatagramPacket(disable, disable.size, remote))
            }
        }.start()
    }

    private fun onRcState(state: SiyiRcChannelState) {
        rcStatus =
            when {
                state.connected -> "RC: connected, packets=${state.packetCount}"
                else -> "RC: ${state.error ?: "waiting"} (force-stop biz.siyi.remotecontrol?)"
            }
        if (!state.connected) return
        val now = SystemClock.uptimeMillis()
        val moved = channelMovedAt.toMutableList()
        state.channels.forEachIndexed { index, value ->
            val old = channels[index]
            if (old != 0 && abs(old - value) > CHANNEL_EPSILON) {
                moved[index] = now
                append("RC CH${index + 1} $old -> $value")
            }
        }
        channelMovedAt = moved
        channels = state.channels
    }

    private fun enumerateDevices() {
        devices.clear()
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            val builder = StringBuilder()
            builder
                .append("id=")
                .append(id)
                .append(" \"")
                .append(device.name)
                .append("\" vendor=0x")
                .append(Integer.toHexString(device.vendorId))
                .append(" product=0x")
                .append(Integer.toHexString(device.productId))
                .append(" external=")
                .append(device.isExternal)
                .append("\n  sources=0x")
                .append(Integer.toHexString(device.sources))
                .append(' ')
                .append(sourcesToString(device.sources))
            for (range in device.motionRanges) {
                builder
                    .append("\n  ")
                    .append(MotionEvent.axisToString(range.axis))
                    .append(" src=")
                    .append(sourcesToString(range.source))
                    .append(" [")
                    .append("%.2f".format(Locale.US, range.min))
                    .append(", ")
                    .append("%.2f".format(Locale.US, range.max))
                    .append("] flat=")
                    .append("%.3f".format(Locale.US, range.flat))
            }
            val text = builder.toString()
            devices.add(text)
            Log.i(TAG, "DEVICE $text")
        }
    }

    private fun append(line: String) {
        val stamped = "${timeFormat.format(Date())} $line"
        Log.i(TAG, stamped)
        log.add(0, stamped)
        if (log.size > MAX_LOG_LINES) log.removeRange(MAX_LOG_LINES, log.size)
    }

    private fun clear() {
        log.clear()
        seen.clear()
        axisRanges.clear()
        keyCounts.clear()
        lastAxis.clear()
    }

    @Composable
    private fun ProbeScreen() {
        val mono = FontFamily.Monospace
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { clear() }) { Text("Clear") }
                    Button(onClick = { enumerateDevices() }) { Text("Rescan") }
                }
                Text(rcStatus, color = Color.White, fontFamily = mono, fontSize = 11.sp)
                ChannelGrid()
                Text("SEEN (${seen.size})", color = Color.Yellow, fontFamily = mono, fontSize = 12.sp)
                seen.entries.sortedBy { it.key }.forEach { (key, value) ->
                    Text("$key  $value", color = Color.White, fontFamily = mono, fontSize = 11.sp)
                }
                Text("DEVICES (${devices.size})", color = Color.Yellow, fontFamily = mono, fontSize = 12.sp)
                devices.forEach { Text(it, color = Color.LightGray, fontFamily = mono, fontSize = 10.sp) }
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxHeight()) {
                items(log) { Text(it, color = Color.Green, fontFamily = mono, fontSize = 10.sp) }
            }
        }
    }

    @Composable
    private fun ChannelGrid() {
        val now = SystemClock.uptimeMillis()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (row in 0 until 4) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (col in 0 until 4) {
                        val index = row * 4 + col
                        val hot = now - channelMovedAt[index] < CHANNEL_HIGHLIGHT_MS
                        Text(
                            "CH%-2d %4d".format(Locale.US, index + 1, channels[index]),
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .background(if (hot) Color(0xFF7A5A00) else Color(0xFF1A1A1A))
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "InputProbe"
        const val MAX_LOG_LINES = 500
        const val AXIS_EPSILON = 0.01f
        const val CHANNEL_EPSILON = 8
        const val CHANNEL_HIGHLIGHT_MS = 600L

        val SOURCE_NAMES =
            listOf(
                InputDevice.SOURCE_KEYBOARD to "KEYBOARD",
                InputDevice.SOURCE_DPAD to "DPAD",
                InputDevice.SOURCE_GAMEPAD to "GAMEPAD",
                InputDevice.SOURCE_TOUCHSCREEN to "TOUCHSCREEN",
                InputDevice.SOURCE_MOUSE to "MOUSE",
                InputDevice.SOURCE_STYLUS to "STYLUS",
                InputDevice.SOURCE_TRACKBALL to "TRACKBALL",
                InputDevice.SOURCE_TOUCHPAD to "TOUCHPAD",
                InputDevice.SOURCE_TOUCH_NAVIGATION to "TOUCH_NAV",
                InputDevice.SOURCE_ROTARY_ENCODER to "ROTARY",
                InputDevice.SOURCE_JOYSTICK to "JOYSTICK",
                InputDevice.SOURCE_HDMI to "HDMI",
                InputDevice.SOURCE_SENSOR to "SENSOR",
            )

        fun sourcesToString(sources: Int): String =
            SOURCE_NAMES
                .filter { (mask, _) -> sources and mask == mask }
                .joinToString("|") { it.second }
                .ifEmpty { "UNKNOWN" }
    }
}
