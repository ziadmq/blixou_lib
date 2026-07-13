import 'package:flutter_test/flutter_test.dart';
import 'package:blixou_lib/blixou_lib.dart';
import 'package:blixou_lib/blixou_lib_platform_interface.dart';
import 'package:blixou_lib/blixou_lib_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockBlixouLibPlatform
    with MockPlatformInterfaceMixin
    implements BlixouLibPlatform {
  @override
  Future<String?> getPlatformVersion() => Future.value('42');

  @override
  Future<bool> applyToTrack(String trackId, {double intensity = 0.5}) => Future.value(true);

  @override
  Future<bool> setIntensity(double intensity) => Future.value(true);
}

void main() {
  final BlixouLibPlatform initialPlatform = BlixouLibPlatform.instance;

  test('$MethodChannelBlixouLib is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelBlixouLib>());
  });

  test('getPlatformVersion', () async {
    BlixouLib blixouLibPlugin = BlixouLib();
    MockBlixouLibPlatform fakePlatform = MockBlixouLibPlatform();
    BlixouLibPlatform.instance = fakePlatform;

    expect(await blixouLibPlugin.getPlatformVersion(), '42');
  });
}
