import 'package:flutter/material.dart';
import 'dart:async';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:blixou_lib/blixou_lib.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Blixou Face Beauty Demo',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        primaryColor: const Color(0xFFFF4081),
        scaffoldBackgroundColor: const Color(0xFF121212),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFFFF4081),
          secondary: Color(0xFF00E5FF),
          surface: Color(0xFF1E1E1E),
        ),
        useMaterial3: true,
      ),
      home: const BeautyDemoScreen(),
    );
  }
}

class BeautyDemoScreen extends StatefulWidget {
  const BeautyDemoScreen({super.key});

  @override
  State<BeautyDemoScreen> createState() => _BeautyDemoScreenState();
}

class _BeautyDemoScreenState extends State<BeautyDemoScreen> {
  final _blixouLib = BlixouLib();
  final _localRenderer = RTCVideoRenderer();
  
  MediaStream? _localStream;
  MediaStreamTrack? _localVideoTrack;
  
  bool _isCameraInitialized = false;
  bool _isBeautyFilterEnabled = false;
  double _beautyIntensity = 0.5;
  String _statusMessage = 'Initializing Camera...';

  @override
  void initState() {
    super.initState();
    _initCamera();
  }

  @override
  void dispose() {
    _localRenderer.dispose();
    _localStream?.dispose();
    super.dispose();
  }

  Future<void> _initCamera() async {
    try {
      await _localRenderer.initialize();

      // Configure media constraints for high performance (720p at 30 FPS)
      final mediaConstraints = {
        'audio': false,
        'video': {
          'facingMode': 'user', // Front camera
          'width': {'ideal': 1280},
          'height': {'ideal': 720},
          'frameRate': {'ideal': 30},
        }
      };

      final stream = await navigator.mediaDevices.getUserMedia(mediaConstraints);
      _localStream = stream;
      _localRenderer.srcObject = stream;

      if (stream.getVideoTracks().isNotEmpty) {
        _localVideoTrack = stream.getVideoTracks().first;
      }

      setState(() {
        _isCameraInitialized = true;
        _statusMessage = 'Camera ready';
      });
    } catch (e) {
      setState(() {
        _statusMessage = 'Failed to open camera: $e';
      });
    }
  }

  Future<void> _toggleBeautyFilter(bool enabled) async {
    if (_localVideoTrack == null) return;
    
    try {
      if (enabled) {
        // Apply the beauty filter to the WebRTC video track ID
        final success = await _blixouLib.applyToTrack(
          _localVideoTrack!.id!,
          intensity: _beautyIntensity,
        );
        if (success) {
          setState(() {
            _isBeautyFilterEnabled = true;
            _statusMessage = 'Beauty Filter Active';
          });
        } else {
          setState(() {
            _statusMessage = 'Failed to bind VideoProcessor';
          });
        }
      } else {
        // Disabling filter is achieved by setting intensity to 0
        await _blixouLib.setIntensity(0.0);
        setState(() {
          _isBeautyFilterEnabled = false;
          _statusMessage = 'Beauty Filter Disabled';
        });
      }
    } catch (e) {
      setState(() {
        _statusMessage = 'Error: $e';
      });
    }
  }

  Future<void> _updateIntensity(double value) async {
    setState(() {
      _beautyIntensity = value;
    });
    if (_isBeautyFilterEnabled) {
      await _blixouLib.setIntensity(value);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Blixou Beauty Filter',
          style: TextStyle(fontWeight: FontWeight.bold, letterSpacing: 0.5),
        ),
        backgroundColor: const Color(0xFF1E1E1E),
        centerTitle: true,
        elevation: 2,
      ),
      body: Column(
        children: [
          // Live Video Preview Container
          Expanded(
            child: Container(
              margin: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.black,
                borderRadius: BorderRadius.circular(20),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withOpacity(0.5),
                    blurRadius: 10,
                    offset: const Offset(0, 5),
                  )
                ],
              ),
              clipBehavior: Clip.antiAlias,
              child: Stack(
                children: [
                  if (_isCameraInitialized)
                    RTCVideoView(
                      _localRenderer,
                      mirror: true,
                      objectFit: RTCVideoViewObjectFit.RTCVideoViewObjectFitCover,
                    )
                  else
                    const Center(
                      child: CircularProgressIndicator(
                        color: Color(0xFFFF4081),
                      ),
                    ),
                  
                  // Status Info Tag Overlay
                  Positioned(
                    top: 16,
                    left: 16,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                      decoration: BoxDecoration(
                        color: Colors.black.withOpacity(0.6),
                        borderRadius: BorderRadius.circular(12),
                        border: Border.all(color: Colors.white10),
                      ),
                      child: Row(
                        children: [
                          Container(
                            width: 8,
                            height: 8,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: _isBeautyFilterEnabled
                                  ? const Color(0xFF00E5FF)
                                  : Colors.grey,
                            ),
                          ),
                          const SizedBox(width: 8),
                          Text(
                            _statusMessage,
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 12,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),

                  // Resolution Tag Overlay
                  if (_isCameraInitialized)
                    Positioned(
                      top: 16,
                      right: 16,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                        decoration: BoxDecoration(
                          color: Colors.black.withOpacity(0.6),
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(color: Colors.white10),
                        ),
                        child: const Text(
                          '720p @ 30 FPS',
                          style: TextStyle(
                            color: Colors.white70,
                            fontSize: 11,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),

          // Control Panel Container
          Container(
            padding: const EdgeInsets.all(24),
            decoration: const BoxDecoration(
              color: Color(0xFF1E1E1E),
              borderRadius: BorderRadius.vertical(top: Radius.circular(30)),
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // Filter Toggle Row
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Face Skin Smoothing',
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        SizedBox(height: 2),
                        Text(
                          'GPU Bilateral + Face Tracking',
                          style: TextStyle(
                            color: Colors.white54,
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ),
                    Switch(
                      value: _isBeautyFilterEnabled,
                      activeColor: const Color(0xFFFF4081),
                      onChanged: _isCameraInitialized ? _toggleBeautyFilter : null,
                    ),
                  ],
                ),
                const SizedBox(height: 24),

                // Intensity Slider Row
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text(
                          'Smoothing Intensity',
                          style: TextStyle(
                            color: Colors.white70,
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                        Text(
                          '${(_beautyIntensity * 100).toInt()}%',
                          style: const TextStyle(
                            color: Color(0xFFFF4081),
                            fontSize: 14,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    SliderTheme(
                      data: SliderTheme.of(context).copyWith(
                        activeTrackColor: const Color(0xFFFF4081),
                        inactiveTrackColor: Colors.white10,
                        thumbColor: const Color(0xFFFF4081),
                        overlayColor: const Color(0xFFFF4081).withAlpha(32),
                        valueIndicatorColor: const Color(0xFFFF4081),
                        trackHeight: 4,
                      ),
                      child: Slider(
                        value: _beautyIntensity,
                        min: 0.0,
                        max: 1.0,
                        onChanged: _isBeautyFilterEnabled ? _updateIntensity : null,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
