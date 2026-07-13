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
import com.cloudwebrtc.webrtc.FlutterWebRTCPlugin

/** BlixouLibPlugin */
class BlixouLibPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private var activeProcessor: BeautyVideoProcessor? = null
    private var activity: Activity? = null

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(act: Activity) {
            if (act == activity) {
                android.util.Log.i("BlixouLib", "Activity resumed: Triggering OpenGL/EGL Context loss recovery...")
                activeProcessor?.forceReinit()
            }
        }
        override fun onActivityPaused(act: Activity) {}
        override fun onActivityStarted(act: Activity) {}
        override fun onActivityStopped(act: Activity) {}
        override fun onActivitySaveInstanceState(act: Activity, outState: Bundle) {}
        override fun onActivityCreated(act: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityDestroyed(act: Activity) {}
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
                val success = applyBeautyFilter(trackId, intensity)
                if (success) {
                    result.success(true)
                } else {
                    result.error("TRACK_NOT_FOUND", "Could not find native WebRTC VideoSource for trackId: $trackId", null)
                }
            }
            "setIntensity" -> {
                val intensity = call.argument<Double>("intensity")?.toFloat() ?: 0.5f
                updateIntensity(intensity)
                result.success(true)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun findVideoSource(trackId: String): VideoSource? {
        try {
            val pluginClass = Class.forName("com.cloudwebrtc.webrtc.FlutterWebRTCPlugin")

            // 1. Resilient Lookup: Find any static field containing an instance of FlutterWebRTCPlugin
            var pluginInstance: Any? = null
            for (field in pluginClass.declaredFields) {
                if (Modifier.isStatic(field.modifiers) && pluginClass.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    pluginInstance = field.get(null)
                    if (pluginInstance != null) {
                        break
                    }
                }
            }

            if (pluginInstance == null) {
                android.util.Log.w("BlixouLib", "Resilient Lookup: FlutterWebRTCPlugin singleton instance not found in static fields.")
                return null
            }

            // 2. Resilient Lookup: Find methodCallHandler field by type ending in MethodCallHandlerImpl
            var methodCallHandler: Any? = null
            for (field in pluginClass.declaredFields) {
                if (field.type.name.endsWith("MethodCallHandlerImpl")) {
                    field.isAccessible = true
                    methodCallHandler = field.get(pluginInstance)
                    if (methodCallHandler != null) {
                        break
                    }
                }
            }

            if (methodCallHandler == null) {
                try {
                    val field = pluginClass.getDeclaredField("methodCallHandler").apply { isAccessible = true }
                    methodCallHandler = field.get(pluginInstance)
                } catch (e: Exception) {
                    // Ignore
                }
            }

            if (methodCallHandler == null) {
                android.util.Log.w("BlixouLib", "Resilient Lookup: MethodCallHandlerImpl instance not found.")
                return null
            }

            // 3. Resilient Lookup: Find getUserMediaImpl field by type ending in GetUserMediaImpl
            var getUserMedia: Any? = null
            val handlerClass = methodCallHandler.javaClass
            for (field in handlerClass.declaredFields) {
                if (field.type.name.endsWith("GetUserMediaImpl")) {
                    field.isAccessible = true
                    getUserMedia = field.get(methodCallHandler)
                    if (getUserMedia != null) {
                        break
                    }
                }
            }

            if (getUserMedia == null) {
                try {
                    val field = handlerClass.getDeclaredField("getUserMediaImpl").apply { isAccessible = true }
                    getUserMedia = field.get(methodCallHandler)
                } catch (e: Exception) {
                    // Ignore
                }
            }

            if (getUserMedia == null) {
                android.util.Log.w("BlixouLib", "Resilient Lookup: GetUserMediaImpl instance not found.")
                return null
            }

            // 4. Resilient Lookup: Find videoSource field inside GetUserMediaImpl by scanning for VideoSource type
            val getUserMediaClass = getUserMedia.javaClass
            for (field in getUserMediaClass.declaredFields) {
                if (VideoSource::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    val source = field.get(getUserMedia) as? VideoSource
                    if (source != null) {
                        return source
                    }
                }
            }

            // Fallback: Check localTracks map registry inside methodCallHandler
            for (field in handlerClass.declaredFields) {
                if (Map::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    val map = field.get(methodCallHandler) as? Map<*, *>
                    val localTrack = map?.get(trackId)
                    if (localTrack != null) {
                        for (trackField in localTrack.javaClass.declaredFields) {
                            if (VideoSource::class.java.isAssignableFrom(trackField.type)) {
                                trackField.isAccessible = true
                                val source = trackField.get(localTrack) as? VideoSource
                                if (source != null) {
                                    return source
                                }
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("BlixouLib", "Resilient reflection lookup failed: ${e.message}")
        }

        return null
    }

    private fun applyBeautyFilter(trackId: String, intensity: Float): Boolean {
        val videoSource = findVideoSource(trackId) ?: return false

        activeProcessor?.release()

        val processor = BeautyVideoProcessor()
        processor.setIntensity(intensity)

        videoSource.setVideoProcessor(processor)
        activeProcessor = processor
        return true
    }

    private fun updateIntensity(intensity: Float) {
        activeProcessor?.setIntensity(intensity)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        activeProcessor?.release()
        activeProcessor = null
    }

    // Activity Lifecycle Observability implementation
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
