package com.softians.cli_blixou.blixou_lib

import android.graphics.Matrix
import org.webrtc.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.nio.ByteBuffer
import java.util.concurrent.Executors

object BeautyVideoProcessorJni {
    // ✅ تحميل المكتبة بأمان — إذا فشل لا يُعطّل التطبيق
    private var isLibLoaded: Boolean = false

    init {
        try {
            System.loadLibrary("blixou_lib")
            isLibLoaded = true
            android.util.Log.i("BlixouLib", "✅ libblixou_lib.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            isLibLoaded = false
            android.util.Log.w("BlixouLib", "⚠️ libblixou_lib.so not available on this device/ABI (beauty filter disabled): ${e.message}")
        } catch (e: Throwable) {
            isLibLoaded = false
            android.util.Log.e("BlixouLib", "⚠️ Unexpected error loading libblixou_lib.so: ${e.message}")
        }
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

    // ─── دوال آمنة تتحقق أولاً من تحميل المكتبة ─────────────────────────────
    fun safeInitBeautyFilter(width: Int, height: Int): Int =
        if (isLibLoaded) try { initBeautyFilter(width, height) } catch (e: Throwable) { 0 } else 0

    fun safeSetBeautyIntensity(filterId: Int, intensity: Float) {
        if (!isLibLoaded || filterId == 0) return
        try { setBeautyIntensity(filterId, intensity) } catch (e: Throwable) { /* تجاهل */ }
    }

    fun safeSetFaceBounds(filterId: Int, minX: Float, minY: Float, maxX: Float, maxY: Float) {
        if (!isLibLoaded || filterId == 0) return
        try { setFaceBounds(filterId, minX, minY, maxX, maxY) } catch (e: Throwable) { /* تجاهل */ }
    }

    fun safeProcessTexture(filterId: Int, textureId: Int, width: Int, height: Int): Int =
        if (isLibLoaded && filterId != 0)
            try { processTexture(filterId, textureId, width, height) } catch (e: Throwable) { textureId }
        else textureId // أعد textureId الأصلي بدون فلتر

    fun safeReleaseBeautyFilter(filterId: Int) {
        if (!isLibLoaded || filterId == 0) return
        try { releaseBeautyFilter(filterId) } catch (e: Throwable) { /* تجاهل */ }
    }
}


class BeautyVideoProcessor : VideoProcessor {
    private var sink: VideoSink? = null
    private var filterId = 0
    private var intensity = 0.5f
    private var isReleased = false

    // OpenGL resources (Triple buffered input textures to prevent tearing/overwrites)
    private var drawer: GlRectDrawer? = null
    private var fbo = 0
    private val poolSize = 3
    private val intermediateTextures = IntArray(poolSize)
    private var poolIndex = 0
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

    // ✅ حارس الإعادة — يمنع استدعاء onFrameCaptured من داخل نفسه
    private val isProcessingFrame = java.util.concurrent.atomic.AtomicBoolean(false)

    fun setIntensity(intensity: Float) {
        this.intensity = intensity
        if (filterId != 0) {
            BeautyVideoProcessorJni.safeSetBeautyIntensity(filterId, intensity)
        }
    }

    fun forceReinit() {
        forceReinitGl = true
    }

    override fun setSink(sink: VideoSink?) {
        this.sink = sink
    }

    override fun onCapturerStarted(success: Boolean) {
        // No-op for downstream, handled by camera session
    }

    override fun onCapturerStopped() {
        release()
    }

    // ❌ أزلنا @Synchronized — كانت تسمح بالإعادة على نفس الـ thread وتسبب StackOverflow
    override fun onFrameCaptured(frame: VideoFrame) {
        // ✅ منع الاستدعاء المتكرر (Reentrancy Guard)
        if (!isProcessingFrame.compareAndSet(false, true)) {
            // إذا كنا نعالج إطاراً بالفعل — أرسل الإطار الخام مباشرة بدون معالجة
            sink?.onFrame(frame)
            return
        }
        try {
            if (isReleased) {
                sink?.onFrame(frame)
                return
            }

            // Handle EGL Context Loss / App Resume forced reinitialization
            if (forceReinitGl) {
                forceReinitGl = false
                releaseGl()
                if (filterId != 0) {
                    filterId = 0
                }
            }

            val buffer = frame.buffer
            if (buffer !is VideoFrame.TextureBuffer || intensity <= 0.0f) {
                sink?.onFrame(frame)
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
                    filterId = BeautyVideoProcessorJni.safeInitBeautyFilter(width, height)
                    android.util.Log.i("BlixouLib", "🎨 initBeautyFilter(${width}x${height}) → filterId=$filterId")
                    // ✅ ابدأ بتطبيق الفلتر على الصورة كاملة حتى قبل كشف الوجه
                    BeautyVideoProcessorJni.safeSetFaceBounds(filterId, 0.0f, 0.0f, 1.0f, 1.0f)
                }
                BeautyVideoProcessorJni.safeSetBeautyIntensity(filterId, intensity)
                android.util.Log.i("BlixouLib", "🎨 setIntensity filterId=$filterId intensity=$intensity")

                // Allocate standard 2D intermediate texture pool (Triple Buffering)
                val textures = IntArray(poolSize)
                android.opengl.GLES20.glGenTextures(poolSize, textures, 0)
                for (i in 0 until poolSize) {
                    intermediateTextures[i] = textures[i]
                    android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, intermediateTextures[i])
                    android.opengl.GLES20.glTexImage2D(
                        android.opengl.GLES20.GL_TEXTURE_2D, 0, android.opengl.GLES20.GL_RGBA,
                        width, height, 0, android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, null
                    )
                    android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
                    android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
                    android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_S, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
                    android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_T, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
                }
                android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, 0)

                val fbos = IntArray(1)
                android.opengl.GLES20.glGenFramebuffers(1, fbos, 0)
                fbo = fbos[0]
                poolIndex = 0
            }

            // Asynchronous Face Detection on a background thread
            if (!isDetectingFace) {
                isDetectingFace = true
                var nv21Bytes: ByteArray? = null
                try {
                    val i420 = buffer.toI420()
                    if (i420 != null) {
                        nv21Bytes = i420ToNv21(i420)
                        i420.release()
                    }
                } catch (e: Exception) {
                    isDetectingFace = false
                }

                if (nv21Bytes != null) {
                    val currentRotation = frame.rotation
                    faceDetectionExecutor.execute {
                        try {
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
                                                BeautyVideoProcessorJni.safeSetFaceBounds(filterId, glMinX, glMinY, glMaxX, glMaxY)
                                            }
                                        }
                                    } else {
                                        // ✅ لا يوجد وجه — طبّق الفلتر على الصورة كاملة بدلاً من إيقافه
                                        if (filterId != 0) {
                                            BeautyVideoProcessorJni.safeSetFaceBounds(filterId, 0.0f, 0.0f, 1.0f, 1.0f)
                                        }
                                    }
                                    isDetectingFace = false
                                }
                                .addOnFailureListener {
                                    isDetectingFace = false
                                }
                        } catch (e: Exception) {
                            isDetectingFace = false
                        }
                    }
                } else {
                    isDetectingFace = false
                }
            }

            // Cycle through triple buffered input textures to prevent tearing/overwrites
            poolIndex = (poolIndex + 1) % poolSize
            val targetInputTextureId = intermediateTextures[poolIndex]

            // Bind FBO and copy OES/RGB camera texture to standard 2D texture using WebRTC's GlRectDrawer
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, fbo)
            android.opengl.GLES20.glFramebufferTexture2D(
                android.opengl.GLES20.GL_FRAMEBUFFER, android.opengl.GLES20.GL_COLOR_ATTACHMENT0,
                android.opengl.GLES20.GL_TEXTURE_2D, targetInputTextureId, 0
            )

            val transformMatrix = RendererCommon.convertMatrixFromAndroidGraphicsMatrix(buffer.transformMatrix)
            if (buffer.type == VideoFrame.TextureBuffer.Type.OES) {
                drawer?.drawOes(buffer.textureId, transformMatrix, width, height, 0, 0, width, height)
            } else {
                drawer?.drawRgb(buffer.textureId, transformMatrix, width, height, 0, 0, width, height)
            }
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, 0)

            // Process texture via GPU C++/GLSL filter (Triple buffered internally in C++)
            val processedTextureId = BeautyVideoProcessorJni.safeProcessTexture(filterId, targetInputTextureId, width, height)
            if (processedTextureId == targetInputTextureId) {
                android.util.Log.w("BlixouLib", "⚠️ processTexture returned same texture (filterId=$filterId) — filter may be inactive")
            }

            // Flush commands to GPU so they execute asynchronously without CPU blocking
            android.opengl.GLES20.glFlush()

            // ✅ احتفظ بـ buffer قبل تمريره — لأن WebRTC يحتفظ به لاحقاً بشكل غير متزامن
            buffer.retain()

            // ✅ التقط القيم كمتغيرات محلية قبل إنشاء الـ anonymous object
            val capturedWidth = width
            val capturedHeight = height
            val capturedTextureId = processedTextureId
            val identityMatrix = Matrix()

            val processedBuffer = object : VideoFrame.TextureBuffer {
                override fun getWidth(): Int = capturedWidth
                override fun getHeight(): Int = capturedHeight
                override fun getType(): VideoFrame.TextureBuffer.Type = VideoFrame.TextureBuffer.Type.RGB
                override fun getTextureId(): Int = capturedTextureId
                override fun getTransformMatrix(): Matrix = identityMatrix

                override fun toI420(): VideoFrame.I420Buffer {
                    return buffer.toI420()!!
                }

                override fun retain() {
                    buffer.retain() // ✅ آمن لأننا retain() مسبقاً قبل إنشاء هذا الـ object
                }

                override fun release() {
                    buffer.release() // ✅ يُحرِّر الـ retain الإضافي الذي أضفناه
                }

                override fun cropAndScale(
                    cropX: Int, cropY: Int,
                    cropWidth: Int, cropHeight: Int,
                    scaleWidth: Int, scaleHeight: Int
                ): VideoFrame.Buffer {
                    return buffer.cropAndScale(cropX, cropY, cropWidth, cropHeight, scaleWidth, scaleHeight)
                }
            }


            // ✅ نمرر frame.rotation الفعلية — GlRectDrawer يطبق فقط camera sensor transform
            // لكنه لا يخبز device orientation rotation في الـ texture
            // لذا يجب إبلاغ WebRTC بدوران الإطار ليطبقه عند العرض والتشفير
            val processedFrame = VideoFrame(processedBuffer, frame.rotation, frame.timestampNs)

            sink?.onFrame(processedFrame)
            processedFrame.release() // ✅ مطلوب — لإعادة المخازن للكاميرا وإلا تتجمّد
        } catch (t: Throwable) {
            android.util.Log.e("BlixouLib", "onFrameCaptured error: ${t.message}", t)
            sink?.onFrame(frame) // مرر الإطار الأصلي عند الفشل
        } finally {
            // ✅ تأكد دائماً من تحرير الحارس حتى لو حدث خطأ
            isProcessingFrame.set(false)
        }
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
        if (android.opengl.EGL14.eglGetCurrentContext() == android.opengl.EGL14.EGL_NO_CONTEXT) {
            drawer = null
            return
        }
        drawer?.release()
        drawer = null
        if (fbo != 0) {
            android.opengl.GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        if (intermediateTextures[0] != 0) {
            android.opengl.GLES20.glDeleteTextures(poolSize, intermediateTextures, 0)
            for (i in 0 until poolSize) {
                intermediateTextures[i] = 0
            }
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
            try {
                BeautyVideoProcessorJni.safeReleaseBeautyFilter(filterId)
            } catch (e: Exception) {
                // Ignore
            }
            filterId = 0
        }
    }
}
