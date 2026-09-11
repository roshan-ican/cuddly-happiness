package com.example.cameraremotecontroller

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

internal class GlVideoSurfaceView(context: Context) : GLSurfaceView(context) {
    private val videoRenderer = VideoRenderer()
    @Volatile private var surfaceGeneration = 0
    private var floatingLayer: Boolean? = null

    var onOutputSurfaceAvailable: ((Surface) -> Unit)? = null
    var onOutputSurfaceDestroyed: (() -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setRenderer(videoRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setFloatingLayer(floating: Boolean) {
        if (floatingLayer == floating) return
        floatingLayer = floating
        setZOrderOnTop(floating)
    }

    fun setVideoTransform(
            rotationDegrees: Int,
            fill: Boolean,
            scale: Float,
            panX: Float,
            panY: Float,
    ) {
        videoRenderer.rotationDegrees = ((rotationDegrees % 360) + 360) % 360
        videoRenderer.fill = fill
        videoRenderer.scale = scale
        videoRenderer.panX = panX
        videoRenderer.panY = panY
        requestRender()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth || height != oldHeight) {
            queueEvent { GLES20.glViewport(0, 0, width, height) }
            requestRender()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceGeneration++
        onOutputSurfaceDestroyed?.invoke()
        videoRenderer.releaseOutputSurface()
        super.surfaceDestroyed(holder)
    }

    private inner class VideoRenderer : Renderer, SurfaceTexture.OnFrameAvailableListener {
        @Volatile var rotationDegrees = 0
        @Volatile var fill = true
        @Volatile var scale = 1f
        @Volatile var panX = 0f
        @Volatile var panY = 0f

        private var program = 0
        private var textureId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var outputSurface: Surface? = null
        private var frameAvailable = false
        private val frameLock = Any()
        private val textureMatrix = FloatArray(16)
        private val positionMatrix = FloatArray(16)
        private val vertices =
                floatBuffer(
                        floatArrayOf(
                                -1f, -1f, 0f, 0f,
                                1f, -1f, 1f, 0f,
                                -1f, 1f, 0f, 1f,
                                1f, 1f, 1f, 1f,
                        ),
                )

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            releaseOutputSurface()
            val generation = ++surfaceGeneration
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            textureId = createExternalTexture()
            surfaceTexture = SurfaceTexture(textureId).also {
                it.setOnFrameAvailableListener(this)
            }
            outputSurface = Surface(surfaceTexture).also { surface ->
                post {
                    if (surfaceGeneration == generation && outputSurface === surface) {
                        onOutputSurfaceAvailable?.invoke(surface)
                    }
                }
            }
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val texture = surfaceTexture ?: return
            val hasFrame = synchronized(frameLock) {
                val available = frameAvailable
                frameAvailable = false
                available
            }
            if (hasFrame) {
                texture.updateTexImage()
                texture.getTransformMatrix(textureMatrix)
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (!hasFrame && texture.timestamp == 0L) return

            val viewAspect = width.toFloat() / height.coerceAtLeast(1)
            val rotated = rotationDegrees == 90 || rotationDegrees == 270
            val videoAspect = if (rotated) 9f / 16f else 16f / 9f
            val scaleX: Float
            val scaleY: Float
            if (fill) {
                scaleX = if (videoAspect > viewAspect) videoAspect / viewAspect else 1f
                scaleY = if (videoAspect > viewAspect) 1f else viewAspect / videoAspect
            } else {
                scaleX = if (videoAspect > viewAspect) 1f else videoAspect / viewAspect
                scaleY = if (videoAspect > viewAspect) viewAspect / videoAspect else 1f
            }
            Matrix.setIdentityM(positionMatrix, 0)
            Matrix.scaleM(positionMatrix, 0, scaleX, scaleY, 1f)
            Matrix.rotateM(positionMatrix, 0, -rotationDegrees.toFloat(), 0f, 0f, 1f)
            Matrix.translateM(positionMatrix, 0, 2f * panX, -2f * panY, 0f)
            Matrix.scaleM(positionMatrix, 0, scale, scale, 1f)

            GLES20.glUseProgram(program)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val textureCoordinate = GLES20.glGetAttribLocation(program, "aTextureCoordinate")
            val positionTransform = GLES20.glGetUniformLocation(program, "uPositionMatrix")
            val textureTransform = GLES20.glGetUniformLocation(program, "uTextureMatrix")

            vertices.position(0)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
            vertices.position(2)
            GLES20.glEnableVertexAttribArray(textureCoordinate)
            GLES20.glVertexAttribPointer(textureCoordinate, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glUniformMatrix4fv(positionTransform, 1, false, positionMatrix, 0)
            GLES20.glUniformMatrix4fv(textureTransform, 1, false, textureMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            synchronized(frameLock) { frameAvailable = true }
            requestRender()
        }

        fun releaseOutputSurface() {
            synchronized(frameLock) { frameAvailable = false }
            surfaceTexture?.setOnFrameAvailableListener(null)
            outputSurface?.release()
            outputSurface = null
            surfaceTexture?.release()
            surfaceTexture = null
        }

        private fun createExternalTexture(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return textures[0]
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            return GLES20.glCreateProgram().also { result ->
                GLES20.glAttachShader(result, vertexShader)
                GLES20.glAttachShader(result, fragmentShader)
                GLES20.glLinkProgram(result)
                val status = IntArray(1)
                GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
                check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(result) }
                GLES20.glDeleteShader(vertexShader)
                GLES20.glDeleteShader(fragmentShader)
            }
        }

        private fun compileShader(type: Int, source: String): Int =
                GLES20.glCreateShader(type).also { shader ->
                    GLES20.glShaderSource(shader, source)
                    GLES20.glCompileShader(shader)
                    val status = IntArray(1)
                    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
                    check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
                }
    }

    private companion object {
        val VERTEX_SHADER =
                """
                uniform mat4 uPositionMatrix;
                uniform mat4 uTextureMatrix;
                attribute vec4 aPosition;
                attribute vec4 aTextureCoordinate;
                varying vec2 vTextureCoordinate;
                void main() {
                    gl_Position = uPositionMatrix * aPosition;
                    vTextureCoordinate = (uTextureMatrix * aTextureCoordinate).xy;
                }
                """.trimIndent()

        val FRAGMENT_SHADER =
                """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES uTexture;
                varying vec2 vTextureCoordinate;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTextureCoordinate);
                }
                """.trimIndent()

        fun floatBuffer(values: FloatArray): FloatBuffer =
                ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()
                        .apply { put(values).position(0) }
    }
}
