import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'blixou_lib_method_channel.dart';

abstract class BlixouLibPlatform extends PlatformInterface {
  /// Constructs a BlixouLibPlatform.
  BlixouLibPlatform() : super(token: _token);

  static final Object _token = Object();

  static BlixouLibPlatform _instance = MethodChannelBlixouLib();

  /// The default instance of [BlixouLibPlatform] to use.
  ///
  /// Defaults to [MethodChannelBlixouLib].
  static BlixouLibPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [BlixouLibPlatform] when
  /// they register themselves.
  static set instance(BlixouLibPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<bool> applyToTrack(String trackId, {double intensity = 0.5}) {
    throw UnimplementedError('applyToTrack() has not been implemented.');
  }

  Future<bool> setIntensity(double intensity) {
    throw UnimplementedError('setIntensity() has not been implemented.');
  }
}
