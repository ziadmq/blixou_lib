package com.softians.cli_blixou.blixou_lib

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import org.webrtc.VideoSource
import java.lang.reflect.Modifier

/** BlixouLibPlugin */
class BlixouLibPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private var activeProcessor: BeautyVideoProcessor? = null
    private var activity: Activity? = null

    // ─── دالة forceReinit آمنة: لا تفعل شيئاً إذا لم يكن هناك معالج نشط ────
    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(act: Activity) {
            if (act == activity) {
                android.util.Log.i("BlixouLib", "Activity resumed: Triggering OpenGL/EGL Context loss recovery...")
                // ✅ آمن: forceReinit فقط يضع علامة boolean، لا يستدعي GLES مباشرة
                try {
                    activeProcessor?.forceReinit()
                } catch (e: Throwable) {
                    android.util.Log.e("BlixouLib", "forceReinit error (ignored): ${e.message}")
                }
            }
        }
        override fun onActivityPaused(act: Activity) {}
        override fun onActivityStarted(act: Activity) {}
        override fun onActivityStopped(act: Activity) {}
        override fun onActivitySaveInstanceState(act: Activity, outState: Bundle) {}
        override fun onActivityCreated(act: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityDestroyed(act: Activity) {
            // ✅ أطلق الموارد عند تدمير الـ Activity لمنع memory leaks
            if (act == activity) {
                try {
                    activeProcessor?.release()
                    activeProcessor = null
                } catch (e: Throwable) {
                    android.util.Log.e("BlixouLib", "onActivityDestroyed cleanup error: ${e.message}")
                }
            }
        }
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "blixou_lib")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }
            "applyToTrack" -> {
                val trackId = call.argument<String>("trackId")
                val intensity = call.argument<Double>("intensity")?.toFloat() ?: 0.5f
                if (trackId == null) {
                    result.error("INVALID_ARGUMENT", "trackId cannot be null", null)
                    return
                }
                try {
                    val success = applyBeautyFilter(trackId, intensity)
                    if (success) {
                        result.success(true)
                    } else {
                        // ✅ نُعيد false بدلاً من error لمنع exception في Dart
                        result.success(false)
                        android.util.Log.w("BlixouLib", "applyToTrack: VideoSource not found for trackId=$trackId")
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("BlixouLib", "applyToTrack exception: ${e.message}", e)
                    result.success(false) // ✅ لا ترمي error يسبب exception في Dart
                }
            }
            "setIntensity" -> {
                val intensity = call.argument<Double>("intensity")?.toFloat() ?: 0.5f
                try {
                    updateIntensity(intensity)
                } catch (e: Throwable) {
                    android.util.Log.e("BlixouLib", "setIntensity exception: ${e.message}", e)
                }
                result.success(true)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    // ─── باحث VideoSource بالانعكاس (Reflection) ──────────────────────────────
    private fun findVideoSourceResiliently(obj: Any?, visited: MutableSet<Any>, depth: Int): VideoSource? {
        if (obj == null || depth > 5 || visited.contains(obj)) return null
        visited.add(obj)

        if (obj is VideoSource) return obj

        if (obj is Map<*, *>) {
            for (value in obj.values) {
                val found = findVideoSourceResiliently(value, visited, depth + 1)
                if (found != null) return found
            }
        }

        if (obj is Collection<*>) {
            for (value in obj) {
                val found = findVideoSourceResiliently(value, visited, depth + 1)
                if (found != null) return found
            }
        }

        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && !clazz.name.startsWith("java.") && !clazz.name.startsWith("android.")) {
            for (field in clazz.declaredFields) {
                try {
                    if (Modifier.isStatic(field.modifiers)) continue
                    field.isAccessible = true
                    val fieldValue = field.get(obj)
                    if (fieldValue != null) {
                        val found = findVideoSourceResiliently(fieldValue, visited, depth + 1)
                        if (found != null) return found
                    }
                } catch (e: Throwable) {
                    // تجاهل أخطاء الوصول
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun findVideoSource(trackId: String): VideoSource? {
        return try {
            val pluginClass = Class.forName("com.cloudwebrtc.webrtc.FlutterWebRTCPlugin")

            var pluginInstance: Any? = null
            for (field in pluginClass.declaredFields) {
                if (Modifier.isStatic(field.modifiers) && pluginClass.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    pluginInstance = field.get(null)
                    if (pluginInstance != null) break
                }
            }

            if (pluginInstance == null) {
                android.util.Log.w("BlixouLib", "FlutterWebRTCPlugin instance not found in static fields")
                return null
            }

            val source = findVideoSourceResiliently(pluginInstance, HashSet(), 0)
            if (source != null) {
                android.util.Log.i("BlixouLib", "VideoSource found successfully via reflection")
            }
            source
        } catch (e: Throwable) {
            android.util.Log.e("BlixouLib", "findVideoSource failed: ${e.message}")
            null
        }
    }

    private fun applyBeautyFilter(trackId: String, intensity: Float): Boolean {
        return try {
            val videoSource = findVideoSource(trackId) ?: return false

            // ✅ أطلق المعالج القديم بأمان قبل إنشاء معالج جديد
            try {
                activeProcessor?.release()
            } catch (e: Throwable) {
                android.util.Log.w("BlixouLib", "Old processor release error (ignored): ${e.message}")
            }
            activeProcessor = null

            val processor = BeautyVideoProcessor()
            processor.setIntensity(intensity)
            videoSource.setVideoProcessor(processor)
            activeProcessor = processor

            android.util.Log.i("BlixouLib", "Beauty filter applied ✅ trackId=$trackId intensity=$intensity")
            true
        } catch (e: Throwable) {
            android.util.Log.e("BlixouLib", "applyBeautyFilter failed: ${e.message}", e)
            false
        }
    }

    private fun updateIntensity(intensity: Float) {
        try {
            activeProcessor?.setIntensity(intensity)
        } catch (e: Throwable) {
            android.util.Log.e("BlixouLib", "updateIntensity error: ${e.message}")
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        try {
            activeProcessor?.release()
        } catch (e: Throwable) {
            android.util.Log.e("BlixouLib", "onDetachedFromEngine cleanup error: ${e.message}")
        }
        activeProcessor = null
    }

    // ─── دورة حياة الـ Activity ───────────────────────────────────────────────
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activity?.application?.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity?.application?.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        activity?.application?.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    override fun onDetachedFromActivity() {
        activity?.application?.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        activity = null
    }
}
