package com.example.cameraremotecontroller

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTransformTest {
    @Test
    fun `scale stays between one and eight`() {
        assertEquals(1f, updateVideoTransform(VideoTransform(), 0.1f, 0f, 0f).scale)
        assertEquals(8f, updateVideoTransform(VideoTransform(), 20f, 0f, 0f).scale)
    }

    @Test
    fun `pan is clamped to the visible image`() {
        val transformed = updateVideoTransform(VideoTransform(scale = 4f), 1f, 10f, -10f)

        assertEquals(1.5f, transformed.panX)
        assertEquals(-1.5f, transformed.panY)
    }

    @Test
    fun `zooming out reclamps an existing pan`() {
        val transformed =
                updateVideoTransform(
                        VideoTransform(scale = 8f, panX = 3.5f, panY = -3.5f),
                        0.25f,
                        0f,
                        0f,
                )

        assertEquals(2f, transformed.scale)
        assertEquals(0.5f, transformed.panX)
        assertEquals(-0.5f, transformed.panY)
    }

    @Test
    fun `gesture deltas rotate into video coordinates`() {
        assertEquals(0.25f to -0.4f, rotateGestureIntoVideo(0, 0.25f, -0.4f))
        assertEquals(-0.4f to -0.25f, rotateGestureIntoVideo(90, 0.25f, -0.4f))
        assertEquals(-0.25f to 0.4f, rotateGestureIntoVideo(180, 0.25f, -0.4f))
        assertEquals(0.4f to 0.25f, rotateGestureIntoVideo(270, 0.25f, -0.4f))
    }
}
