import 'package:flutter/material.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

double _n(Object? v) => (v as num?)?.toDouble() ?? 0;
String _q(Object? v) {
  final d = _n(v);
  return d == d.roundToDouble() ? d.toStringAsFixed(0) : d.toStringAsFixed(3).replaceAll(RegExp(r'0+$'), '');
}

/// Anbar: products with stock, low-stock filter, add product and stock in / out / count.
class InventoryScreen extends StatefulWidget {
  const InventoryScreen({super.key, required this.session});

  final Session session;

  @override
  State<InventoryScreen> createState() => _InventoryScreenState();
}

class _InventoryScreenState extends State<InventoryScreen> {
  String _q2 = '';
  bool _low = false;
  late Future<List<dynamic>> _data = _fetch();

  bool get _manage => widget.session.me!.can('INVENTORY');

  Future<List<dynamic>> _fetch() => Future.wait([
        widget.session.api.get('/api/products', query: {
          'active': 'true',
          if (_q2.isNotEmpty) 'q': _q2,
          if (_low) 'stock': 'LOW',
        }),
        widget.session.api.get('/api/products/summary'),
      ]);

  void _reload() => setState(() => _data = _fetch());

  Future<void> _sheet(Widget child) async {
    final msg = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (ctx) => Padding(padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom), child: child),
    );
    if (msg == null || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
    _reload();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Anbar')),
      floatingActionButton: _manage
          ? FloatingActionButton.extended(
              onPressed: () => _sheet(_ProductSheet(session: widget.session)), icon: const Icon(Icons.add), label: const Text('Məhsul'))
          : null,
      body: FutureBuilder<List<dynamic>>(
        future: _data,
        builder: (context, snap) {
          if (snap.hasError) return ListView(children: [Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!)))]);
          final items = snap.hasData ? (snap.data![0] as List).cast<Map<String, dynamic>>() : <Map<String, dynamic>>[];
          final s = snap.hasData ? snap.data![1] as Map<String, dynamic> : null;
          return RefreshIndicator(
            onRefresh: () async => _reload(),
            child: ListView(padding: const EdgeInsets.fromLTRB(12, 12, 12, 96), children: [
              if (s != null)
                Row(children: [
                  Expanded(child: StatTile(label: 'Anbar dəyəri (alış)', value: formatMoney(_n(s['costValue'])), hint: '${s['activeCount']} məhsul')),
                  const SizedBox(width: 10),
                  Expanded(child: StatTile(label: 'Az qalan / bitən', value: '${s['lowStockCount']} / ${s['outOfStockCount']}')),
                ]),
              const SizedBox(height: 10),
              TextField(
                decoration: const InputDecoration(prefixIcon: Icon(Icons.search), hintText: 'Ad, kod və ya barkod'),
                textInputAction: TextInputAction.search,
                onSubmitted: (v) {
                  _q2 = v.trim();
                  _reload();
                },
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Yalnız az qalanlar'),
                value: _low,
                onChanged: (v) {
                  _low = v;
                  _reload();
                },
              ),
              if (!snap.hasData) const Center(child: CircularProgressIndicator()),
              if (snap.hasData && items.isEmpty) const EmptyState(title: 'Məhsul yoxdur'),
              for (final p in items)
                Card(
                  child: ListTile(
                    onTap: _manage ? () => _sheet(_StockSheet(session: widget.session, product: p)) : null,
                    title: Text('${p['name']}', style: const TextStyle(fontWeight: FontWeight.w600)),
                    subtitle: Text('${p['code']} · alış ${formatMoney(_n(p['purchasePrice']))} · satış ${formatMoney(_n(p['salePrice']))}'),
                    trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                      Text('${_q(p['stockQty'])} ${p['unit']}',
                          style: TextStyle(
                              fontWeight: FontWeight.w800,
                              color: p['outOfStock'] == true
                                  ? Colors.red.shade700
                                  : p['lowStock'] == true
                                      ? Colors.orange.shade800
                                      : navy)),
                      if (p['outOfStock'] == true)
                        const Text('bitib', style: TextStyle(fontSize: 11, color: Colors.red))
                      else if (p['lowStock'] == true)
                        const Text('az qalıb', style: TextStyle(fontSize: 11, color: Colors.orange)),
                    ]),
                  ),
                ),
              if (_manage && items.isNotEmpty)
                Text('Stok əlavə etmək / çıxmaq üçün məhsula toxunun.', style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
            ]),
          );
        },
      ),
    );
  }
}

class _ProductSheet extends StatefulWidget {
  const _ProductSheet({required this.session});

  final Session session;

  @override
  State<_ProductSheet> createState() => _ProductSheetState();
}

class _ProductSheetState extends State<_ProductSheet> {
  final _name = TextEditingController();
  final _barcode = TextEditingController();
  final _buy = TextEditingController();
  final _sell = TextEditingController();
  final _stock = TextEditingController();
  final _min = TextEditingController(text: '0');
  final _unit = TextEditingController(text: 'ədəd');
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    for (final c in [_name, _barcode, _buy, _sell, _stock, _min, _unit]) {
      c.dispose();
    }
    super.dispose();
  }

  String _d(TextEditingController c) => c.text.trim().replaceAll(',', '.');

  Future<void> _save() async {
    if (_name.text.trim().isEmpty || double.tryParse(_d(_buy)) == null || double.tryParse(_d(_sell)) == null) {
      setState(() => _error = 'Ad, alış və satış qiymətini daxil edin');
      return;
    }
    setState(() => _busy = true);
    try {
      final p = await widget.session.api.post('/api/products', {
        'name': _name.text.trim(),
        'barcode': _barcode.text.trim().isEmpty ? null : _barcode.text.trim(),
        'purchasePrice': _d(_buy),
        'salePrice': _d(_sell),
        'unit': _unit.text.trim(),
        'minStock': _d(_min).isEmpty ? '0' : _d(_min),
        'initialStock': _d(_stock).isEmpty ? null : _d(_stock),
      }) as Map<String, dynamic>;
      if (mounted) Navigator.pop(context, 'Məhsul əlavə edildi: ${p['code']}');
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
        const Text('Yeni məhsul', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: navy)),
        if (_error != null) ErrorBanner(_error!),
        TextField(controller: _name, decoration: const InputDecoration(labelText: 'Ad')),
        TextField(controller: _barcode, decoration: const InputDecoration(labelText: 'Barkod (istəyə bağlı)')),
        Row(children: [
          Expanded(child: TextField(controller: _buy, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: const InputDecoration(labelText: 'Alış (₼)'))),
          const SizedBox(width: 10),
          Expanded(child: TextField(controller: _sell, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: const InputDecoration(labelText: 'Satış (₼)'))),
        ]),
        Row(children: [
          Expanded(child: TextField(controller: _stock, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: const InputDecoration(labelText: 'Başlanğıc qalıq'))),
          const SizedBox(width: 10),
          Expanded(child: TextField(controller: _min, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: const InputDecoration(labelText: 'Minimum stok'))),
          const SizedBox(width: 10),
          Expanded(child: TextField(controller: _unit, decoration: const InputDecoration(labelText: 'Vahid'))),
        ]),
        const SizedBox(height: 14),
        FilledButton(onPressed: _busy ? null : _save, child: const Text('Saxla')),
      ]);
}

class _StockSheet extends StatefulWidget {
  const _StockSheet({required this.session, required this.product});

  final Session session;
  final Map<String, dynamic> product;

  @override
  State<_StockSheet> createState() => _StockSheetState();
}

class _StockSheetState extends State<_StockSheet> {
  String _kind = 'RECEIPT';
  final _qty = TextEditingController();
  late final _cost = TextEditingController(text: _n(widget.product['purchasePrice']).toStringAsFixed(2));
  final _note = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _qty.dispose();
    _cost.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final q = _qty.text.trim().replaceAll(',', '.');
    if (double.tryParse(q) == null) {
      setState(() => _error = 'Miqdarı daxil edin');
      return;
    }
    setState(() => _busy = true);
    try {
      final m = await widget.session.api.post('/api/inventory/movements', {
        'productId': widget.product['id'],
        'kind': _kind,
        'quantity': q,
        if (_kind == 'RECEIPT') 'unitCost': _cost.text.trim().replaceAll(',', '.'),
        'note': _note.text.trim().isEmpty ? null : _note.text.trim(),
      }) as Map<String, dynamic>;
      if (mounted) Navigator.pop(context, '${widget.product['name']}: yeni qalıq ${_q(m['balanceAfter'])}');
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
        Text('${widget.product['name']}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: navy)),
        Text('İndiki qalıq: ${_q(widget.product['stockQty'])} ${widget.product['unit']}'),
        const SizedBox(height: 10),
        if (_error != null) ErrorBanner(_error!),
        Wrap(spacing: 6, children: [
          for (final k in const [['RECEIPT', 'Giriş'], ['ISSUE', 'Çıxış'], ['ADJUSTMENT', 'Düzəliş ±'], ['COUNT', 'Sayım']])
            ChoiceChip(label: Text(k[1]), selected: _kind == k[0], onSelected: (_) => setState(() => _kind = k[0])),
        ]),
        TextField(
          controller: _qty,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
          decoration: InputDecoration(labelText: _kind == 'COUNT' ? 'Sayılan miqdar' : _kind == 'ADJUSTMENT' ? 'Fərq (+/−)' : 'Miqdar'),
        ),
        if (_kind == 'RECEIPT')
          TextField(controller: _cost, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: const InputDecoration(labelText: 'Alış qiyməti (₼)')),
        TextField(controller: _note, decoration: const InputDecoration(labelText: 'Qeyd')),
        const SizedBox(height: 14),
        FilledButton(onPressed: _busy ? null : _save, child: const Text('Təsdiqlə')),
      ]);
}
