import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:pdf/pdf.dart';
import 'package:printing/printing.dart';

import 'session.dart';

/// Receipt (çek) printer settings for sales, kept on this device:
/// - [autoPrint]: print the receipt right after every sale;
/// - [printerUrl]/[printerName]: the chosen printer where the platform can print without a dialog (iOS, Windows,
///   macOS). On Android the system print screen opens with the last used printer already selected — one tap prints.
class ReceiptPrinter {
  ReceiptPrinter._();

  static final instance = ReceiptPrinter._();
  static const _storage = FlutterSecureStorage();

  bool autoPrint = false;
  String? printerUrl;
  String? printerName;
  bool _loaded = false;

  Future<void> load() async {
    if (_loaded) return;
    try {
      autoPrint = await _storage.read(key: 'receipt_auto_print') == 'true';
      printerUrl = await _storage.read(key: 'receipt_printer_url');
      printerName = await _storage.read(key: 'receipt_printer_name');
    } catch (_) {}
    _loaded = true;
  }

  Future<void> save() async {
    try {
      await _storage.write(key: 'receipt_auto_print', value: '$autoPrint');
      if (printerUrl == null) {
        await _storage.delete(key: 'receipt_printer_url');
        await _storage.delete(key: 'receipt_printer_name');
      } else {
        await _storage.write(key: 'receipt_printer_url', value: printerUrl);
        await _storage.write(key: 'receipt_printer_name', value: printerName ?? '');
      }
    } catch (_) {}
  }

  /// Whether this platform can print straight to a saved printer (no dialog).
  Future<bool> canDirectPrint() async {
    try {
      return (await Printing.info()).directPrint;
    } catch (_) {
      return false;
    }
  }

  /// Prints a sale receipt (80 mm roll PDF from the server).
  Future<void> printSale(Session session, Map<String, dynamic> sale) async {
    final bytes = Uint8List.fromList(await session.api.getBytes('/api/sales/${sale['id']}/receipt'));
    await printPdf(bytes, 'Çek ${sale['number']}');
  }

  Future<void> printPdf(Uint8List bytes, String name) async {
    await load();
    if (printerUrl != null && await canDirectPrint()) {
      final ok = await Printing.directPrintPdf(
        printer: Printer(url: printerUrl!, name: printerName),
        onLayout: (_) async => bytes,
        name: name,
        format: PdfPageFormat.roll80,
      );
      if (ok) return;
    }
    await Printing.layoutPdf(onLayout: (_) async => bytes, name: name, format: PdfPageFormat.roll80, dynamicLayout: false);
  }
}

/// Settings sheet: automatic receipt printing after a sale, the printer, and a test print of the last receipt.
Future<void> showReceiptPrinterSheet(BuildContext context, Session session, {Map<String, dynamic>? lastSale}) =>
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => _ReceiptPrinterSheet(session: session, lastSale: lastSale),
    );

class _ReceiptPrinterSheet extends StatefulWidget {
  const _ReceiptPrinterSheet({required this.session, this.lastSale});

  final Session session;
  final Map<String, dynamic>? lastSale;

  @override
  State<_ReceiptPrinterSheet> createState() => _ReceiptPrinterSheetState();
}

class _ReceiptPrinterSheetState extends State<_ReceiptPrinterSheet> {
  final _p = ReceiptPrinter.instance;
  bool _direct = false;
  bool _ready = false;

  @override
  void initState() {
    super.initState();
    () async {
      await _p.load();
      final direct = await _p.canDirectPrint();
      if (mounted) {
        setState(() {
          _direct = direct;
          _ready = true;
        });
      }
    }();
  }

  Future<void> _pick() async {
    final printer = await Printing.pickPrinter(context: context, title: 'Çek printeri');
    if (printer == null || !mounted) return;
    setState(() {
      _p.printerUrl = printer.url;
      _p.printerName = printer.name;
    });
    await _p.save();
  }

  Future<void> _test() async {
    try {
      if (widget.lastSale != null) {
        await _p.printSale(widget.session, widget.lastSale!);
      }
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Çap alınmadı: $e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_ready) return const Padding(padding: EdgeInsets.all(32), child: Center(child: CircularProgressIndicator()));
    return ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(20, 0, 20, 24), children: [
      const Text('Çek printeri', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
      const SizedBox(height: 8),
      SwitchListTile(
        contentPadding: EdgeInsets.zero,
        title: const Text('Satışdan sonra çeki avtomatik çap et'),
        subtitle: const Text('Satış tamamlananda çek dərhal printerə göndərilir'),
        value: _p.autoPrint,
        onChanged: (v) async {
          setState(() => _p.autoPrint = v);
          await _p.save();
        },
      ),
      const Divider(),
      if (_direct)
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.print_outlined),
          title: Text(_p.printerName?.isNotEmpty == true ? _p.printerName! : 'Printer seçilməyib'),
          subtitle: const Text('Seçilmiş printerə çek pəncərəsiz çap olunur'),
          trailing: TextButton(onPressed: _pick, child: const Text('Seç')),
        )
      else
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(color: Colors.blueGrey.shade50, borderRadius: BorderRadius.circular(12)),
          child: const Text(
            'Android-də çek telefonun çap xidməti ilə çap olunur: çap ekranı açılır, son seçdiyiniz printer artıq seçili '
            'olur — "Çap et" düyməsini basın. Bluetooth çek printeri üçün printerin öz çap xidmətini (məs. RawBT) '
            'quraşdırıb bir dəfə seçin; sonra həmişə o seçili qalır. Çek 80 mm rulon ölçüsündədir.',
          ),
        ),
      if (widget.lastSale != null) ...[
        const SizedBox(height: 16),
        OutlinedButton.icon(
          onPressed: _test,
          icon: const Icon(Icons.receipt_long_outlined),
          label: Text('Son çeki çap et (${widget.lastSale!['number']})'),
        ),
      ],
    ]);
  }
}
