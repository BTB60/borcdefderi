import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import '../whatsapp.dart';
import 'common.dart';

double _n(Object? v) => v is num ? v.toDouble() : double.tryParse('${v ?? ''}'.replaceAll(',', '.')) ?? 0;
String _q(double v) => v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toString();

/// Goods of a receipt sent back to the supplier (some, or all = cancel the receipt). Returns the updated receipt.
Future<Map<String, dynamic>?> returnToSupplier(BuildContext context, Session session, Map<String, dynamic> purchase) async {
  final items = (purchase['items'] as List).cast<Map<String, dynamic>>()
      .map((i) => (item: i, left: _n(i['quantity']) - _n(i['returnedQty'])))
      .where((x) => x.left > 0)
      .toList();
  if (items.isEmpty) {
    showError(context, 'Bütün mallar artıq qaytarılıb');
    return null;
  }
  final qty = {for (final x in items) '${x.item['id']}': TextEditingController()};
  bool refund = false;
  final all = await showDialog<bool>(
    context: context,
    builder: (ctx) => StatefulBuilder(
      builder: (ctx, set) => AlertDialog(
        title: const Text('Tədarükçüyə qaytar'),
        content: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            for (final x in items)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(children: [
                  Expanded(child: Text('${x.item['name']}\n(qalıb ${_q(x.left)})')),
                  SizedBox(
                    width: 80,
                    child: TextField(
                      controller: qty['${x.item['id']}'],
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      decoration: const InputDecoration(hintText: '0', isDense: true),
                    ),
                  ),
                ]),
              ),
            if (purchase['companyId'] != null)
              CheckboxListTile(
                contentPadding: EdgeInsets.zero,
                value: refund,
                onChanged: (v) => set(() => refund = v ?? false),
                title: const Text('Artıq ödənmiş pulu tədarükçü geri verdi — kassaya yaz', style: TextStyle(fontSize: 13)),
              ),
            Text('Mal anbardan çıxılır, dəyəri tədarükçüyə borcunuzdan çıxılır.', style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
          ]),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Bağla')),
          OutlinedButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Seçilənlər')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red.shade700),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Hamısı (ləğv et)'),
          ),
        ],
      ),
    ),
  );
  final lines = all == false
      ? [
          for (final x in items)
            if (_n(qty['${x.item['id']}']!.text) > 0) {'itemId': x.item['id'], 'quantity': _n(qty['${x.item['id']}']!.text)},
        ]
      : <Map<String, dynamic>>[];
  for (final c in qty.values) {
    c.dispose();
  }
  if (all == null) return null;
  if (all == false && lines.isEmpty) {
    if (context.mounted) showError(context, 'Qaytarılan miqdarı yazın');
    return null;
  }
  try {
    return await session.api.post('/api/purchases/${purchase['id']}/returns', {'items': lines, 'refundToCash': refund})
        as Map<String, dynamic>;
  } catch (e) {
    if (context.mounted) showError(context, e);
    return null;
  }
}

/// Toplu qiymət dəyişikliyi: one price of many products at once.
Future<String?> bulkPriceSheet(BuildContext context, Session session) async {
  final cats = ((await session.api.get('/api/product-categories')) as List).cast<Map<String, dynamic>>();
  final sups = ((await session.api.get('/api/companies')) as List).cast<Map<String, dynamic>>();
  if (!context.mounted) return null;
  String field = 'SALE', mode = 'PERCENT', round = '0.01', scope = 'all';
  String? target;
  final value = TextEditingController();
  String? error;
  final result = await showModalBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    useSafeArea: true,
    builder: (ctx) => StatefulBuilder(builder: (ctx, set) {
      Future<void> apply() async {
        final v = _n(value.text);
        if (value.text.trim().isEmpty) return set(() => error = 'Dəyəri yazın');
        if (scope != 'all' && target == null) return set(() => error = 'Seçim edin');
        try {
          final r = await session.api.post('/api/products/bulk-price', {
            'field': field, 'mode': mode, 'value': v.toStringAsFixed(2), 'roundTo': round,
            'categoryId': scope == 'category' ? target : null, 'supplierId': scope == 'supplier' ? target : null,
          }) as Map<String, dynamic>;
          if (ctx.mounted) Navigator.pop(ctx, '${r['changed']} məhsulun qiyməti dəyişdi');
        } catch (e) {
          set(() => error = errorMessage(e));
        }
      }

      DropdownButtonFormField<String> drop(String label, String val, Map<String, String> items, ValueChanged<String> on) =>
          DropdownButtonFormField<String>(
            initialValue: val,
            isExpanded: true,
            decoration: InputDecoration(labelText: label),
            items: [for (final e in items.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
            onChanged: (v) => set(() => on(v!)),
          );
      final targets = scope == 'category' ? cats : sups;
      return ListView(
        shrinkWrap: true,
        padding: EdgeInsets.fromLTRB(20, 0, 20, 20 + sheetBottomInset(ctx)),
        children: [
          const Text('Qiymətləri toplu dəyiş', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: navy)),
          if (error != null) Padding(padding: const EdgeInsets.only(top: 8), child: ErrorBanner(error!)),
          const SizedBox(height: 12),
          drop('Hansı qiymət', field, const {'SALE': 'Top 2 — pərakəndə', 'WHOLESALE': 'Top 1 — topdan', 'PURCHASE': 'Alış qiyməti'}, (v) => field = v),
          const SizedBox(height: 10),
          drop('Necə', mode, const {'PERCENT': 'Faizlə (+10 / −5)', 'AMOUNT': 'Manatla (+0.50 / −1)', 'SET': 'Yeni qiymət'}, (v) => mode = v),
          const SizedBox(height: 10),
          TextField(
            controller: value,
            keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
            decoration: InputDecoration(labelText: mode == 'PERCENT' ? 'Faiz (%)' : 'Məbləğ (₼)'),
          ),
          const SizedBox(height: 10),
          drop('Yuvarlaqlaşdır', round, const {'0.01': 'Qəpiyə qədər', '0.05': '5 qəpik', '0.1': '10 qəpik', '1': 'Tam manat'}, (v) => round = v),
          const SizedBox(height: 10),
          drop('Hansı məhsullar', scope, const {'all': 'Bütün aktiv məhsullar', 'category': 'Kateqoriya', 'supplier': 'Tədarükçü'}, (v) {
            scope = v;
            target = null;
          }),
          if (scope != 'all') ...[
            const SizedBox(height: 10),
            DropdownButtonFormField<String>(
              initialValue: target,
              isExpanded: true,
              decoration: InputDecoration(labelText: scope == 'category' ? 'Kateqoriya' : 'Tədarükçü'),
              items: [for (final t in targets) DropdownMenuItem(value: '${t['id']}', child: Text('${t['name']}'))],
              onChanged: (v) => set(() => target = v),
            ),
          ],
          const SizedBox(height: 16),
          FilledButton(onPressed: apply, child: const Text('Tətbiq et')),
        ],
      );
    }),
  );
  value.dispose();
  return result;
}

/// Sifariş siyahısı: goods at or below their minimum stock, by supplier, with a WhatsApp order.
class ReorderScreen extends StatefulWidget {
  const ReorderScreen({super.key, required this.session});

  final Session session;

  @override
  State<ReorderScreen> createState() => _ReorderScreenState();
}

class _ReorderScreenState extends State<ReorderScreen> {
  late final Future<List<dynamic>> _data = Future.wait([
    widget.session.api.get('/api/products', query: {'active': 'true'}),
    widget.session.api.get('/api/companies'),
  ]);

  double _suggested(Map<String, dynamic> p) {
    final min = _n(p['minStock']) < 1 ? 1.0 : _n(p['minStock']);
    final s = (min * 2 - (_n(p['stockQty']) < 0 ? 0 : _n(p['stockQty']))).ceilToDouble();
    return s < 1 ? 1 : s;
  }

  Future<void> _order(String? phone, List<Map<String, dynamic>> items) async {
    final store = widget.session.me?.storeName ?? 'Mağazamız';
    final text = 'Salam! $store üçün sifariş:\n${[
      for (var i = 0; i < items.length; i++) '${i + 1}. ${items[i]['name']} — ${_q(_suggested(items[i]))} ${items[i]['unit']}',
    ].join('\n')}\n\nTəşəkkür edirik!';
    final uri = waUri(phone, text) ?? Uri.parse('https://wa.me/?text=${Uri.encodeComponent(text)}');
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication) && mounted) showError(context, 'WhatsApp açılmadı');
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Sifariş siyahısı')),
        body: FutureBuilder<List<dynamic>>(
          future: _data,
          builder: (context, snap) {
            if (snap.hasError) return ListView(children: [ErrorBanner(errorMessage(snap.error!))]);
            if (!snap.hasData) return const Center(child: CircularProgressIndicator());
            final products = (snap.data![0] as List).cast<Map<String, dynamic>>()
                .where((p) => p['lowStock'] == true || p['outOfStock'] == true).toList();
            final sups = (snap.data![1] as List).cast<Map<String, dynamic>>();
            if (products.isEmpty) return ListView(children: const [EmptyState(title: 'Sifariş ediləcək mal yoxdur')]);
            final groups = <String, List<Map<String, dynamic>>>{};
            for (final p in products) {
              groups.putIfAbsent('${p['supplierId'] ?? ''}', () => []).add(p);
            }
            return ListView(padding: const EdgeInsets.all(12), children: [
              for (final g in groups.entries)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(g.value.first['supplierName'] ?? 'Tədarükçüsüz mallar', style: const TextStyle(fontWeight: FontWeight.w800, color: navy)),
                      for (final p in g.value)
                        Padding(
                          padding: const EdgeInsets.only(top: 4),
                          child: Text('• ${p['name']} — qalıq ${_q(_n(p['stockQty']))}, sifariş ${_q(_suggested(p))} ${p['unit']}'),
                        ),
                      const SizedBox(height: 6),
                      FilledButton.icon(
                        style: FilledButton.styleFrom(backgroundColor: const Color(0xFF25D366)),
                        onPressed: () => _order(sups.where((s) => s['id'] == g.value.first['supplierId']).firstOrNull?['phone'] as String?, g.value),
                        icon: const Icon(Icons.chat, size: 18),
                        label: const Text('WhatsApp-da sifariş'),
                      ),
                    ]),
                  ),
                ),
            ]);
          },
        ),
      );
}

/// Son istifadə tarixi: goods on the shelf that have expired or expire within 7 / 30 / 90 days.
class ExpiryScreen extends StatefulWidget {
  const ExpiryScreen({super.key, required this.session});

  final Session session;

  @override
  State<ExpiryScreen> createState() => _ExpiryScreenState();
}

class _ExpiryScreenState extends State<ExpiryScreen> {
  int _days = 30;
  late Future<List<Map<String, dynamic>>> _data = _load();

  Future<List<Map<String, dynamic>>> _load() async =>
      ((await widget.session.api.get('/api/inventory/expiring', query: {'days': '$_days'})) as List).cast<Map<String, dynamic>>();

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Son istifadə tarixi')),
        body: Column(children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
            child: SegmentedButton<int>(
              showSelectedIcon: false,
              segments: const [
                ButtonSegment(value: 7, label: Text('7 gün')),
                ButtonSegment(value: 30, label: Text('30 gün')),
                ButtonSegment(value: 90, label: Text('90 gün')),
              ],
              selected: {_days},
              onSelectionChanged: (v) => setState(() {
                _days = v.first;
                _data = _load();
              }),
            ),
          ),
          Expanded(
            child: FutureBuilder<List<Map<String, dynamic>>>(
              future: _data,
              builder: (context, snap) {
                if (snap.hasError) return ListView(children: [ErrorBanner(errorMessage(snap.error!))]);
                if (!snap.hasData) return const Center(child: CircularProgressIndicator());
                final rows = snap.data!;
                if (rows.isEmpty) {
                  return ListView(children: [
                    EmptyState(title: 'Vaxtı keçən mal yoxdur', text: 'Növbəti $_days gündə heç bir partiyanın vaxtı bitmir. Tarixi mal qəbulunda yazın.'),
                  ]);
                }
                return ListView.builder(
                  padding: const EdgeInsets.all(12),
                  itemCount: rows.length,
                  itemBuilder: (_, i) {
                    final r = rows[i];
                    final left = (r['daysLeft'] as num).toInt();
                    final color = left < 0 ? Colors.red : left <= 7 ? Colors.orange : Colors.blueGrey;
                    return Card(
                      color: left < 0 ? Colors.red.shade50 : null,
                      child: ListTile(
                        title: Text('${r['productName']}', style: const TextStyle(fontWeight: FontWeight.w700)),
                        subtitle: Text('${r['purchaseNumber']} · rəfdə ${_q(_n(r['quantityLeft']))} ${r['unit']} · maya ${formatMoney(_n(r['costValue']))}'),
                        trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                          Text(formatDate(DateTime.tryParse('${r['expiryDate']}')), style: const TextStyle(fontWeight: FontWeight.w600)),
                          Text(left < 0 ? '${-left} gün keçib' : left == 0 ? 'Bu gün bitir' : '$left gün qalıb',
                              style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: color.shade800)),
                        ]),
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ]),
      );
}

/// Yatmış mallar: goods in stock not sold for 30 / 60 / 90 days, with the money frozen in them.
class IdleGoodsScreen extends StatefulWidget {
  const IdleGoodsScreen({super.key, required this.session});

  final Session session;

  @override
  State<IdleGoodsScreen> createState() => _IdleGoodsScreenState();
}

class _IdleGoodsScreenState extends State<IdleGoodsScreen> {
  int _days = 60;
  late Future<List<Map<String, dynamic>>> _data = _load();

  Future<List<Map<String, dynamic>>> _load() async =>
      ((await widget.session.api.get('/api/inventory/idle', query: {'days': '$_days'})) as List).cast<Map<String, dynamic>>();

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Yatmış mallar')),
        body: Column(children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
            child: SegmentedButton<int>(
              showSelectedIcon: false,
              segments: const [
                ButtonSegment(value: 30, label: Text('30+')),
                ButtonSegment(value: 60, label: Text('60+')),
                ButtonSegment(value: 90, label: Text('90+')),
                ButtonSegment(value: 180, label: Text('180+')),
              ],
              selected: {_days},
              onSelectionChanged: (v) => setState(() {
                _days = v.first;
                _data = _load();
              }),
            ),
          ),
          Expanded(
            child: FutureBuilder<List<Map<String, dynamic>>>(
              future: _data,
              builder: (context, snap) {
                if (snap.hasError) return ListView(children: [ErrorBanner(errorMessage(snap.error!))]);
                if (!snap.hasData) return const Center(child: CircularProgressIndicator());
                final rows = snap.data!;
                if (rows.isEmpty) return ListView(children: [EmptyState(title: 'Yatmış mal yoxdur', text: 'Son $_days gündə bütün mallar satılıb.')]);
                final frozen = rows.fold<double>(0, (a, r) => a + _n(r['costValue']));
                return ListView(padding: const EdgeInsets.all(12), children: [
                  Card(
                    color: Colors.red.shade50,
                    child: ListTile(
                      leading: Icon(Icons.savings_outlined, color: Colors.red.shade800),
                      title: Text('Donmuş pul: ${formatMoney(frozen)}', style: const TextStyle(fontWeight: FontWeight.w800)),
                      subtitle: Text('${rows.length} məhsul $_days+ gündür satılmır. Endirimə qoyun.'),
                    ),
                  ),
                  for (final r in rows)
                    Card(
                      child: ListTile(
                        title: Text('${r['name']}', style: const TextStyle(fontWeight: FontWeight.w700)),
                        subtitle: Text('Qalıq ${_q(_n(r['stockQty']))} ${r['unit']} · '
                            '${r['lastSoldOn'] == null ? 'heç satılmayıb' : 'son satış ${formatDate(DateTime.tryParse('${r['lastSoldOn']}'))}'}'),
                        trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                          Text(formatMoney(_n(r['costValue'])), style: const TextStyle(fontWeight: FontWeight.w800)),
                          Text('${r['idleDays']} gün', style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
                        ]),
                      ),
                    ),
                ]);
              },
            ),
          ),
        ]),
      );
}
