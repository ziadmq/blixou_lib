import 'dart:ffi' as ffi;
import 'dart:io';
import 'blixou_lib_platform_interface.dart';

class BlixouLib {
  // Main FFI loading
  static final ffi.DynamicLibrary _nativeLib = () {
    if (Platform.isIOS || Platform.isMacOS) {
      return ffi.DynamicLibrary.process();
    } else if (Platform.isAndroid) {
      return ffi.DynamicLibrary.open('libblixou_lib.so');
    } else if (Platform.isWindows) {
      return ffi.DynamicLibrary.open('blixou_lib.dll');
    } else {
      throw UnsupportedError('Unsupported platform');
    }
  }();

  // Bind C++ FFI functions
  static final int Function(int, int) _initBeautyFilter = _nativeLib
      .lookup<ffi.NativeFunction<ffi.Int32 Function(ffi.Int32, ffi.Int32)>>(
          'init_beauty_filter')
      .asFunction();

  static final void Function(int, double) _setBeautyIntensity = _nativeLib
      .lookup<ffi.NativeFunction<ffi.Void Function(ffi.Int32, ffi.Float)>>(
          'set_beauty_intensity')
      .asFunction();

  static final void Function(int, double, double, double, double) _setFaceBounds =
      _nativeLib
          .lookup<
              ffi.NativeFunction<
                  ffi.Void Function(ffi.Int32, ffi.Float, ffi.Float, ffi.Float,
                      ffi.Float)>>('set_face_bounds')
          .asFunction();

  static final int Function(int, int, int, int) _processTexture = _nativeLib
      .lookup<
          ffi.NativeFunction<
              ffi.Int32 Function(ffi.Int32, ffi.Int32, ffi.Int32,
                  ffi.Int32)>>('process_texture')
      .asFunction();

  static final void Function(int) _releaseBeautyFilter = _nativeLib
      .lookup<ffi.NativeFunction<ffi.Void Function(ffi.Int32)>>(
          'release_beauty_filter')
      .asFunction();

  // Expose static Dart FFI wrapper methods (Fallback Direct API)
  static int initFilter(int width, int height) => _initBeautyFilter(width, height);
  static void setFilterIntensity(int filterId, double intensity) =>
      _setBeautyIntensity(filterId, intensity);
  static void setFilterFaceBounds(int filterId, double minX, double minY, double maxX, double maxY) =>
      _setFaceBounds(filterId, minX, minY, maxX, maxY);
  static int processTexture(int filterId, int textureId, int width, int height) =>
      _processTexture(filterId, textureId, width, height);
  static void releaseFilter(int filterId) => _releaseBeautyFilter(filterId);

  // Expose delegate platform MethodChannel calls
  Future<String?> getPlatformVersion() {
    return BlixouLibPlatform.instance.getPlatformVersion();
  }

  Future<bool> applyToTrack(String trackId, {double intensity = 0.5}) {
    return BlixouLibPlatform.instance.applyToTrack(trackId, intensity: intensity);
  }

  Future<bool> setIntensity(double intensity) {
    return BlixouLibPlatform.instance.setIntensity(intensity);
  }
}
