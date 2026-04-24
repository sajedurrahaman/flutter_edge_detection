/// A Flutter plugin for real-time edge detection and document scanning
/// with advanced image processing capabilities.
library flutter_edge_detection;

import 'package:flutter/services.dart'
    show MethodChannel, PlatformException;

/// Provides static methods for performing edge detection and document scanning.
///
/// Use [detectEdge] to launch a live camera scanner and [detectEdgeFromGallery]
/// to process an image selected from the device gallery.
class FlutterEdgeDetection {
  static const MethodChannel _channel = MethodChannel('flutter_edge_detection');

  /// Full scan session output from the native scanner.
  ///
  /// - [imagePaths]: saved, cropped images (multi-page)
  /// - [pdfPath]: imported PDF file (if user used "Import File")
  const FlutterEdgeDetection._();

  static Future<EdgeDetectionSessionResult?> detectEdgeSession(
    String saveTo, {
    bool canUseGallery = true,
    bool androidAutoCapture = true,
    String androidScanTitle = 'Scanning',
    String androidCropTitle = 'Crop',
    String androidCropBlackWhiteTitle = 'Black White',
    String androidCropReset = 'Reset',
    String? initialMode,
  }) async {
    final dynamic raw = await _channel.invokeMethod('edge_detect', {
      'save_to': saveTo,
      'can_use_gallery': canUseGallery,
      'auto_capture': androidAutoCapture,
      'scan_title': androidScanTitle,
      'crop_title': androidCropTitle,
      'crop_black_white_title': androidCropBlackWhiteTitle,
      'crop_reset_title': androidCropReset,
      'initial_mode': initialMode,
    });

    if (raw == null) return null;
    if (raw is bool) return raw ? const EdgeDetectionSessionResult() : null;

    if (raw is Map) {
      final uris =
          (raw['uris'] as List?)?.whereType<String>().toList() ??
          const <String>[];
      final pdfPath = raw['pdf_path'] as String?;
      final mode = raw['mode'] as String?;
      final openCrop = raw['open_crop'] as bool? ?? false;
      return EdgeDetectionSessionResult(
        imagePaths: uris,
        pdfPath: pdfPath,
        mode: mode,
        openCrop: openCrop,
      );
    }

    return null;
  }

  /// Scans an object using the device camera with edge detection.
  ///
  /// The cropped image is written to [saveTo], which must be a writable,
  /// absolute file path (for example, a path inside
  /// `getApplicationSupportDirectory()`).
  ///
  /// The [canUseGallery] flag determines whether the user can switch to the
  /// gallery from the camera UI.
  ///
  /// On Android, you can customize the scan UI by providing localized titles
  /// for [androidScanTitle], [androidCropTitle],
  /// [androidCropBlackWhiteTitle], and [androidCropReset].
  ///
  /// Returns `true` if the operation was successful and the image was saved,
  /// or `false` if the user cancelled the flow.
  ///
  /// [androidAutoCapture] (Android only) when `true`, the scanner uses
  /// auto-capture when a document is detected; when `false`, capture is manual only.
  ///
  /// Throws an [EdgeDetectionException] if the underlying platform call fails.
  static Future<bool> detectEdge(
    String saveTo, {
    bool canUseGallery = true,
    bool androidAutoCapture = true,
    String androidScanTitle = 'Scanning',
    String androidCropTitle = 'Crop',
    String androidCropBlackWhiteTitle = 'Black White',
    String androidCropReset = 'Reset',
  }) async {
    try {
      final session = await detectEdgeSession(
        saveTo,
        canUseGallery: canUseGallery,
        androidAutoCapture: androidAutoCapture,
        androidScanTitle: androidScanTitle,
        androidCropTitle: androidCropTitle,
        androidCropBlackWhiteTitle: androidCropBlackWhiteTitle,
        androidCropReset: androidCropReset,
      );
      // Backwards-compatible: old callers only need "success" boolean.
      return session != null &&
          (session.imagePaths.isNotEmpty || session.pdfPath != null);
    } on PlatformException catch (e) {
      throw EdgeDetectionException(
        code: e.code,
        message: e.message ?? 'Unknown error occurred',
        details: e.details,
      );
    }
  }

  /// Scans an object from an existing gallery image with edge detection.
  ///
  /// The cropped image is written to [saveTo], which must be a writable,
  /// absolute file path (for example, a path inside
  /// `getApplicationSupportDirectory()`).
  ///
  /// On Android, you can customize the crop UI by providing localized titles
  /// for [androidCropTitle], [androidCropBlackWhiteTitle], and
  /// [androidCropReset].
  ///
  /// Returns `true` if the operation was successful and the image was saved,
  /// or `false` if the user cancelled the flow.
  ///
  /// Throws an [EdgeDetectionException] if the underlying platform call fails.
  static Future<bool> detectEdgeFromGallery(
    String saveTo, {
    String androidCropTitle = 'Crop',
    String androidCropBlackWhiteTitle = 'Black White',
    String androidCropReset = 'Reset',
  }) async {
    try {
      final dynamic raw = await _channel.invokeMethod('edge_detect_gallery', {
        'save_to': saveTo,
        'crop_title': androidCropTitle,
        'crop_black_white_title': androidCropBlackWhiteTitle,
        'crop_reset_title': androidCropReset,
        'from_gallery': true,
      });
      if (raw is bool) return raw;
      if (raw is Map) {
        final uris =
            (raw['uris'] as List?)?.whereType<String>().toList() ??
            const <String>[];
        final pdfPath = raw['pdf_path'] as String?;
        return uris.isNotEmpty || pdfPath != null;
      }
      return false;
    } on PlatformException catch (e) {
      throw EdgeDetectionException(
        code: e.code,
        message: e.message ?? 'Unknown error occurred',
        details: e.details,
      );
    }
  }
}

class EdgeDetectionSessionResult {
  final List<String> imagePaths;
  final String? pdfPath;
  final String? mode;
  final bool openCrop;

  const EdgeDetectionSessionResult({
    this.imagePaths = const <String>[],
    this.pdfPath,
    this.mode,
    this.openCrop = false,
  });
}

/// Exception thrown when edge detection operations fail.
class EdgeDetectionException implements Exception {
  /// The error code returned by the platform implementation.
  final String code;

  /// A human-readable description of the error.
  final String message;

  /// Additional platform-specific error details, if available.
  final dynamic details;

  /// Creates a new [EdgeDetectionException].
  const EdgeDetectionException({
    required this.code,
    required this.message,
    this.details,
  });

  @override
  String toString() => 'EdgeDetectionException($code, $message)';
}
