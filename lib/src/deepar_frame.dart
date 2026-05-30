import 'dart:typed_data';

/// Represents a single AR-processed frame from the DeepAR engine.
///
/// On Android, frames are in RGBA format. On iOS, frames are in BGRA format.
/// The [format] field indicates which format was used.
class DeepARFrame {
  /// Raw pixel data as bytes.
  final Uint8List data;

  /// Width of the frame in pixels.
  final int width;

  /// Height of the frame in pixels.
  final int height;

  /// Pixel format: 'rgba' (Android) or 'bgra' (iOS).
  final String format;

  /// Timestamp in milliseconds since epoch.
  final int timestamp;

  const DeepARFrame({
    required this.data,
    required this.width,
    required this.height,
    required this.format,
    required this.timestamp,
  });

  /// Creates a [DeepARFrame] from a raw map received via EventChannel.
  factory DeepARFrame.fromMap(Map<dynamic, dynamic> map) {
    final rawData = map['data'];
    final Uint8List frameData;
    if (rawData is Uint8List) {
      frameData = rawData;
    } else if (rawData is List) {
      frameData = Uint8List.fromList(rawData.cast<int>());
    } else {
      frameData = Uint8List(0);
    }
    return DeepARFrame(
      data: frameData,
      width: map['width'] as int,
      height: map['height'] as int,
      format: map['format'] as String? ?? 'rgba',
      timestamp: map['timestamp'] as int,
    );
  }
}
