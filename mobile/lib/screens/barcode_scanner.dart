import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../api/api_client.dart';
import '../state/session.dart';

/// Opens the camera and returns the first barcode read, or null if the user closes it.
Future<String?> scanBarcode(BuildContext context) =>
    Navigator.of(context).push<String>(MaterialPageRoute(fullscreenDialog: true, builder: (_) => const BarcodeScannerScreen()));

/// Looks a scanned code up among the store's active products (barcode or product code); null when there is none.
Future<Map<String, dynamic>?> productByBarcode(Session session, String code) async {
  try {
    return await session.api.get('/api/products/by-code/${Uri.encodeComponent(code)}') as Map<String, dynamic>;
  } on ApiException catch (e) {
    if (e.status == 404) return null;
    rethrow;
  }
}

/// Camera barcode scanner. Without [onCode] it closes on the first code and returns it.
/// With [onCode] it keeps scanning (several items in a row) and shows the message [onCode] returns.
class BarcodeScannerScreen extends StatefulWidget {
  const BarcodeScannerScreen({super.key, this.onCode, this.title = 'Barkodu skan edin'});

  final Future<String?> Function(String code)? onCode;
  final String title;

  @override
  State<BarcodeScannerScreen> createState() => _BarcodeScannerScreenState();
}

class _BarcodeScannerScreenState extends State<BarcodeScannerScreen> {
  final _controller = MobileScannerController(detectionSpeed: DetectionSpeed.normal);
  String? _last;
  DateTime _lastAt = DateTime(2000);
  bool _handling = false;
  bool _done = false;
  String? _message;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _detect(BarcodeCapture capture) async {
    if (_handling || _done) return;
    final code = capture.barcodes.map((b) => b.rawValue?.trim()).firstWhere((v) => v != null && v.isNotEmpty, orElse: () => null);
    if (code == null) return;
    // The camera reads the same label many times per second: ignore repeats for a moment.
    final now = DateTime.now();
    if (code == _last && now.difference(_lastAt) < const Duration(seconds: 2)) return;
    _last = code;
    _lastAt = now;
    HapticFeedback.mediumImpact();
    SystemSound.play(SystemSoundType.click);
    if (widget.onCode == null) {
      _done = true;
      Navigator.of(context).pop(code);
      return;
    }
    _handling = true;
    try {
      final msg = await widget.onCode!(code);
      if (mounted) setState(() => _message = msg);
    } finally {
      _lastAt = DateTime.now();
      _handling = false;
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        backgroundColor: Colors.black,
        appBar: AppBar(
          title: Text(widget.title),
          actions: [
            IconButton(tooltip: 'Fənər', icon: const Icon(Icons.flashlight_on_outlined), onPressed: () => _controller.toggleTorch()),
            IconButton(tooltip: 'Kameranı dəyiş', icon: const Icon(Icons.cameraswitch_outlined), onPressed: () => _controller.switchCamera()),
          ],
        ),
        body: Stack(children: [
          MobileScanner(
            controller: _controller,
            onDetect: _detect,
            errorBuilder: (context, error) => Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text(
                  error.errorCode == MobileScannerErrorCode.permissionDenied
                      ? 'Kameraya icazə verilməyib. Telefonun Ayarlar → Tətbiqlər → HesabPro → İcazələr bölməsində kameranı aktiv edin.'
                      : 'Kamera açılmadı: ${error.errorDetails?.message ?? error.errorCode.name}',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white),
                ),
              ),
            ),
          ),
          Center(
            child: Container(
              width: 280,
              height: 160,
              decoration: BoxDecoration(border: Border.all(color: Colors.white, width: 2), borderRadius: BorderRadius.circular(12)),
            ),
          ),
          Positioned(
            left: 16,
            right: 16,
            bottom: 32,
            child: Column(children: [
              if (_message != null)
                Container(
                  width: double.infinity,
                  margin: const EdgeInsets.only(bottom: 12),
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(10)),
                  child: Text(_message!, textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w600)),
                ),
              Text('Barkodu çərçivənin içinə tutun', style: TextStyle(color: Colors.white.withValues(alpha: 0.85))),
              if (widget.onCode != null) ...[
                const SizedBox(height: 12),
                FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Hazırdır')),
              ],
            ]),
          ),
        ]),
      );
}
