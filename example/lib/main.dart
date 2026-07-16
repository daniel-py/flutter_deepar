import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_deepar/flutter_deepar.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'DeepAR Example',
      theme: ThemeData(
        colorSchemeSeed: Colors.deepPurple,
        useMaterial3: true,
        brightness: Brightness.dark,
      ),
      home: const DeepARExamplePage(),
    );
  }
}

class DeepARExamplePage extends StatefulWidget {
  const DeepARExamplePage({super.key});

  @override
  State<DeepARExamplePage> createState() => _DeepARExamplePageState();
}

class _DeepARExamplePageState extends State<DeepARExamplePage> {
  final DeepARController _deepar = DeepARController();
  bool _isInitialized = false;
  bool _isCapturing = false;
  String _currentEffect = 'None';
  int _frameCount = 0;
  String _frameInfo = 'No frames yet';

  // Replace with your DeepAR license keys
  static const String _androidKey = 'YOUR_ANDROID_LICENSE_KEY';
  static const String _iosKey = 'YOUR_IOS_LICENSE_KEY';

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    final key = Platform.isIOS ? _iosKey : _androidKey;
    final success = await _deepar.initialize(licenseKey: key);
    setState(() => _isInitialized = success);

    // Listen for frames
    _deepar.frameStream.listen((frame) {
      _frameCount++;
      if (_frameCount == 1 || _frameCount % 100 == 0) {
        setState(() {
          _frameInfo =
              'Frame #$_frameCount: ${frame.width}x${frame.height} (${frame.format})';
        });
      }
    });
  }

  Future<void> _toggleCapture() async {
    if (_isCapturing) {
      await _deepar.stopCapture();
    } else {
      await _deepar.startCapture();
    }
    setState(() => _isCapturing = !_isCapturing);
  }

  Future<void> _loadEffect(String name) async {
    if (name == 'None') {
      await _deepar.clearEffect();
    } else {
      await _deepar.loadEffect('effects/$name.deepar');
    }
    setState(() => _currentEffect = name);
  }

  @override
  void dispose() {
    _deepar.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('DeepAR Example')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status card
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Status',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    _statusRow('Initialized', _isInitialized),
                    _statusRow('Capturing', _isCapturing),
                    _statusRow('Effect', false, label2: _currentEffect),
                    const SizedBox(height: 8),
                    Text(_frameInfo,
                        style: Theme.of(context).textTheme.bodySmall),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),

            // Controls
            FilledButton.icon(
              onPressed: _isInitialized ? _toggleCapture : null,
              icon: Icon(_isCapturing ? Icons.stop : Icons.play_arrow),
              label: Text(_isCapturing ? 'Stop Capture' : 'Start Capture'),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: _isCapturing ? () => _deepar.switchCamera() : null,
              icon: const Icon(Icons.cameraswitch),
              label: const Text('Switch Camera'),
            ),
            const SizedBox(height: 24),

            // Effect buttons
            Text('Effects', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: ['None', 'burning_effect', 'beauty', 'glasses']
                  .map(
                    (name) => ChoiceChip(
                      label: Text(name),
                      selected: _currentEffect == name,
                      onSelected: _isCapturing ? (_) => _loadEffect(name) : null,
                    ),
                  )
                  .toList(),
            ),
          ],
        ),
      ),
    );
  }

  Widget _statusRow(String label, bool active, {String? label2}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Icon(
            active ? Icons.check_circle : Icons.cancel,
            size: 16,
            color: active ? Colors.green : Colors.grey,
          ),
          const SizedBox(width: 8),
          Text(label2 ?? '$label: ${active ? "Yes" : "No"}'),
        ],
      ),
    );
  }
}
