package com.example.cameraremotecontroller

internal data class VideoTransform(
        val scale: Float = 1f,
        val panX: Float = 0f,
        val panY: Float = 0f,
)

internal fun updateVideoTransform(
        current: VideoTransform,
        zoomChange: Float,
        panXChange: Float,
        panYChange: Float,
): VideoTransform {
    val scale = (current.scale * zoomChange).coerceIn(1f, 8f)
    val maxPan = (scale - 1f) / 2f
    return VideoTransform(
            scale = scale,
            panX = (current.panX + panXChange).coerceIn(-maxPan, maxPan),
            panY = (current.panY + panYChange).coerceIn(-maxPan, maxPan),
    )
}

internal fun rotateGestureIntoVideo(rotation: Int, x: Float, y: Float): Pair<Float, Float> =
        when (((rotation % 360) + 360) % 360) {
            90 -> y to -x
            180 -> -x to -y
            270 -> -y to x
            else -> x to y
        }
