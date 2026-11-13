import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:share_plus/share_plus.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

/// Barcode label printing and label designer: size (presets or your own in mm), barcode / text scale, which parts
/// to show, with a live preview. The PDF (one label per page, page = label size) goes to the share sheet, where
/// "Print" / the label printer's app prints it. The last design is remembered on the phone.
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

double _num(Object? v) => (v as num?)?.toDouble() ?? 0;

/// Dialog for the sale price and the price before a discount; saves the product and returns it (null = cancelled).
Future<Map<String, dynamic>?> editProductPrice(BuildContext context, Session session, Map<String, dynamic> p) async {
  final sale = TextEditingController(text: _num(p['salePrice']).toStringAsFixed(2));
  final old = TextEditingController(text: p['oldPrice'] == null ? '' : _num(p['oldPrice']).toStringAsFixed(2));
  final ok = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('Qiymət və endirim'),
      content: Column(mainAxisSize: MainAxisSize.min, children: [
        TextField(
          controller: old,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(
            labelText: 'Köhnə qiymət (endirimdən əvvəl)',
            suffixText: '₼',
            helperText: 'Etiketdə üstündən xətt çəkilir. Boş = endirim yoxdur',
            helperMaxLines: 2,
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: sale,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Endirim qiyməti (indiki satış qiyməti)', suffixText: '₼'),
        ),
      ]),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Ləğv et')),
        FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Saxla')),
      ],
    ),
  );
  final saleText = sale.text.trim().replaceAll(',', '.');
  final oldText = old.text.trim().replaceAll(',', '.');
  sale.dispose();
  old.dispose();
  if (ok != true || !context.mounted) return null;
  final newSale = double.tryParse(saleText);
  final newOld = oldText.isEmpty ? 0.0 : double.tryParse(oldText);
  if (newSale == null || newOld == null) {
    showError(context, 'Qiyməti düzgün yazın');
    return null;
  }
  if (newOld != 0 && newOld <= newSale) {
    showError(context, 'Köhnə qiymət endirim qiymətindən böyük olmalıdır');
    return null;
  }
  try {
    final saved = await session.api.put('/api/products/${p['id']}', {
      'name': p['name'],
      'code': p['code'],
      'barcode': p['barcode'],
      'categoryId': p['categoryId'],
      'supplierId': p['supplierId'],
      'unit': p['unit'],
      'purchasePrice': _num(p['purchasePrice']).toStringAsFixed(2),
      'salePrice': newSale.toStringAsFixed(2),
      'minStock': '${p['minStock']}',
      'active': p['active'],
      'version': p['version'],
      'oldPrice': newOld.toStringAsFixed(2),
    }) as Map<String, dynamic>;
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(newOld == 0 ? 'Qiymət saxlandı' : 'Endirim: ${formatMoney(newOld)} → ${formatMoney(newSale)}')));
    }
    return saved;
  } catch (e) {
    if (context.mounted) showError(context, e);
    return null;
  }
}

class _Preset {
  const _Preset(this.key, this.title, this.w, this.h, this.show);

  final String key;
  final String title;
  final double w;
  final double h;
  final Set<String> show;
}

const _presets = [
  _Preset('small', 'Balaca', 40, 25, {'name', 'price', 'old'}),
  _Preset('medium', 'Orta', 58, 40, {'store', 'name', 'price', 'old'}),
  _Preset('large', 'Böyük', 100, 50, {'store', 'name', 'code', 'price', 'old'}),
];

const _parts = [
  ('store', 'Mağaza adı'),
  ('name', 'Məhsulun adı'),
  ('code', 'Kod və vahid'),
  ('price', 'Qiymət'),
  ('old', 'Endirim (köhnə qiymət xətli)'),
];

class _LabelSheet extends StatefulWidget {
  const _LabelSheet({required this.session, this.product});

  final Session session;
  final Map<String, dynamic>? product;

  @override
  State<_LabelSheet> createState() => _LabelSheetState();
}

class _LabelSheetState extends State<_LabelSheet> {
  static const _storageKey = 'label_design_v1';
  final _storage = const FlutterSecureStorage();

  String _size = 'medium';
  final _w = TextEditingController(text: '58');
  final _h = TextEditingController(text: '40');
  double _barcode = 100;
  double _font = 100;
  Set<String> _show = {..._presets[1].show};
  int _copies = 1;
  bool _perStock = false;
  bool _busy = false;
  late Map<String, dynamic>? _product = widget.product;

  bool get _single => _product != null;

  @override
  void initState() {
    super.initState();
    _restore();
  }

  @override
  void dispose() {
    _w.dispose();
    _h.dispose();
    super.dispose();
  }

  Future<void> _restore() async {
    try {
      final raw = await _storage.read(key: _storageKey);
      if (raw == null || !mounted) return;
      final j = jsonDecode(raw) as Map<String, dynamic>;
      setState(() {
        _size = j['size'] as String? ?? _size;
        _w.text = '${j['w'] ?? _w.text}';
        _h.text = '${j['h'] ?? _h.text}';
        _barcode = (j['barcode'] as num?)?.toDouble() ?? 100;
        _font = (j['font'] as num?)?.toDouble() ?? 100;
        _show = {...((j['show'] as List?)?.cast<String>() ?? _show)};
      });
    } catch (_) {}
  }

  Future<void> _save() async {
    try {
      await _storage.write(
        key: _storageKey,
        value: jsonEncode({'size': _size, 'w': _w.text, 'h': _h.text, 'barcode': _barcode, 'font': _font, 'show': _show.toList()}),
      );
    } catch (_) {}
  }

  double get _wMm => _size == 'custom' ? (double.tryParse(_w.text.replaceAll(',', '.')) ?? 58) : _presets.firstWhere((p) => p.key == _size).w;

  double get _hMm => _size == 'custom' ? (double.tryParse(_h.text.replaceAll(',', '.')) ?? 40) : _presets.firstWhere((p) => p.key == _size).h;

  void _pick(String key) => setState(() {
        _size = key;
        if (key != 'custom') {
          final p = _presets.firstWhere((p) => p.key == key);
          _show = {...p.show};
          _w.text = p.w.toStringAsFixed(0);
          _h.text = p.h.toStringAsFixed(0);
        }
      });

  Future<void> _print() async {
    final w = _wMm, h = _hMm;
    if (w < 20 || w > 150 || h < 12 || h > 120) {
      showError(context, 'Etiket ölçüsü: en 20–150 mm, hündürlük 12–120 mm');
      return;
    }
    setState(() => _busy = true);
    await _save();
    try {
      final q = <String>[
        'size=custom',
        'w=${w.toStringAsFixed(1)}',
        'h=${h.toStringAsFixed(1)}',
        'barcode=${_barcode.round()}',
        'font=${_font.round()}',
        'show=${_show.join(',')}',
        if (_single) 'ids=${_product!['id']}',
        if (_perStock) 'perStock=true' else 'copies=$_copies',
      ].join('&');
      final bytes = await widget.session.api.getBytes('/api/products/labels?$q');
      final name = _single ? 'barkod-${_product!['code']}.pdf' : 'barkodlar.pdf';
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

  Widget _section(String t) => Padding(
        padding: const EdgeInsets.only(top: 18, bottom: 8),
        child: Text(t.toUpperCase(),
            style: TextStyle(fontSize: 12, letterSpacing: 0.8, fontWeight: FontWeight.w700, color: Colors.grey.shade600)),
      );

  @override
  Widget build(BuildContext context) {
    return ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(20, 0, 20, 24), children: [
      Text(_single ? 'Barkod çap et' : 'Bütün barkodları çap et',
          style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: navy)),
      const SizedBox(height: 4),
      Text(_single ? '${_product!['name']}' : 'Bütün aktiv məhsullar', style: TextStyle(color: Colors.grey.shade700)),
      const SizedBox(height: 14),

      // live preview
      _LabelPreview(
        wMm: _wMm.clamp(20, 150).toDouble(),
        hMm: _hMm.clamp(12, 120).toDouble(),
        barcodePct: _barcode,
        fontPct: _font,
        show: _show,
        store: widget.session.me?.storeName ?? 'Mağaza',
        product: _product,
      ),
      if (_single) ...[
        const SizedBox(height: 10),
        Material(
          color: Colors.white,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12), side: BorderSide(color: Colors.grey.shade300)),
          child: ListTile(
            leading: const Icon(Icons.local_offer_outlined),
            title: Text(_product!['oldPrice'] != null && _num(_product!['oldPrice']) > _num(_product!['salePrice'])
                ? 'Endirim: ${formatMoney(_num(_product!['oldPrice']))} → ${formatMoney(_num(_product!['salePrice']))}'
                : 'Endirim yoxdur · ${formatMoney(_num(_product!['salePrice']))}'),
            subtitle: const Text('Köhnə qiymət etiketdə xətli çıxır'),
            trailing: TextButton(
              onPressed: () async {
                final saved = await editProductPrice(context, widget.session, _product!);
                if (saved != null && mounted) setState(() => _product = saved);
              },
              child: const Text('Dəyiş'),
            ),
          ),
        ),
      ],

      _section('Ölçü'),
      Wrap(spacing: 8, runSpacing: 8, children: [
        for (final p in _presets)
          ChoiceChip(
            label: Text('${p.title} ${p.w.toStringAsFixed(0)}×${p.h.toStringAsFixed(0)}'),
            selected: _size == p.key,
            onSelected: (_) => _pick(p.key),
          ),
        ChoiceChip(label: const Text('Öz ölçüm'), selected: _size == 'custom', onSelected: (_) => _pick('custom')),
      ]),
      if (_size == 'custom') ...[
        const SizedBox(height: 12),
        Row(children: [
          Expanded(
            child: TextField(
              controller: _w,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(labelText: 'En', suffixText: 'mm', helperText: '20–150'),
              onChanged: (_) => setState(() {}),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: TextField(
              controller: _h,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(labelText: 'Hündürlük', suffixText: 'mm', helperText: '12–120'),
              onChanged: (_) => setState(() {}),
            ),
          ),
        ]),
      ],

      _section('Dizayn'),
      Row(children: [
        const SizedBox(width: 110, child: Text('Barkod ölçüsü')),
        Expanded(
          child: Slider(
            value: _barcode,
            min: 30,
            max: 100,
            divisions: 14,
            label: '${_barcode.round()}%',
            onChanged: (v) => setState(() => _barcode = v),
          ),
        ),
        SizedBox(width: 44, child: Text('${_barcode.round()}%', textAlign: TextAlign.right)),
      ]),
      Row(children: [
        const SizedBox(width: 110, child: Text('Yazı ölçüsü')),
        Expanded(
          child: Slider(
            value: _font,
            min: 50,
            max: 200,
            divisions: 15,
            label: '${_font.round()}%',
            onChanged: (v) => setState(() => _font = v),
          ),
        ),
        SizedBox(width: 44, child: Text('${_font.round()}%', textAlign: TextAlign.right)),
      ]),
      Wrap(spacing: 8, runSpacing: 4, children: [
        for (final (key, title) in _parts)
          FilterChip(
            label: Text(title),
            selected: _show.contains(key),
            onSelected: (v) => setState(() => v ? _show.add(key) : _show.remove(key)),
          ),
      ]),

      _section('Say'),
      SwitchListTile(
        contentPadding: EdgeInsets.zero,
        title: const Text('Qalıq qədər'),
        subtitle: Text(_single ? 'Anbarda neçə ədəd varsa, o qədər etiket' : 'Hər məhsuldan anbardakı sayı qədər etiket'),
        value: _perStock,
        onChanged: (v) => setState(() => _perStock = v),
      ),
      if (!_perStock)
        Row(children: [
          Expanded(child: Text(_single ? 'Nüsxə sayı' : 'Hər məhsuldan', style: const TextStyle(fontSize: 16))),
          IconButton.outlined(onPressed: _copies > 1 ? () => setState(() => _copies--) : null, icon: const Icon(Icons.remove)),
          SizedBox(
            width: 52,
            child: Text('$_copies', textAlign: TextAlign.center, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          ),
          IconButton.outlined(onPressed: _copies < 500 ? () => setState(() => _copies++) : null, icon: const Icon(Icons.add)),
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
      Text('Dizayn bu telefonda yadda saxlanır. Açılan pəncərədə "Print" və ya barkod printerinin tətbiqini seçin; printerdə kağız ölçüsünü etiket ölçüsünə uyğun qoyun.',
          style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
    ]);
  }
}

/// Approximate on-screen preview of the label (the PDF from the server is the real thing).
class _LabelPreview extends StatelessWidget {
  const _LabelPreview({
    required this.wMm,
    required this.hMm,
    required this.barcodePct,
    required this.fontPct,
    required this.show,
    required this.store,
    required this.product,
  });

  final double wMm;
  final double hMm;
  final double barcodePct;
  final double fontPct;
  final Set<String> show;
  final String store;
  final Map<String, dynamic>? product;

  double _n(Object? v) => (v as num?)?.toDouble() ?? 0;

  @override
  Widget build(BuildContext context) {
    final p = product;
    final name = p == null ? 'Məhsulun adı' : '${p['name']}';
    final sale = p == null ? 10.0 : _n(p['salePrice']);
    final oldP = p == null ? 15.0 : (p['oldPrice'] == null ? null : _n(p['oldPrice']));
    final discount = show.contains('old') && oldP != null && oldP > sale;
    final code = p == null ? 'P-000001' : '${p['code']}';
    final barcodeText = p == null ? '4760000000011' : '${p['barcode'] ?? p['code']}';
    final wide = show.contains('price') && wMm / hMm >= 1.8;

    return LayoutBuilder(builder: (context, c) {
      final maxW = c.maxWidth;
      final scale = (maxW / wMm).clamp(1.0, 220 / hMm);
      final pw = wMm * scale, ph = hMm * scale;
      final mm = scale; // pixels per mm
      final base = (hMm * 0.25).clamp(6, 14) * fontPct / 100 * mm * 0.36; // pt → px (1 pt ≈ 0.353 mm)
      final priceSize = base * 1.7;
      String money(double v) => formatMoney(v).replaceAll(' ₼', ' AZN');

      Widget priceBlock(CrossAxisAlignment align) => Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: align, children: [
            if (discount && wide) Text('ENDİRİM', style: TextStyle(fontSize: priceSize * 0.45, fontWeight: FontWeight.w800)),
            if (discount)
              Text(money(oldP), style: TextStyle(fontSize: priceSize * 0.55, color: Colors.grey.shade700, decoration: TextDecoration.lineThrough)),
            Text(money(sale), style: TextStyle(fontSize: priceSize, fontWeight: FontWeight.w900, height: 1.0)),
          ]);

      final barcode = FractionallySizedBox(
        widthFactor: barcodePct / 100,
        heightFactor: barcodePct / 100,
        child: Column(children: [
          Expanded(child: CustomPaint(painter: _BarsPainter(barcodeText), size: Size.infinite)),
          Text(barcodeText, style: TextStyle(fontSize: (base * 0.8).clamp(6, 40), letterSpacing: 1.5), maxLines: 1, overflow: TextOverflow.clip),
        ]),
      );

      return Center(
        child: Container(
          width: pw,
          height: ph,
          padding: EdgeInsets.all((wMm < hMm ? wMm : hMm) * 0.06 * mm),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(6),
            border: Border.all(color: Colors.grey.shade400),
            boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: 0.06), blurRadius: 8, offset: const Offset(0, 2))],
          ),
          child: DefaultTextStyle(
            style: const TextStyle(color: Colors.black),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              if (show.contains('store'))
                Text(store, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: base * 0.7, color: Colors.grey.shade700)),
              if (show.contains('name'))
                Text(name,
                    maxLines: hMm >= 35 ? 2 : 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: base, fontWeight: FontWeight.w800, height: 1.1)),
              if (show.contains('code')) Text('$code · ${p?['unit'] ?? 'ədəd'}', style: TextStyle(fontSize: base * 0.75)),
              Expanded(
                child: wide
                    ? Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
                        Expanded(child: barcode),
                        const SizedBox(width: 8),
                        priceBlock(CrossAxisAlignment.end),
                      ])
                    : barcode,
              ),
              if (show.contains('price') && !wide)
                discount
                    ? Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
                        Text(money(oldP),
                            style: TextStyle(fontSize: priceSize * 0.55, color: Colors.grey.shade700, decoration: TextDecoration.lineThrough)),
                        const Spacer(),
                        Text(money(sale), style: TextStyle(fontSize: priceSize, fontWeight: FontWeight.w900, height: 1.0)),
                      ])
                    : Center(child: Text(money(sale), style: TextStyle(fontSize: priceSize, fontWeight: FontWeight.w900, height: 1.0))),
            ]),
          ),
        ),
      );
    });
  }
}

/// Barcode-looking stripes derived from the text (only for the preview).
class _BarsPainter extends CustomPainter {
  _BarsPainter(this.text);

  final String text;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()..color = Colors.black;
    final codes = text.codeUnits;
    final widths = <int>[];
    for (var i = 0; i < 48; i++) {
      widths.add(1 + (codes[i % codes.length] + i * 7) % 3);
    }
    final total = widths.fold<int>(0, (a, b) => a + b) * 2;
    final unit = size.width / total;
    var x = 0.0;
    for (var i = 0; i < widths.length; i++) {
      canvas.drawRect(Rect.fromLTWH(x, 0, widths[i] * unit, size.height), paint);
      x += widths[i] * unit * 2;
    }
  }

  @override
  bool shouldRepaint(_BarsPainter old) => old.text != text;
}
