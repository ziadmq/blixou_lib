import 'dart:ffi' as ffi;
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'blixou_lib_platform_interface.dart';

class BlixouLib {
  // ─── Lazy FFI Loading ───────────────────────────────────────────────────────
  // The native library is loaded ONCE, on first use, and only if the platform
  // actually supports it. If loading fails (e.g. .so not found, wrong ABI)
  // we set _nativeLibLoaded = false and all direct-FFI calls become no-ops.
  static ffi.DynamicLibrary? _nativeLib;
  static bool _nativeLibLoaded = false;
  static bool _loadAttempted = false;

  static void _ensureLibLoaded() {
    if (_loadAttempted) return;
    _loadAttempted = true;
    try {
      if (Platform.isIOS || Platform.isMacOS) {
        _nativeLib = ffi.DynamicLibrary.process();
      } else if (Platform.isAndroid) {
        _nativeLib = ffi.DynamicLibrary.open('libblixou_lib.so');
      } else if (Platform.isWindows) {
        _nativeLib = ffi.DynamicLibrary.open('blixou_lib.dll');
      } else {
        debugPrint('⚠️ [BlixouLib] Unsupported platform for direct FFI');
        return;
      }
      _nativeLibLoaded = true;
      debugPrint('✅ [BlixouLib] Native library loaded successfully');
    } catch (e) {
      debugPrint('⚠️ [BlixouLib] Could not load native library (FFI disabled): $e');
    }
  }

  // ─── FFI Function Bindings (Lazy) ──────────────────────────────────────────
  static int Function(int, int)? __initBeautyFilter;
  static void Function(int, double)? __setBeautyIntensity;
  static void Function(int, double, double, double, double)? __setFaceBounds;
  static int Function(int, int, int, int)? __processTexture;
  static void Function(int)? __releaseBeautyFilter;

  static void _ensureBindings() {
    _ensureLibLoaded();
    if (!_nativeLibLoaded || _nativeLib == null) return;
    if (__initBeautyFilter != null) return; // Already bound
    try {
      __initBeautyFilter = _nativeLib!
          .lookup<ffi.NativeFunction<ffi.Int32 Function(ffi.Int32, ffi.Int32)>>(
              'init_beauty_filter')
          .asFunction();
      __setBeautyIntensity = _nativeLib!
          .lookup<ffi.NativeFunction<ffi.Void Function(ffi.Int32, ffi.Float)>>(
              'set_beauty_intensity')
          .asFunction();
      __setFaceBounds = _nativeLib!
          .lookup<ffi.NativeFunction<ffi.Void Function(ffi.Int32, ffi.Float, ffi.Float, ffi.Float, ffi.Float)>>(
              'set_face_bounds')
          .asFunction();
      __processTexture = _nativeLib!
          .lookup<ffi.NativeFunction<ffi.Int32 Function(ffi.Int32, ffi.Int32, ffi.Int32, ffi.Int32)>>(
              'process_texture')
          .asFunction();
      __releaseBeautyFilter = _nativeLib!
          .lookup<ffi.NativeFunction<ffi.Void Function(ffi.Int32)>>(
              'release_beauty_filter')
          .asFunction();
    } catch (e) {
      debugPrint('⚠️ [BlixouLib] Failed to bind FFI symbols: $e');
      _nativeLibLoaded = false;
    }
  }

  /// Whether the native library was successfully loaded. If false, all
  /// direct-FFI calls are no-ops, and MethodChannel calls should still work.
  static bool get isNativeLibAvailable {
    _ensureLibLoaded();
    return _nativeLibLoaded;
  }

  // ─── Static direct-FFI API (safe no-ops if lib unavailable) ────────────────
  static int initFilter(int width, int height) {
    _ensureBindings();
    return __initBeautyFilter?.call(width, height) ?? 0;
  }

  static void setFilterIntensity(int filterId, double intensity) {
    _ensureBindings();
    __setBeautyIntensity?.call(filterId, intensity);
  }

  static void setFilterFaceBounds(int filterId, double minX, double minY, double maxX, double maxY) {
    _ensureBindings();
    __setFaceBounds?.call(filterId, minX, minY, maxX, maxY);
  }

  static int processTexture(int filterId, int textureId, int width, int height) {
    _ensureBindings();
    return __processTexture?.call(filterId, textureId, width, height) ?? textureId;
  }

  static void releaseFilter(int filterId) {
    _ensureBindings();
    __releaseBeautyFilter?.call(filterId);
  }

  // ─── MethodChannel delegates (instance API) ─────────────────────────────────
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
