import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../api/api_client.dart';
import 'barcode_scanner.dart';

/// Runs a return / cancel request. When the server answers APPROVAL_REQUIRED (every return of a cashier / seller),
/// asks for a manager's PIN (or a scan of the manager card) and sends the same request again with it.
/// Returns the server's answer, or null when the PIN dialog was closed.
Future<T?> withReturnApproval<T>(BuildContext context, Future<T> Function(String? pin) send) async {
  try {
    return await send(null);
  } on ApiException catch (e) {
    if (e.code != 'APPROVAL_REQUIRED' || !context.mounted) rethrow;
    return showDialog<T>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _PinDialog<T>(message: e.message, send: send),
    );
  }
}

class _PinDialog<T> extends StatefulWidget {
  const _PinDialog({required this.message, required this.send});

  final String message;
  final Future<T> Function(String? pin) send;

  @override
  State<_PinDialog<T>> createState() => _PinDialogState<T>();
}

class _PinDialogState<T> extends State<_PinDialog<T>> {
  final _pin = TextEditingController();
  String? _error;
  bool _busy = false;
  bool _locked = false;

  @override
  void dispose() {
    _pin.dispose();
    super.dispose();
  }

  Future<void> _scanCard() async {
    final code = await scanBarcode(context);
    if (code == null || code.isEmpty || !mounted) return;
    await _submit(code);
  }

  Future<void> _submit([String? code]) async {
    final value = code ?? _pin.text;
    if (value.length < 4 || _busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final res = await widget.send(value);
      if (mounted) Navigator.of(context).pop(res);
    } catch (e) {
      if (!mounted) return;
      _pin.clear();
      setState(() {
        _error = e is ApiException ? e.message : 'Təsdiqlənmədi';
        _locked = e is ApiException && e.code == 'APPROVAL_LOCKED';
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text('Menecer təsdiqi'),
        content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(widget.message),
          const SizedBox(height: 16),
          TextField(
            controller: _pin,
            enabled: !_locked,
            autofocus: true,
            obscureText: true,
            keyboardType: TextInputType.number,
            maxLength: 6,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 24, letterSpacing: 12),
            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
            decoration: const InputDecoration(labelText: 'Menecer PIN-i', counterText: ''),
            onChanged: (_) => setState(() {}),
            onSubmitted: (_) => _submit(),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: _busy || _locked ? null : _scanCard,
            icon: const Icon(Icons.qr_code_scanner),
            label: const Text('Menecer kartını skan et'),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: const TextStyle(color: Colors.red)),
          ],
        ]),
        actions: [
          TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('İmtina')),
          FilledButton(
            onPressed: _busy || _locked || _pin.text.length < 4 ? null : () => _submit(),
            child: _busy
                ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Təsdiq et'),
          ),
        ],
      );
}
