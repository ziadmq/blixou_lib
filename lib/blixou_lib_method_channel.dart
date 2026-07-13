import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'blixou_lib_platform_interface.dart';

/// An implementation of [BlixouLibPlatform] that uses method channels.
class MethodChannelBlixouLib extends BlixouLibPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('blixou_lib');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>(
      'getPlatformVersion',
    );
    return version;
  }

  @override
  Future<bool> applyToTrack(String trackId, {double intensity = 0.5}) async {
    final result = await methodChannel.invokeMethod<bool>(
      'applyToTrack',
      {
        'trackId': trackId,
        'intensity': intensity,
      },
    );
    return result ?? false;
  }

  @override
  Future<bool> setIntensity(double intensity) async {
    final result = await methodChannel.invokeMethod<bool>(
      'setIntensity',
      {
        'intensity': intensity,
      },
    );
    return result ?? false;
  }
}
