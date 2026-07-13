package com.softians.cli_blixou.blixou_lib

import android.graphics.Matrix
import org.webrtc.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.nio.ByteBuffer
import java.util.concurrent.Executors

object BeautyVideoProcessorJni {
    init {
        System.loadLibrary("blixou_lib")
    }

    @JvmStatic
    external fun initBeautyFilter(width: Int, height: Int): Int

    @JvmStatic
    external fun setBeautyIntensity(filterId: Int, intensity: Float)

    @JvmStatic
    external fun setFaceBounds(filterId: Int, minX: Float, minY: Float, maxX: Float, maxY: Float)

    @JvmStatic
    external fun processTexture(filterId: Int, textureId: Int, width: Int, height: Int): Int

    @JvmStatic
    external fun releaseBeautyFilter(filterId: Int)
}

class BeautyVideoProcessor : VideoProcessor {
    private var sink: VideoSink? = null
    private var filterId = 0
    private var intensity = 0.5f
    private var isReleased = false

    // OpenGL resources
    private var drawer: GlRectDrawer? = null
    private var fbo = 0
    private var intermediateTexture = 0
    private var width = 0
    private var height = 0

    // Lifecycle/Context recovery flag
    @Volatile
    private var forceReinitGl = false

    // Face detection resources
    private val faceDetectionExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var isDetectingFace = false
    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .build()
    private val faceDetector = FaceDetection.getClient(detectorOptions)

    fun setIntensity(intensity: Float) {
        this.intensity = intensity
        if (filterId != 0) {
            BeautyVideoProcessorJni.setBeautyIntensity(filterId, intensity)
        }
    }

    fun forceReinit() {
        forceReinitGl = true
    }

    override fun setSink(sink: VideoSink?) {
        this.sink = sink
    }

    override fun onCapturerStarted(success: Boolean) {
        sink?.onCapturerStarted(success)
    }

    override fun onCapturerStopped() {
        sink?.onCapturerStopped()
        release()
    }

    @Synchronized
    override fun onFrameCaptured(frame: VideoFrame) {
        if (isReleased) {
            sink?.onFrameCaptured(frame)
            return
        }

        // Handle EGL Context Loss / App Resume forced reinitialization
        if (forceReinitGl) {
            forceReinitGl = false
            releaseGl()
            // Reset C++ filter handles so it recompiles shaders in the new EGL context
            if (filterId != 0) {
                // Do NOT call JNI release if the old EGL context is already dead (it is handled internally in C++)
                filterId = 0
            }
        }

        val buffer = frame.buffer
        if (buffer !is VideoFrame.TextureBuffer || intensity <= 0.0f) {
            sink?.onFrameCaptured(frame)
            return
        }

        val w = buffer.width
        val h = buffer.height

        // Re-initialize resources if frame dimensions changed or after context reset
        if (drawer == null || w != width || h != height || filterId == 0) {
            releaseGl()
            width = w
            height = h
            drawer = GlRectDrawer()

            if (filterId == 0) {
                filterId = BeautyVideoProcessorJni.initBeautyFilter(width, height)
            }
            BeautyVideoProcessorJni.setBeautyIntensity(filterId, intensity)

            // Allocate standard 2D intermediate texture
            val textures = IntArray(1)
            android.opengl.GLES20.glGenTextures(1, textures, 0)
            intermediateTexture = textures[0]
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, intermediateTexture)
            android.opengl.GLES20.glTexImage2D(
                android.opengl.GLES20.GL_TEXTURE_2D, 0, android.opengl.GLES20.GL_RGBA,
                width, height, 0, android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, null
            )
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_S, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_T, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, 0)

            val fbos = IntArray(1)
            android.opengl.GLES20.glGenFramebuffers(1, fbos, 0)
            fbo = fbos[0]
        }

        // Asynchronous Face Detection on a background thread
        if (!isDetectingFace) {
            isDetectingFace = true
            buffer.retain()
            val currentRotation = frame.rotation
            faceDetectionExecutor.execute {
                try {
                    val i420 = buffer.toI420()
                    val nv21Bytes = i420ToNv21(i420)
                    i420.release()

                    val inputImage = InputImage.fromByteBuffer(
                        ByteBuffer.wrap(nv21Bytes),
                        w, h, currentRotation, InputImage.IMAGE_FORMAT_NV21
                    )

                    faceDetector.process(inputImage)
                        .addOnSuccessListener { faces ->
                            if (faces.isNotEmpty()) {
                                val largestFace = faces.maxByOrNull { face ->
                                    face.boundingBox.width() * face.boundingBox.height()
                                }
                                if (largestFace != null) {
                                    val box = largestFace.boundingBox

                                    // Determine upright dimensions relative to rotation
                                    val isRotated = (currentRotation == 90 || currentRotation == 270)
                                    val imgWidth = if (isRotated) h else w
                                    val imgHeight = if (isRotated) w else h

                                    // Normalize coordinates
                                    val leftNorm = box.left.toFloat() / imgWidth
                                    val rightNorm = box.right.toFloat() / imgWidth
                                    val topNorm = box.top.toFloat() / imgHeight
                                    val bottomNorm = box.bottom.toFloat() / imgHeight

                                    // Convert Top-Left origin (ML Kit) to Bottom-Left origin (OpenGL)
                                    val glMinX = leftNorm
                                    val glMaxX = rightNorm
                                    val glMinY = 1.0f - bottomNorm
                                    val glMaxY = 1.0f - topNorm

                                    if (filterId != 0) {
                                        BeautyVideoProcessorJni.setFaceBounds(filterId, glMinX, glMinY, glMaxX, glMaxY)
                                    }
                                }
                            } else {
                                // No face detected, pass 0 to shader to skip beauty rendering (early-exit)
                                if (filterId != 0) {
                                    BeautyVideoProcessorJni.setFaceBounds(filterId, 0.0f, 0.0f, 0.0f, 0.0f)
                                }
                            }
                            isDetectingFace = false
                            buffer.release()
                        }
                        .addOnFailureListener {
                            isDetectingFace = false
                            buffer.release()
                        }
                } catch (e: Exception) {
                    isDetectingFace = false
                    buffer.release()
                }
            }
        }

        // Bind FBO and copy OES/RGB camera texture to standard 2D texture using WebRTC's GlRectDrawer
        android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, fbo)
        android.opengl.GLES20.glFramebufferTexture2D(
            android.opengl.GLES20.GL_FRAMEBUFFER, android.opengl.GLES20.GL_COLOR_ATTACHMENT0,
            android.opengl.GLES20.GL_TEXTURE_2D, intermediateTexture, 0
        )

        val transformMatrix = RendererCommon.convertMatrixFromAndroidGraphicsMatrix(buffer.transformMatrix)
        if (buffer.type == VideoFrame.TextureBuffer.Type.OES) {
            drawer?.drawOes(buffer.textureId, transformMatrix, width, height, 0, 0, width, height)
        } else {
            drawer?.drawRgb(buffer.textureId, transformMatrix, width, height, 0, 0, width, height)
        }
        android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, 0)

        // Process texture via GPU C++/GLSL filter
        val processedTextureId = BeautyVideoProcessorJni.processTexture(filterId, intermediateTexture, width, height)

        // Wrap the processed texture ID in a custom delegating TextureBuffer implementation
        val identityMatrix = Matrix()
        val processedBuffer = object : VideoFrame.TextureBuffer {
            override fun getWidth(): Int = width
            override fun getHeight(): Int = height
            override fun getType(): VideoFrame.TextureBuffer.Type = VideoFrame.TextureBuffer.Type.RGB
            override fun getTextureId(): Int = processedTextureId
            override fun getTransformMatrix(): Matrix = identityMatrix

            override fun toI420(): VideoFrame.I420Buffer {
                return buffer.toI420()
            }

            override fun retain() {
                buffer.retain()
            }

            override fun release() {
                buffer.release()
            }
        }

        // Pass frame rotation as 0 since the rotation has already been baked into the texture by GlRectDrawer
        val processedFrame = VideoFrame(processedBuffer, 0, frame.timestampNs)
        sink?.onFrameCaptured(processedFrame)
        processedFrame.release()
    }

    private fun i420ToNv21(i420: VideoFrame.I420Buffer): ByteArray {
        val width = i420.width
        val height = i420.height
        val nv21 = ByteArray(width * height + (width * height / 2))

        val yData = i420.dataY.duplicate()
        val uData = i420.dataU.duplicate()
        val vData = i420.dataV.duplicate()

        val yStride = i420.strideY
        val uStride = i420.strideU
        val vStride = i420.strideV

        var offset = 0
        for (row in 0 until height) {
            yData.position(row * yStride)
            yData.get(nv21, offset, width)
            offset += width
        }

        val uvHeight = height / 2
        val uvWidth = width / 2

        var uvOffset = width * height
        for (row in 0 until uvHeight) {
            val uRowOffset = row * uStride
            val vRowOffset = row * vStride
            for (col in 0 until uvWidth) {
                uData.position(uRowOffset + col)
                vData.position(vRowOffset + col)

                nv21[uvOffset++] = vData.get() // V
                nv21[uvOffset++] = uData.get() // U
            }
        }

        return nv21
    }

    @Synchronized
    private fun releaseGl() {
        drawer?.release()
        drawer = null
        if (fbo != 0) {
            android.opengl.GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        if (intermediateTexture != 0) {
            android.opengl.GLES20.glDeleteTextures(1, intArrayOf(intermediateTexture), 0)
            intermediateTexture = 0
        }
    }

    @Synchronized
    fun release() {
        if (isReleased) return
        isReleased = true
        releaseGl()
        faceDetectionExecutor.shutdown()
        faceDetector.close()
        if (filterId != 0) {
            // Do NOT call JNI release if the EGL Context is already dead/invalidated
            try {
                BeautyVideoProcessorJni.releaseBeautyFilter(filterId)
            } catch (e: Exception) {
                // Ignore
            }
            filterId = 0
        }
    }
}
