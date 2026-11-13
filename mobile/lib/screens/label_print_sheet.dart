import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';

import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

/// Barcode label printing: choose a label size and how many, then the PDF (one label per page, page = label size)
/// goes to the share sheet, where "Çap et" / the label printer's app prints it.
/// With [product] it prints that product; without, every active product.
Future<void> showLabelPrintSheet(BuildContext context, Session session, {Map<String, dynamic>? product}) =>
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
        child: _LabelSheet(session: session, product: product),
      ),
    );

class _LabelSheet extends StatefulWidget {
  const _LabelSheet({required this.session, this.product});

  final Session session;
  final Map<String, dynamic>? product;

  @override
  State<_LabelSheet> createState() => _LabelSheetState();
}

class _LabelSheetState extends State<_LabelSheet> {
  static const _sizes = [
    (key: 'small', title: 'Balaca', mm: '40 × 25 mm', note: 'Ad, barkod, qiymət', w: 40.0, h: 25.0),
    (key: 'medium', title: 'Orta', mm: '58 × 40 mm', note: 'Mağaza, ad, barkod, böyük qiymət', w: 58.0, h: 40.0),
    (key: 'large', title: 'Böyük', mm: '100 × 50 mm', note: 'Mağaza, ad, kod, böyük barkod və qiymət', w: 100.0, h: 50.0),
  ];

  String _size = 'medium';
  int _copies = 1;
  bool _perStock = false;
  bool _busy = false;

  bool get _single => widget.product != null;

  Future<void> _print() async {
    setState(() => _busy = true);
    try {
      final q = <String>[
        'size=$_size',
        if (_single) 'ids=${widget.product!['id']}',
        if (_perStock) 'perStock=true' else 'copies=$_copies',
      ].join('&');
      final bytes = await widget.session.api.getBytes('/api/products/labels?$q');
      final name = _single ? 'barkod-${widget.product!['code']}.pdf' : 'barkodlar.pdf';
      await SharePlus.instance.share(ShareParams(
        files: [XFile.fromData(Uint8List.fromList(bytes), mimeType: 'application/pdf', name: name)],
        fileNameOverrides: [name],
      ));
      if (mounted) Navigator.pop(context);
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(20, 0, 20, 24), children: [
      Text(_single ? 'Barkod çap et' : 'Bütün barkodları çap et',
          style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: navy)),
      const SizedBox(height: 4),
      Text(_single ? '${widget.product!['name']}' : 'Bütün aktiv məhsullar', style: TextStyle(color: Colors.grey.shade700)),
      const SizedBox(height: 16),
      Text('ETİKET ÖLÇÜSÜ', style: TextStyle(fontSize: 12, letterSpacing: 0.8, fontWeight: FontWeight.w700, color: Colors.grey.shade600)),
      const SizedBox(height: 8),
      for (final s in _sizes)
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Material(
            color: _size == s.key ? gold.withValues(alpha: 0.12) : Colors.white,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(14),
              side: BorderSide(color: _size == s.key ? gold : Colors.grey.shade300, width: _size == s.key ? 2 : 1),
            ),
            child: InkWell(
              borderRadius: BorderRadius.circular(14),
              onTap: () => setState(() => _size = s.key),
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Row(children: [
                  // label shape to scale
                  SizedBox(
                    width: 64,
                    height: 40,
                    child: Center(
                      child: Container(
                        width: s.w * 0.6,
                        height: s.h * 0.6,
                        decoration: BoxDecoration(border: Border.all(color: navy, width: 1.5), borderRadius: BorderRadius.circular(3)),
                        child: const Icon(Icons.barcode_reader, size: 14, color: navy),
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text('${s.title} · ${s.mm}', style: const TextStyle(fontWeight: FontWeight.w700, color: navy)),
                      Text(s.note, style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
                    ]),
                  ),
                  Icon(_size == s.key ? Icons.radio_button_checked : Icons.radio_button_off, color: _size == s.key ? gold : Colors.grey),
                ]),
              ),
            ),
          ),
        ),
      const SizedBox(height: 8),
      Text('SAY', style: TextStyle(fontSize: 12, letterSpacing: 0.8, fontWeight: FontWeight.w700, color: Colors.grey.shade600)),
      SwitchListTile(
        contentPadding: EdgeInsets.zero,
        title: const Text('Qalıq qədər'),
        subtitle: Text(_single
            ? 'Anbarda neçə ədəd varsa, o qədər etiket'
            : 'Hər məhsuldan anbardakı sayı qədər etiket'),
        value: _perStock,
        onChanged: (v) => setState(() => _perStock = v),
      ),
      if (!_perStock)
        Row(children: [
          Expanded(child: Text(_single ? 'Nüsxə sayı' : 'Hər məhsuldan', style: const TextStyle(fontSize: 16))),
          IconButton.outlined(
            onPressed: _copies > 1 ? () => setState(() => _copies--) : null,
            icon: const Icon(Icons.remove),
          ),
          SizedBox(
            width: 52,
            child: Text('$_copies', textAlign: TextAlign.center, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          ),
          IconButton.outlined(
            onPressed: _copies < 500 ? () => setState(() => _copies++) : null,
            icon: const Icon(Icons.add),
          ),
        ]),
      const SizedBox(height: 20),
      SizedBox(
        height: 52,
        child: FilledButton.icon(
          onPressed: _busy ? null : _print,
          icon: _busy
              ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : const Icon(Icons.print_outlined),
          label: const Text('Çap et', style: TextStyle(fontSize: 16)),
        ),
      ),
      const SizedBox(height: 8),
      Text('Açılan pəncərədə "Çap et" (Print) və ya barkod printerinin tətbiqini seçin. Printerdə kağız ölçüsünü etiket ölçüsünə uyğun qoyun.',
          style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
    ]);
  }
}
