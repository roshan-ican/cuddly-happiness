package com.example.cameraremotecontroller

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val MOVED_HIGHLIGHT_MS = 700L
private const val MOVED_THRESHOLD = 12
private val Amber = Color(0xFFFFB020)

@Composable
internal fun RcMappingDialog(
    state: SiyiRcChannelState,
    onApplyProfile: () -> SiyiRcMapApplyResult,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirmApply by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    val lastValues = remember { IntArray(RC_CHANNEL_COUNT) }
    val movedAt = remember { LongArray(RC_CHANNEL_COUNT) }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }

    LaunchedEffect(state.channels) {
        val time = SystemClock.uptimeMillis()
        state.channels.forEachIndexed { index, value ->
            if (lastValues[index] != 0 && value != 0 && abs(value - lastValues[index]) > MOVED_THRESHOLD) movedAt[index] = time
            lastValues[index] = value
        }
    }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.uptimeMillis(); kotlinx.coroutines.delay(100) } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(SettingsSurface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Rover + Weapon Station Inputs", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(when { state.connected -> "Live SIYI channels · ${state.packetCount} packets"; state.error != null -> state.error; else -> "Connecting to SIYI controller…" }, color = SettingsSecondary, fontSize = 12.sp)
                }
                Text("Done", color = Green, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.pressable(onClick = onDismiss).padding(10.dp))
            }
            resultText?.let { Text(it, color = if (it.startsWith("Applied")) Green else Amber, fontSize = 12.sp) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.weight(1f)) {
                items(ROVER_WS_PROFILE) { profile ->
                    RcProfileRow(profile, state.channels.getOrElse(profile.channel - 1) { 0 }, now - movedAt[profile.channel - 1] < MOVED_HIGHLIGHT_MS)
                }
            }
            Text(if (applying) "Applying controller configuration…" else "Apply Rover / WS Map", color = if (applying) SettingsSecondary else Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(if (applying) SettingsCard else Green).pressable(enabled = !applying) { confirmApply = true }.padding(top = 13.dp))
        }
        if (confirmApply) ConfirmationOverlay(onCancel = { confirmApply = false }, onConfirm = {
            confirmApply = false
            applying = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { onApplyProfile() }
                applying = false
                resultText = if (result.verified) "Applied and verified all 16 controller channels." else result.error ?: "Controller mapping failed."
            }
        })
    }
}

@Composable
private fun RcProfileRow(profile: RcProfileChannel, value: Int, moving: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Row(modifier = Modifier.fillMaxWidth().clip(shape).background(SettingsCard).border(1.dp, if (moving) Amber else Color.White.copy(alpha = 0.1f), shape).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${profile.channel}".padStart(2, '0'), color = SettingsSecondary, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.width(24.dp))
        Text(profile.control.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(34.dp))
        Text(profile.function, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        when (profile.displayKind) {
            RcProfileDisplayKind.PROPORTIONAL -> ProportionalValue(value, moving)
            RcProfileDisplayKind.SWITCH -> SwitchValue(value, moving, profile.positionLabels)
            RcProfileDisplayKind.BUTTON -> ButtonValue(value, moving)
        }
    }
}

@Composable
private fun ProportionalValue(value: Int, moving: Boolean) {
    val normalized = if (value == 0) 0f else ((value - 1050) / 900f).coerceIn(0f, 1f)
    Column(modifier = Modifier.width(126.dp)) {
        Text(if (value == 0) "—" else value.toString(), color = if (moving) Amber else Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(SettingsSeparator)) { Box(Modifier.fillMaxWidth(normalized).height(5.dp).background(if (moving) Amber else Green)) }
    }
}

@Composable
private fun SwitchValue(value: Int, moving: Boolean, positionLabels: List<String>) {
    val selected = when { value == 0 -> -1; value < 1250 -> 0; value > 1750 -> 2; else -> 1 }
    Row(modifier = Modifier.width(126.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        positionLabels.forEachIndexed { index, label ->
            Text(label, color = if (selected == index) Color.Black else SettingsSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).clip(RoundedCornerShape(5.dp)).background(if (selected == index) if (moving) Amber else Green else SettingsSeparator).padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun ButtonValue(value: Int, moving: Boolean) {
    val pressed = value >= 1750
    Text(if (pressed) "PRESSED" else "RELEASED", color = if (pressed) Color.Black else SettingsSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(126.dp).clip(RoundedCornerShape(6.dp)).background(if (pressed) if (moving) Amber else Green else SettingsSeparator).padding(vertical = 5.dp))
}

@Composable
private fun ConfirmationOverlay(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)).background(SettingsSurface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Apply Rover / WS Map?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("This replaces all 16 controller assignments, then reads them back to verify the result.", color = SettingsSecondary, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", color = SettingsSecondary, modifier = Modifier.weight(1f).pressable(onClick = onCancel).padding(12.dp), textAlign = TextAlign.Center)
                Text("Apply", color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Green).pressable(onClick = onConfirm).padding(12.dp), textAlign = TextAlign.Center)
            }
        }
    }
}
