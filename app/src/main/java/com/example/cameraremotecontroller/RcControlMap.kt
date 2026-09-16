package com.example.cameraremotecontroller

import android.content.SharedPreferences

internal const val RC_CHANNEL_COUNT = 16

private const val PREFS_KEY_RC_CHANNEL_MAP = "rc_channel_map"

internal enum class RcControlKind { STICK, DIAL, SWITCH, BUTTON }

internal enum class RcSide { LEFT, RIGHT }

internal enum class RcControl(
    val label: String,
    val kind: RcControlKind,
    val side: RcSide,
    val diagramX: Float? = null,
    val diagramY: Float? = null,
) {
    J1("J1", RcControlKind.STICK, RcSide.RIGHT, 0.73f, 0.9f),
    J2("J2", RcControlKind.STICK, RcSide.RIGHT, 0.87f, 0.9f),
    J3("J3", RcControlKind.STICK, RcSide.LEFT, 0.27f, 0.9f),
    J4("J4", RcControlKind.STICK, RcSide.LEFT, 0.13f, 0.9f),
    LS("LS", RcControlKind.DIAL, RcSide.LEFT, 0.06f, -0.2f),
    RS("RS", RcControlKind.DIAL, RcSide.RIGHT, 0.94f, -0.2f),
    SA("SA", RcControlKind.SWITCH, RcSide.LEFT, 0.35f, 0.32f),
    SB("SB", RcControlKind.SWITCH, RcSide.RIGHT, 0.65f, 0.32f),
    SE("SE", RcControlKind.SWITCH, RcSide.LEFT, 0.07f, 0.32f),
    LK("LK", RcControlKind.DIAL, RcSide.LEFT, 0.2f, 0.32f),
    LD("LD", RcControlKind.DIAL, RcSide.LEFT, 0.35f, 0.13f),
    SC("SC", RcControlKind.SWITCH, RcSide.RIGHT, 0.93f, 0.13f),
    SD("SD", RcControlKind.SWITCH, RcSide.RIGHT, 0.83f, 0.13f),
    SF("SF", RcControlKind.SWITCH, RcSide.RIGHT, 0.93f, 0.32f),
    RK("RK", RcControlKind.DIAL, RcSide.RIGHT, 0.8f, 0.32f),
    RD("RD", RcControlKind.DIAL, RcSide.RIGHT, 0.65f, 0.13f),
    FLIGHT("Fl", RcControlKind.SWITCH, RcSide.LEFT),
    S1("S1", RcControlKind.BUTTON, RcSide.LEFT),
    S2("S2", RcControlKind.BUTTON, RcSide.LEFT),
    S3("S3", RcControlKind.BUTTON, RcSide.RIGHT),
    S4("S4", RcControlKind.BUTTON, RcSide.RIGHT),
    L1("L1", RcControlKind.BUTTON, RcSide.LEFT),
    L2("L2", RcControlKind.BUTTON, RcSide.LEFT),
    L3("L3", RcControlKind.BUTTON, RcSide.LEFT),
    R1("R1", RcControlKind.BUTTON, RcSide.RIGHT),
    R2("R2", RcControlKind.BUTTON, RcSide.RIGHT),
    R3("R3", RcControlKind.BUTTON, RcSide.RIGHT),
    M1("M1", RcControlKind.BUTTON, RcSide.RIGHT),
    J5("J5", RcControlKind.STICK, RcSide.RIGHT),
    J6("J6", RcControlKind.STICK, RcSide.RIGHT),
    J7("J7", RcControlKind.STICK, RcSide.LEFT),
    J8("J8", RcControlKind.STICK, RcSide.LEFT),
    ;

    val onDiagram: Boolean get() = diagramX != null && diagramY != null
}

internal val DEFAULT_RC_CHANNEL_MAP: List<RcControl?> =
    listOf(
        RcControl.J1,
        RcControl.J2,
        RcControl.J3,
        RcControl.J4,
        RcControl.FLIGHT,
        RcControl.SA,
        RcControl.SB,
        RcControl.SC,
        RcControl.RD,
        RcControl.SE,
        RcControl.SF,
        RcControl.S1,
        RcControl.S2,
        RcControl.S4,
        RcControl.LK,
        RcControl.RK,
    )

internal enum class RcProfileDisplayKind { PROPORTIONAL, SWITCH, BUTTON }

internal data class RcProfileChannel(
    val channel: Int,
    val control: RcControl,
    val function: String,
    val displayKind: RcProfileDisplayKind,
    val positionLabels: List<String> = emptyList(),
)

internal val ROVER_WS_PROFILE = listOf(
    RcProfileChannel(1, RcControl.J1, "WS Azimuth", RcProfileDisplayKind.PROPORTIONAL),
    RcProfileChannel(2, RcControl.J2, "WS Elevation", RcProfileDisplayKind.PROPORTIONAL),
    RcProfileChannel(3, RcControl.J3, "Rover Throttle", RcProfileDisplayKind.PROPORTIONAL),
    RcProfileChannel(4, RcControl.J4, "Rover Steering", RcProfileDisplayKind.PROPORTIONAL),
    RcProfileChannel(5, RcControl.FLIGHT, "Screen Preset", RcProfileDisplayKind.SWITCH, listOf("SPLIT", "GRID", "SIDE")),
    RcProfileChannel(6, RcControl.SA, "Rover Brakes", RcProfileDisplayKind.SWITCH, listOf("RELEASE", "HOLD", "LOCK")),
    RcProfileChannel(7, RcControl.SB, "WS Brakes", RcProfileDisplayKind.SWITCH, listOf("RELEASE", "HOLD", "LOCK")),
    RcProfileChannel(8, RcControl.SC, "Rover Lights", RcProfileDisplayKind.SWITCH, listOf("OFF", "LOW", "HIGH")),
    RcProfileChannel(9, RcControl.RD, "WS Camera Zoom", RcProfileDisplayKind.PROPORTIONAL),
    RcProfileChannel(10, RcControl.SE, "WS Firing Mode", RcProfileDisplayKind.SWITCH, listOf("SAFE", "SINGLE", "AUTO")),
    RcProfileChannel(11, RcControl.SF, "WS Tracking Mode", RcProfileDisplayKind.SWITCH, listOf("OFF", "MANUAL", "AUTO")),
    RcProfileChannel(12, RcControl.S1, "WS Trigger 1", RcProfileDisplayKind.BUTTON),
    RcProfileChannel(13, RcControl.S2, "WS Trigger 2", RcProfileDisplayKind.BUTTON),
    RcProfileChannel(14, RcControl.S4, "WS Cocking", RcProfileDisplayKind.BUTTON),
    RcProfileChannel(15, RcControl.LK, "Rover Speed Range", RcProfileDisplayKind.PROPORTIONAL),
    RcProfileChannel(16, RcControl.RK, "WS Speed Range", RcProfileDisplayKind.PROPORTIONAL),
)

internal fun RcControl.positionLabels(): List<String> =
    ROVER_WS_PROFILE.firstOrNull { it.control == this }?.positionLabels?.takeIf { it.size == 3 }
        ?: listOf("LOW", "MID", "HIGH")

internal fun List<RcControl?>.channelValue(
    control: RcControl,
    channels: List<Int>,
): Int {
    val index = indexOf(control)
    return if (index < 0) 0 else channels.getOrElse(index) { 0 }
}

internal fun List<RcControl?>.assign(
    channel: Int,
    control: RcControl?,
): List<RcControl?> =
    mapIndexed { index, current ->
        when {
            index == channel -> control
            control != null && current == control -> null
            else -> current
        }
    }

internal fun SharedPreferences.loadRcChannelMap(): List<RcControl?> {
    val raw = getString(PREFS_KEY_RC_CHANNEL_MAP, null) ?: return DEFAULT_RC_CHANNEL_MAP
    val parts = raw.split(',')
    if (parts.size != RC_CHANNEL_COUNT) return DEFAULT_RC_CHANNEL_MAP
    return parts.map { id -> RcControl.entries.firstOrNull { it.name == id } }
}

internal fun SharedPreferences.saveRcChannelMap(mapping: List<RcControl?>) {
    edit().putString(PREFS_KEY_RC_CHANNEL_MAP, mapping.joinToString(",") { it?.name.orEmpty() }).apply()
}
