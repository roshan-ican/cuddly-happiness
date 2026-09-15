package com.example.cameraremotecontroller

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlin.math.abs

private val Amber = Color(0xFFFFB020)
private const val MOVED_HIGHLIGHT_MS = 700L
private const val MOVED_THRESHOLD = 12

private val NODE_W = 36.dp
private val NODE_H = 24.dp
private val CHANNEL_W = 168.dp
private val COLUMN_GAP = 40.dp
private val HEADER_H = 56.dp
private val EDGE = 16.dp

@Composable
internal fun RcMappingDialog(
    state: SiyiRcChannelState,
    mapping: List<RcControl?>,
    onMappingChange: (List<RcControl?>) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) {
            dialogWindow?.let { window ->
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }

        var selected by remember { mutableStateOf<RcControl?>(null) }
        var pickerChannel by remember { mutableStateOf<Int?>(null) }
        val lastValues = remember { IntArray(RC_CHANNEL_COUNT) }
        val movedAt = remember { LongArray(RC_CHANNEL_COUNT) }
        var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }

        LaunchedEffect(state.channels) {
            val time = SystemClock.uptimeMillis()
            state.channels.forEachIndexed { index, value ->
                val previous = lastValues[index]
                if (previous != 0 && value != 0 && abs(value - previous) > MOVED_THRESHOLD) movedAt[index] = time
                lastValues[index] = value
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                now = SystemClock.uptimeMillis()
                delay(100)
            }
        }

        val moving = BooleanArray(RC_CHANNEL_COUNT) { now - movedAt[it] < MOVED_HIGHLIGHT_MS }

        fun onChannelTap(channel: Int) {
            val control = selected
            if (control == null) {
                pickerChannel = channel
            } else {
                onMappingChange(mapping.assign(channel, if (mapping[channel] == control) null else control))
                selected = null
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SettingsSurface),
        ) {
            MappingCanvas(
                state = state,
                mapping = mapping,
                moving = moving,
                selected = selected,
                onControlTap = { control -> selected = if (selected == control) null else control },
                onChannelTap = ::onChannelTap,
            )
            MappingHeader(
                state = state,
                selected = selected,
                onReset = {
                    selected = null
                    onMappingChange(DEFAULT_RC_CHANNEL_MAP)
                },
                onDismiss = onDismiss,
            )
            pickerChannel?.let { channel ->
                ControlPicker(
                    channel = channel,
                    current = mapping[channel],
                    mapping = mapping,
                    onPick = { control ->
                        onMappingChange(mapping.assign(channel, control))
                        pickerChannel = null
                    },
                    onDismiss = { pickerChannel = null },
                )
            }
        }
    }
}

@Composable
private fun MappingHeader(
    state: SiyiRcChannelState,
    selected: RcControl?,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(HEADER_H)
                .padding(horizontal = EDGE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("RC Input Test", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    selected != null -> "${selected.label} selected — tap the channel it is on (move it to see which one lights up)"
                    state.connected -> "Connected · ${state.packetCount} packets · tap a channel to assign, or tap a control"
                    state.error != null -> state.error
                    else -> "Connecting to 192.168.144.20:19856…"
                },
                color = if (selected != null) Green else SettingsSecondary,
                fontSize = 11.sp,
            )
        }
        Text(
            "Reset",
            color = SettingsSecondary,
            fontSize = 14.sp,
            modifier = Modifier.pressable(onClick = onReset).padding(10.dp),
        )
        Text(
            "Done",
            color = Green,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.pressable(onClick = onDismiss).padding(10.dp),
        )
    }
}

@Composable
private fun MappingCanvas(
    state: SiyiRcChannelState,
    mapping: List<RcControl?>,
    moving: BooleanArray,
    selected: RcControl?,
    onControlTap: (RcControl) -> Unit,
    onChannelTap: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        val areaTop = HEADER_H
        val areaHeight = height - areaTop - EDGE
        val rowGap = 6.dp
        val channelH = (areaHeight - rowGap * 7) / 8
        val centerLeft = EDGE + CHANNEL_W + COLUMN_GAP
        val centerWidth = width - (EDGE + CHANNEL_W + COLUMN_GAP) * 2
        val handsetTop = areaTop + 44.dp
        val handsetHeight = areaHeight * 0.52f
        val chipTop = handsetTop + handsetHeight + 20.dp
        val extras = RcControl.entries.filter { !it.onDiagram }
        val extrasPerRow = ((centerWidth + 6.dp) / (NODE_W + 6.dp)).toInt().coerceAtLeast(1)

        fun channelOrigin(channel: Int): Pair<Dp, Dp> {
            val left = channel % 2 == 0
            val row = channel / 2
            val x = if (left) EDGE else width - EDGE - CHANNEL_W
            return x to areaTop + (channelH + rowGap) * row
        }

        fun channelAnchor(channel: Int): Pair<Dp, Dp> {
            val (x, y) = channelOrigin(channel)
            val left = channel % 2 == 0
            return (if (left) x + CHANNEL_W else x) to y + channelH / 2
        }

        fun controlAnchor(control: RcControl): Pair<Dp, Dp> {
            if (control.onDiagram) {
                return centerLeft + centerWidth * control.diagramX!! to handsetTop + handsetHeight * control.diagramY!!
            }
            val index = extras.indexOf(control)
            val row = index / extrasPerRow
            val col = index % extrasPerRow
            val rowWidth = (NODE_W + 6.dp) * minOf(extrasPerRow, extras.size - row * extrasPerRow) - 6.dp
            val startX = centerLeft + (centerWidth - rowWidth) / 2
            return startX + (NODE_W + 6.dp) * col + NODE_W / 2 to chipTop + (NODE_H + 8.dp) * row + NODE_H / 2
        }

        val channels = state.channels
        Canvas(modifier = Modifier.fillMaxSize()) {
            val handsetRectTop = handsetTop.toPx()
            val handsetRectLeft = centerLeft.toPx()
            drawRoundRect(
                color = Color.White.copy(alpha = 0.04f),
                topLeft = Offset(handsetRectLeft, handsetRectTop),
                size = Size(centerWidth.toPx(), handsetHeight.toPx()),
                cornerRadius = CornerRadius(22.dp.toPx()),
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.3f),
                topLeft = Offset(handsetRectLeft, handsetRectTop),
                size = Size(centerWidth.toPx(), handsetHeight.toPx()),
                cornerRadius = CornerRadius(22.dp.toPx()),
                style = Stroke(1.5.dp.toPx()),
            )
            val stickRadius = minOf(centerWidth.toPx() * 0.085f, handsetHeight.toPx() * 0.2f)
            listOf(
                Triple(0.2f, RcControl.J4, RcControl.J3),
                Triple(0.8f, RcControl.J1, RcControl.J2),
            ).forEach { (fx, xControl, yControl) ->
                val center = Offset(handsetRectLeft + centerWidth.toPx() * fx, handsetRectTop + handsetHeight.toPx() * 0.58f)
                drawCircle(Color.Black.copy(alpha = 0.4f), stickRadius, center)
                drawCircle(Color.White.copy(alpha = 0.35f), stickRadius, center, style = Stroke(1.5.dp.toPx()))
                val xValue = mapping.channelValue(xControl, channels)
                val yValue = mapping.channelValue(yControl, channels)
                val dx = if (xValue == 0) 0f else ((xValue - 1500) / 450f).coerceIn(-1f, 1f)
                val dy = if (yValue == 0) 0f else -((yValue - 1500) / 450f).coerceIn(-1f, 1f)
                drawCircle(
                    if (xValue == 0 && yValue == 0) SettingsSecondary else Green,
                    stickRadius * 0.28f,
                    Offset(center.x + dx * stickRadius * 0.6f, center.y + dy * stickRadius * 0.6f),
                )
            }

            mapping.forEachIndexed { channel, control ->
                if (control == null) return@forEachIndexed
                val (cx, cy) = controlAnchor(control)
                val (tx, ty) = channelAnchor(channel)
                val highlight = control == selected
                val color =
                    when {
                        highlight -> Green
                        moving[channel] -> Amber
                        else -> Color.White.copy(alpha = 0.32f)
                    }
                drawLine(
                    color,
                    Offset(cx.toPx(), cy.toPx()),
                    Offset(tx.toPx(), ty.toPx()),
                    (if (highlight || moving[channel]) 2.5.dp else 1.2.dp).toPx(),
                )
                drawCircle(color, 3.dp.toPx(), Offset(tx.toPx(), ty.toPx()))
            }
        }

        Text(
            "MAIN L",
            color = SettingsSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .offset(x = centerLeft + centerWidth * 0.2f - 30.dp, y = handsetTop + handsetHeight * 0.72f)
                    .width(60.dp),
        )
        Text(
            "MAIN R",
            color = SettingsSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .offset(x = centerLeft + centerWidth * 0.8f - 30.dp, y = handsetTop + handsetHeight * 0.72f)
                    .width(60.dp),
        )

        RcControl.entries.forEach { control ->
            val (cx, cy) = controlAnchor(control)
            val channel = mapping.indexOf(control)
            ControlNode(
                control = control,
                mapped = channel >= 0,
                moving = channel >= 0 && moving[channel],
                selected = control == selected,
                modifier = Modifier.offset(x = cx - NODE_W / 2, y = cy - NODE_H / 2),
                onClick = { onControlTap(control) },
            )
        }

        repeat(RC_CHANNEL_COUNT) { channel ->
            val (x, y) = channelOrigin(channel)
            ChannelBox(
                channel = channel,
                value = channels.getOrElse(channel) { 0 },
                control = mapping[channel],
                moving = moving[channel],
                selectedMatch = selected != null && mapping[channel] == selected,
                alignEnd = channel % 2 == 1,
                modifier =
                    Modifier
                        .offset(x = x, y = y)
                        .size(CHANNEL_W, channelH),
                onClick = { onChannelTap(channel) },
            )
        }
    }
}

@Composable
private fun ControlNode(
    control: RcControl,
    mapped: Boolean,
    moving: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier =
            modifier
                .size(NODE_W, NODE_H)
                .clip(shape)
                .background(if (selected) Green else SettingsCard)
                .border(
                    1.dp,
                    when {
                        selected -> Green
                        moving -> Amber
                        mapped -> Color.White.copy(alpha = 0.4f)
                        else -> Color.Transparent
                    },
                    shape,
                ).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            control.label,
            color =
                when {
                    selected -> Color.Black
                    mapped -> Color.White
                    else -> SettingsSecondary
                },
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ChannelBox(
    channel: Int,
    value: Int,
    control: RcControl?,
    moving: Boolean,
    selectedMatch: Boolean,
    alignEnd: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val normalized = if (value == 0) 0f else ((value - 1000) / 1000f).coerceIn(0f, 1f)
    Column(
        modifier =
            modifier
                .clip(shape)
                .background(SettingsCard)
                .border(
                    if (selectedMatch || moving) 1.5.dp else 0.dp,
                    when {
                        selectedMatch -> Green
                        moving -> Amber
                        else -> Color.Transparent
                    },
                    shape,
                ).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (alignEnd) ControlChip(control)
            Text(
                "CH${channel + 1}",
                color = SettingsSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (value == 0) "—" else value.toString(),
                color = if (value == 0) SettingsSecondary else Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (!alignEnd) ControlChip(control)
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SettingsSeparator),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(normalized)
                    .fillMaxHeight()
                    .background(if (moving) Amber else Green),
            )
        }
    }
}

@Composable
private fun ControlChip(control: RcControl?) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier =
            Modifier
                .widthIn(min = 36.dp)
                .border(1.dp, Color.White.copy(alpha = if (control == null) 0.2f else 0.6f), shape)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            control?.label ?: "--",
            color = if (control == null) SettingsSecondary else Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ControlPicker(
    channel: Int,
    current: RcControl?,
    mapping: List<RcControl?>,
    onPick: (RcControl?) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            modifier =
                Modifier
                    .widthIn(max = 560.dp)
                    .clip(shape)
                    .background(SettingsSurface)
                    .border(0.5.dp, Color.White.copy(alpha = 0.12f), shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "CH${channel + 1} — pick the control assigned in UniGCS",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val options: List<RcControl?> = RcControl.entries.sortedBy { PICKER_ORDER.indexOf(it) } + listOf(null)
            options.chunked(10).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { control ->
                        val active = control == current
                        val usedElsewhere = control != null && !active && mapping.contains(control)
                        Box(
                            modifier =
                                Modifier
                                    .size(width = 44.dp, height = 32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (active) Green else SettingsCard)
                                    .pressable(onClick = { onPick(control) }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                control?.label ?: "--",
                                color =
                                    when {
                                        active -> Color.Black
                                        usedElsewhere -> SettingsSecondary
                                        else -> Color.White
                                    },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            Text(
                "Grey = already on another channel; picking it moves it here.",
                color = SettingsSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

private val PICKER_ORDER =
    listOf(
        RcControl.J1, RcControl.J2, RcControl.J3, RcControl.J4, RcControl.J5, RcControl.J6, RcControl.J7, RcControl.J8,
        RcControl.LK, RcControl.RK, RcControl.LD, RcControl.RD, RcControl.SA, RcControl.SB, RcControl.SC, RcControl.SD,
        RcControl.SE, RcControl.SF, RcControl.S1, RcControl.S2, RcControl.S3, RcControl.S4, RcControl.L1, RcControl.L2,
        RcControl.L3, RcControl.R1, RcControl.R2, RcControl.R3, RcControl.M1, RcControl.LS, RcControl.RS, RcControl.FLIGHT,
    )
