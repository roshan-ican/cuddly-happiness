package com.example.cameraremotecontroller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.cameraremotecontroller.ui.theme.CameraRemoteControllerTheme

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val Background = Color(0xFF111216)
private val HeaderBackground = Color(0xFF090A0D)
private val BorderColor = Color(0xFF2A2D33)
private val GridColor = Color(0xFF24262B)
private val Green = Color(0xFF3EDC81)
private val Red = Color(0xFFF34F55)
private val MutedText = Color(0xFF8296A8)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(
            window,
            window.decorView
        ).apply {
            hide(WindowInsetsCompat.Type.systemBars())

            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            CameraRemoteControllerTheme {
                ControllerDashboard()
            }
        }
    }
}

@Composable
fun ControllerDashboard() {
    var nightMode by rememberSaveable {
        mutableStateOf(false)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Background
    ) {
        BoxWithConstraints {
            val compact = maxWidth < 900.dp || maxWidth < 550.dp

            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                StatusHeader(compact = compact)

                Row(
                    modifier = Modifier.fillMaxSize()
                ) {
                    CameraPanel(
                        cameraName = "CAM 1",
                        modifier = Modifier
                            .weight(1.9f)
                            .fillMaxHeight()
                    ) {
                        MovementControl(
                            compact = compact,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(
                                    start = if (compact) 12.dp else 20.dp,
                                    bottom = if (compact) 10.dp else 18.dp
                                )
                        )

                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(
                                    end = if (compact) 10.dp else 18.dp,
                                    bottom = if (compact) 10.dp else 18.dp
                                ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DayNightToggle(
                                nightMode = nightMode,
                                compact = compact,
                                onModeChanged = { selectedNightMode ->
                                    nightMode = selectedNightMode
                                }
                            )

                            UdpMessageButton(compact)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        CameraPanel(
                            cameraName = "CAM 2 · REAR",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(BorderColor)
                        )

                        CameraPanel(
                            cameraName = "CAM 3 · TOOL",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(compact: Boolean) {
        val headerHeight = if (compact) 40.dp else 48.dp
        val smallText = if (compact) 9.sp else 11.sp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .background(HeaderBackground)
                .border(
                    width = 1.dp,
                    color = BorderColor
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                StatusDot()

                Text(
                    text = "UDP LINKED",
                    color = MutedText,
                    fontSize = smallText,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = "CAMS",
                    color = MutedText,
                    fontSize = smallText,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                repeat(3) {
                    StatusDot()
                }
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "CONTROL RTT: 42 ms",
                    color = Color.White,
                    fontSize = if (compact) 11.sp else 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 7.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Button(
                    onClick = {
                        // E-Stop functionality will be connected later.
                    },
                    modifier = Modifier
                        .width(if (compact) 92.dp else 126.dp)
                        .fillMaxHeight()
                        .padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Red
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(
                        text = "E-STOP",
                        color = Color.White,
                        fontSize = smallText,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusDot() {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    color = Green,
                    shape = CircleShape
                )
        )
    }

    @Composable
    private fun CameraPanel(
        cameraName: String,
        modifier: Modifier,
        content: @Composable BoxScope.() -> Unit = {}
    ) {
        Box(
            modifier = modifier
                .background(Background)
                .border(
                    width = 0.5.dp,
                    color = BorderColor
                )
        ) {
            CameraGrid()

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                StatusDot()

                Text(
                    text = cameraName,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            content()
        }
    }



    @Composable
    private fun CameraGrid() {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val gridSize = 56.dp.toPx()

            var currentX = 0f

            while (currentX <= size.width) {
                drawLine(
                    color = GridColor,
                    start = Offset(currentX, 0f),
                    end = Offset(currentX, size.height),
                    strokeWidth = 1f
                )

                currentX += gridSize
            }

            var currentY = 0f

            while (currentY <= size.height) {
                drawLine(
                    color = GridColor,
                    start = Offset(0f, currentY),
                    end = Offset(size.width, currentY),
                    strokeWidth = 1f
                )

                currentY += gridSize
            }

            // Temporary placeholder showing that video is not connected.
            drawLine(
                color = Color.White.copy(alpha = 0.045f),
                start = Offset(
                    x = size.width * 0.08f,
                    y = size.height * 0.76f
                ),
                end = Offset(
                    x = size.width * 0.92f,
                    y = size.height * 0.25f
                ),
                strokeWidth = size.height * 0.08f
            )
        }
    }



    @Composable
    private fun JoystickPlaceholder(size: androidx.compose.ui.unit.Dp) {
        Canvas(
            modifier = Modifier.size(size)
        ) {
            val centre = Offset(
                x = this.size.width / 2,
                y = this.size.height / 2
            )

            drawCircle(
                color = Color.White.copy(alpha = 0.35f),
                radius = this.size.minDimension / 2.1f,
                center = centre,
                style = Stroke(width = 1.dp.toPx())
            )

            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = this.size.minDimension / 5f,
                center = centre,
                style = Stroke(width = 1.dp.toPx())
            )

            drawLine(
                color = Color.White.copy(alpha = 0.2f),
                start = Offset(centre.x, 0f),
                end = Offset(centre.x, this.size.height),
                strokeWidth = 1.dp.toPx()
            )

            drawLine(
                color = Color.White.copy(alpha = 0.2f),
                start = Offset(0f, centre.y),
                end = Offset(this.size.width, centre.y),
                strokeWidth = 1.dp.toPx()
            )
        }
    }

    @Composable
    private fun DayNightToggle(
        nightMode: Boolean,
        compact: Boolean,
        onModeChanged: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .width(if (compact) 122.dp else 150.dp)
                .height(if (compact) 38.dp else 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = 1.dp,
                    color = BorderColor,
                    shape = RoundedCornerShape(8.dp)
                )
        ) {
            ModeOption(
                text = "DAY",
                selected = !nightMode,
                onClick = {
                    onModeChanged(false)
                }
            )

            ModeOption(
                text = "NIGHT",
                selected = nightMode,
                onClick = {
                    onModeChanged(true)
                }
            )
        }
    }

    @Composable
    private fun RowScope.ModeOption(
        text: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(
                    if (selected) Green.copy(alpha = 0.18f)
                    else Color.Transparent
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (selected) Green else MutedText,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }


@Composable
private fun InteractiveJoystick(
    diameter: Dp,
    onPositionChanged: (x: Float, y: Float) -> Unit
) {
    var joystickOffset by remember {
        mutableStateOf(Offset.Zero)
    }

    var joystickSizePixels by remember {
        mutableStateOf(0f)
    }

    Canvas(
        modifier = Modifier
            .size(diameter)
            .onSizeChanged { size ->
                joystickSizePixels = size.width.toFloat()
            }
            .pointerInput(joystickSizePixels) {
                if (joystickSizePixels <= 0f) {
                    return@pointerInput
                }

                val centre = Offset(
                    x = joystickSizePixels / 2f,
                    y = joystickSizePixels / 2f
                )

                // Maximum distance the inner knob can move.
                val movementRadius = joystickSizePixels * 0.30f

                fun updateJoystick(pointerPosition: Offset) {
                    val requestedOffset = Offset(
                        x = pointerPosition.x - centre.x,
                        y = pointerPosition.y - centre.y
                    )

                    val distance = sqrt(
                        requestedOffset.x * requestedOffset.x +
                                requestedOffset.y * requestedOffset.y
                    )

                    val limitedOffset =
                        if (distance > movementRadius && distance > 0f) {
                            val scale = movementRadius / distance

                            Offset(
                                x = requestedOffset.x * scale,
                                y = requestedOffset.y * scale
                            )
                        } else {
                            requestedOffset
                        }

                    joystickOffset = limitedOffset

                    val normalizedX =
                        (limitedOffset.x / movementRadius)
                            .coerceIn(-1f, 1f)

                    // Negative because screen Y increases downward.
                    val normalizedY =
                        (-limitedOffset.y / movementRadius)
                            .coerceIn(-1f, 1f)

                    onPositionChanged(
                        normalizedX,
                        normalizedY
                    )
                }

                detectDragGestures(
                    onDragStart = { position ->
                        updateJoystick(position)
                    },

                    onDrag = { change, _ ->
                        change.consume()
                        updateJoystick(change.position)
                    },

                    onDragEnd = {
                        joystickOffset = Offset.Zero
                        onPositionChanged(0f, 0f)
                    },

                    onDragCancel = {
                        joystickOffset = Offset.Zero
                        onPositionChanged(0f, 0f)
                    }
                )
            }
    ) {
        val centre = Offset(
            x = size.width / 2f,
            y = size.height / 2f
        )

        val knobCentre = Offset(
            x = centre.x + joystickOffset.x,
            y = centre.y + joystickOffset.y
        )

        // Outer boundary
        drawCircle(
            color = Color.White.copy(alpha = 0.35f),
            radius = size.minDimension / 2.1f,
            center = centre,
            style = Stroke(width = 1.dp.toPx())
        )

        // Horizontal guide
        drawLine(
            color = Color.White.copy(alpha = 0.18f),
            start = Offset(0f, centre.y),
            end = Offset(size.width, centre.y),
            strokeWidth = 1.dp.toPx()
        )

        // Vertical guide
        drawLine(
            color = Color.White.copy(alpha = 0.18f),
            start = Offset(centre.x, 0f),
            end = Offset(centre.x, size.height),
            strokeWidth = 1.dp.toPx()
        )

        // Movable inner knob
        drawCircle(
            color = Color(0xFF181B20),
            radius = size.minDimension / 5f,
            center = knobCentre
        )

        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = size.minDimension / 5f,
            center = knobCentre,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

@Composable
private fun MovementControl(
    compact: Boolean,
    modifier: Modifier = Modifier
) {

    var joystick_x by remember {
        mutableStateOf(0f)
    }

    var joystick_y by remember {
        mutableStateOf(0f)
    }

    val displayedX = (joystick_x * 1000).roundToInt()
    val displayedY = (joystick_y * 1000).roundToInt()

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        InteractiveJoystick(
            diameter = if (compact) 90.dp else 120.dp,
            onPositionChanged = { x, y ->
                joystick_x = x
                joystick_y = y
            }
        )

        Column(
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            Text(
                text = "MOVEMENT",
                color = Color.White,
                fontSize = if (compact) 9.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Text(
                text = "X $displayedX · Y $displayedY",
                color = MutedText,
                fontSize = if (compact) 8.sp else 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

    @Composable
    private fun UdpMessageButton(compact: Boolean) {
        OutlinedButton(
            onClick = {
                // UDP messaging will be connected later.
            },
            modifier = Modifier
                .height(if (compact) 38.dp else 44.dp),
            border = BorderStroke(
                width = 1.dp,
                color = BorderColor
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp)
        ) {
            Text(
                text = "UDP MESSAGE",
                color = Color.White,
                fontSize = if (compact) 8.sp else 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
