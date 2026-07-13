import Flutter
import UIKit

@_silgen_name("set_global_beauty_enabled")
func set_global_beauty_enabled(_ enabled: Int32)

@_silgen_name("set_global_beauty_intensity")
func set_global_beauty_intensity(_ intensity: Float)

public class BlixouLibPlugin: NSObject, FlutterPlugin {
  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "blixou_lib", binaryMessenger: registrar.messenger())
    let instance = BlixouLibPlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "getPlatformVersion":
      result("iOS " + UIDevice.current.systemVersion)
    case "applyToTrack":
      set_global_beauty_enabled(1)
      if let args = call.arguments as? [String: Any],
         let intensity = args["intensity"] as? Double {
          set_global_beauty_intensity(Float(intensity))
      }
      result(true)
    case "setIntensity":
      if let args = call.arguments as? [String: Any],
         let intensity = args["intensity"] as? Double {
          set_global_beauty_intensity(Float(intensity))
      }
      result(true)
    default:
      result(FlutterMethodNotImplemented)
    }
  }
}
