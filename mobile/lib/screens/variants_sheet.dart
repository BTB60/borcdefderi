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

List<String> _split(String s) => s.split(RegExp(r'[,;\n]')).map((e) => e.trim()).where((e) => e.isNotEmpty).toList();

/// Ölçü / rəng variantları of a model product: the list, and new sizes × colours. Each variant is a product of its
/// own (own barcode, price and stock), sold and counted like any other.
Future<void> showVariantsSheet(BuildContext context, Session session, Map<String, dynamic> product, {required bool manage}) =>
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(bottom: sheetBottomInset(ctx)),
        child: _VariantsSheet(session: session, product: product, manage: manage),
      ),
    );

class _VariantsSheet extends StatefulWidget {
  const _VariantsSheet({required this.session, required this.product, required this.manage});

  final Session session;
  final Map<String, dynamic> product;
  final bool manage;

  @override
  State<_VariantsSheet> createState() => _VariantsSheetState();
}

class _VariantsSheetState extends State<_VariantsSheet> {
  final _sizes = TextEditingController();
  final _colors = TextEditingController();
  List<Map<String, dynamic>>? _items;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _sizes.dispose();
    _colors.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final list = (await widget.session.api.get('/api/products/${widget.product['id']}/variants') as List).cast<Map<String, dynamic>>();
      if (mounted) setState(() => _items = list);
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    }
  }

  int get _count {
    final s = _split(_sizes.text).length;
    final c = _split(_colors.text).length;
    if (s == 0 && c == 0) return 0;
    return (s == 0 ? 1 : s) * (c == 0 ? 1 : c);
  }

  Future<void> _add() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final before = _items?.length ?? 0;
      final list = (await widget.session.api.post('/api/products/${widget.product['id']}/variants',
              {'sizes': _split(_sizes.text), 'colors': _split(_colors.text)}) as List)
          .cast<Map<String, dynamic>>();
      if (!mounted) return;
      setState(() => _items = list);
      _sizes.clear();
      _colors.clear();
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${list.length - before} yeni variant əlavə olundu')));
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final items = _items;
    return ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
      Text('Variantlar — ${widget.product['name']}', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: navy)),
      const SizedBox(height: 4),
      Text('Hər ölçü + rəng ayrıca məhsuldur: öz barkodu, qiyməti və qalığı ilə.', style: TextStyle(color: Colors.grey.shade600, fontSize: 13)),
      const SizedBox(height: 12),
      if (_error != null) ErrorBanner(_error!),
      if (widget.manage) ...[
        TextField(
          controller: _sizes,
          onChanged: (_) => setState(() {}),
          decoration: const InputDecoration(labelText: 'Ölçülər', hintText: '38, 39, 40 və ya S, M, L'),
        ),
        const SizedBox(height: 10),
        TextField(
          controller: _colors,
          onChanged: (_) => setState(() {}),
          decoration: const InputDecoration(labelText: 'Rənglər', hintText: 'Qara, Ağ, Boz'),
        ),
        const SizedBox(height: 10),
        FilledButton.icon(
          onPressed: _busy || _count == 0 ? null : _add,
          icon: const Icon(Icons.add),
          label: Text(_count == 0 ? 'Variantları yarat' : '$_count variant yarat'),
        ),
        const Divider(height: 28),
      ],
      if (items == null && _error == null)
        const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()))
      else if (items != null && items.isEmpty)
        const EmptyState(title: 'Hələ variant yoxdur')
      else if (items != null) ...[
        for (final v in items)
          ListTile(
            contentPadding: EdgeInsets.zero,
            title: Text([v['variantSize'], v['variantColor']].where((e) => e != null).join(' · '),
                style: const TextStyle(fontWeight: FontWeight.w600)),
            subtitle: Text('${v['code']}${v['barcode'] != null ? ' · ${v['barcode']}' : ''}'),
            trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
              Text(formatMoney(_n(v['salePrice'])), style: const TextStyle(fontWeight: FontWeight.w700)),
              Text('${_q(v['stockQty'])} ${v['unit']}',
                  style: TextStyle(color: _n(v['stockQty']) == 0 ? Colors.red.shade700 : Colors.grey.shade700, fontSize: 12)),
            ]),
          ),
        const SizedBox(height: 8),
        Text('Cəmi qalıq: ${_q(items.fold<double>(0, (s, v) => s + _n(v['stockQty'])))}. '
            'Variantın qiymətini və qalığını Anbarda həmin variantı açıb dəyişin.',
            style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
      ],
    ]);
  }
}
