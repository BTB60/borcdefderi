import 'package:flutter/material.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'barcode_scanner.dart';
import 'cash_screen.dart' show newRequestId;
import 'common.dart';

double _n(Object? v) => v is num ? v.toDouble() : double.tryParse('${v ?? ''}'.replaceAll(',', '.')) ?? 0;
double _r2(double v) => (v * 100).roundToDouble() / 100;
String _q(double v) => v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toString();

/// Mal qəbulu: list of goods received from suppliers; "+" opens a new receipt.
class PurchasesScreen extends StatefulWidget {
  const PurchasesScreen({super.key, required this.session});

  final Session session;

  @override
  State<PurchasesScreen> createState() => _PurchasesScreenState();
}

class _PurchasesScreenState extends State<PurchasesScreen> {
  late Future<List<Map<String, dynamic>>> _data = _fetch();

  Future<List<Map<String, dynamic>>> _fetch() async =>
      ((await widget.session.api.get('/api/purchases')) as List).cast<Map<String, dynamic>>();

  void _reload() => setState(() => _data = _fetch());

  Future<void> _new() async {
    final saved = await Navigator.of(context).push<Map<String, dynamic>>(
        MaterialPageRoute(builder: (_) => PurchaseFormScreen(session: widget.session)));
    if (saved == null || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('${saved['number']} qəbul edildi: ${(saved['items'] as List).length} məhsul, ${formatMoney(_n(saved['total']))}')));
    _reload();
  }

  Future<void> _show(Map<String, dynamic> row) async {
    try {
      final p = await widget.session.api.get('/api/purchases/${row['id']}') as Map<String, dynamic>;
      if (!mounted) return;
      final items = (p['items'] as List).cast<Map<String, dynamic>>();
      await showModalBottomSheet<void>(
        context: context,
        showDragHandle: true,
        isScrollControlled: true,
        useSafeArea: true,
        builder: (ctx) => ListView(
          shrinkWrap: true,
          padding: EdgeInsets.fromLTRB(20, 0, 20, 20 + sheetBottomInset(ctx)),
          children: [
            Text('${p['number']} · ${formatDate(DateTime.tryParse('${p['receivedOn']}'))}',
                style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: navy)),
            Text([
              p['companyName'] ?? 'Təchizatçısız',
              if (p['supplierInvoice'] != null) 'qaimə ${p['supplierInvoice']}',
              '${p['createdByName']}',
            ].join(' · '), style: TextStyle(color: Colors.grey.shade600)),
            const SizedBox(height: 8),
            for (final i in items)
              ListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                title: Text('${i['name']}'),
                subtitle: Text('${_q(_n(i['quantity']))} × ${formatMoney(_n(i['unitCost']))}'),
                trailing: Text(formatMoney(_n(i['lineTotal'])), style: const TextStyle(fontWeight: FontWeight.w700)),
              ),
            const Divider(),
            _total('Cəmi', _n(p['total']), navy),
            _total('Ödənildi', _n(p['paid']), Colors.green.shade700),
            if (_n(p['unpaid']) > 0) _total('Borca yazıldı', _n(p['unpaid']), Colors.red.shade700),
            if (p['note'] != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text('${p['note']}')),
          ],
        ),
      );
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Widget _total(String label, double v, Color c) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Row(children: [
          Expanded(child: Text(label)),
          Text(formatMoney(v), style: TextStyle(fontWeight: FontWeight.w800, color: c)),
        ]),
      );

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Mal qəbulu')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _new,
        icon: const Icon(Icons.add),
        label: const Text('Yeni qəbul'),
      ),
      body: FutureBuilder<List<Map<String, dynamic>>>(
        future: _data,
        builder: (context, snap) {
          if (snap.hasError) return ListView(children: [ErrorBanner(errorMessage(snap.error!))]);
          if (!snap.hasData) return const Center(child: CircularProgressIndicator());
          final list = snap.data!;
          return RefreshIndicator(
            onRefresh: () async => _reload(),
            child: list.isEmpty
                ? ListView(children: const [
                    EmptyState(title: 'Hələ mal qəbulu yoxdur', text: 'Təchizatçıdan mal gələndə “Yeni qəbul” basın'),
                  ])
                : ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 96),
                    itemCount: list.length,
                    itemBuilder: (_, i) {
                      final p = list[i];
                      final unpaid = _n(p['unpaid']);
                      return Card(
                        child: ListTile(
                          onTap: () => _show(p),
                          leading: const CircleAvatar(child: Icon(Icons.local_shipping_outlined)),
                          title: Text('${p['number']} · ${p['companyName'] ?? 'Təchizatçısız'}',
                              maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600)),
                          subtitle: Text([
                            formatDate(DateTime.tryParse('${p['receivedOn']}')),
                            if (p['supplierInvoice'] != null) 'qaimə ${p['supplierInvoice']}',
                            if (unpaid > 0) 'borc ${formatMoney(unpaid)}',
                          ].join(' · ')),
                          trailing: Text(formatMoney(_n(p['total'])), style: const TextStyle(fontWeight: FontWeight.w800)),
                        ),
                      );
                    },
                  ),
          );
        },
      ),
    );
  }
}

class _Line {
  _Line(this.product)
      : qty = TextEditingController(text: '1'),
        cost = TextEditingController(text: _n(product['purchasePrice']).toStringAsFixed(2));

  final Map<String, dynamic> product;
  final TextEditingController qty;
  final TextEditingController cost;

  double get total => _r2(_n(qty.text) * _n(cost.text));

  void dispose() {
    qty.dispose();
    cost.dispose();
  }
}

/// New purchase receipt: supplier, products (search or scan), quantities, prices and what is paid now.
class PurchaseFormScreen extends StatefulWidget {
  const PurchaseFormScreen({super.key, required this.session});

  final Session session;

  @override
  State<PurchaseFormScreen> createState() => _PurchaseFormScreenState();
}

class _PurchaseFormScreenState extends State<PurchaseFormScreen> {
  final _requestId = newRequestId();
  final _lines = <_Line>[];
  final _search = TextEditingController();
  final _invoice = TextEditingController();
  final _paid = TextEditingController();
  final _note = TextEditingController();
  List<Map<String, dynamic>> _products = [];
  List<Map<String, dynamic>> _suppliers = [];
  String? _companyId;
  String _method = 'CASH';
  DateTime? _due;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final r = await Future.wait([
        widget.session.api.get('/api/products', query: {'active': 'true'}),
        widget.session.api.get('/api/companies'),
      ]);
      if (!mounted) return;
      setState(() {
        _products = (r[0] as List).cast<Map<String, dynamic>>();
        _suppliers = (r[1] as List).cast<Map<String, dynamic>>();
      });
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  void dispose() {
    for (final l in _lines) {
      l.dispose();
    }
    _search.dispose();
    _invoice.dispose();
    _paid.dispose();
    _note.dispose();
    super.dispose();
  }

  double get _total => _r2(_lines.fold<double>(0, (s, l) => s + l.total));

  void _add(Map<String, dynamic> p) {
    setState(() {
      final i = _lines.indexWhere((l) => l.product['id'] == p['id']);
      if (i >= 0) {
        _lines[i].qty.text = _q(_n(_lines[i].qty.text) + 1);
      } else {
        _lines.add(_Line(p));
      }
      _search.clear();
    });
  }

  List<Map<String, dynamic>> get _matches {
    final q = _search.text.trim().toLowerCase();
    if (q.isEmpty) return const [];
    return _products
        .where((p) => '${p['name']}'.toLowerCase().contains(q) || '${p['code']}'.toLowerCase() == q || '${p['barcode']}' == q)
        .take(8)
        .toList();
  }

  Future<void> _scan() async {
    await Navigator.of(context).push(MaterialPageRoute(
      fullscreenDialog: true,
      builder: (_) => BarcodeScannerScreen(
        title: 'Gələn malı skan edin',
        onCode: (code) async {
          final p = _products.where((x) => x['barcode'] == code || x['code'] == code).firstOrNull;
          if (p == null) return 'Tapılmadı: $code';
          _add(p);
          return '${p['name']} əlavə edildi';
        },
      ),
    ));
  }

  Future<void> _save() async {
    if (_lines.isEmpty) return showError(context, 'Ən azı bir məhsul əlavə edin');
    final paid = _r2(_n(_paid.text));
    if (paid > _total) return showError(context, 'Ödənilən məbləğ alışdan çox ola bilməz');
    if (_companyId == null && paid > 0 && paid != _total) {
      return showError(context, 'Borcla və ya qismən ödənişli alış üçün təchizatçı seçin');
    }
    setState(() => _busy = true);
    try {
      final saved = await widget.session.api.post('/api/purchases', {
        'companyId': _companyId,
        'items': [
          for (final l in _lines) {'productId': l.product['id'], 'quantity': _n(l.qty.text), 'unitCost': _r2(_n(l.cost.text))},
        ],
        'paid': paid,
        'paymentMethod': _method,
        'supplierInvoice': _invoice.text.trim().isEmpty ? null : _invoice.text.trim(),
        'dueDate': _due?.toIso8601String().substring(0, 10),
        'note': _note.text.trim().isEmpty ? null : _note.text.trim(),
        'clientRequestId': _requestId,
      }) as Map<String, dynamic>;
      if (mounted) Navigator.of(context).pop(saved);
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final matches = _matches;
    final unpaid = _companyId == null ? 0.0 : (_total - _n(_paid.text)).clamp(0, double.infinity).toDouble();
    return Scaffold(
      appBar: AppBar(title: const Text('Yeni mal qəbulu'), actions: [
        IconButton(tooltip: 'Barkod skan et', icon: const Icon(Icons.qr_code_scanner), onPressed: _scan),
      ]),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
          child: FilledButton.icon(
            onPressed: _busy || _lines.isEmpty ? null : _save,
            icon: const Icon(Icons.check),
            label: Text(_busy ? 'Saxlanılır…' : 'Qəbul et · ${formatMoney(_total)}'),
          ),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
        children: [
          DropdownButtonFormField<String?>(
            initialValue: _companyId,
            isExpanded: true,
            decoration: const InputDecoration(labelText: 'Təchizatçı (şirkət)', helperText: 'Ödənilməyən hissə “Təchizatçılar üzrə borc uçotu”na yazılır'),
            items: [
              const DropdownMenuItem<String?>(value: null, child: Text('— Təchizatçısız —')),
              for (final s in _suppliers) DropdownMenuItem<String?>(value: '${s['id']}', child: Text('${s['name']}')),
            ],
            onChanged: (v) => setState(() {
              _companyId = v;
              if (v == null && _method == 'TRANSFER') _method = 'CASH';
            }),
          ),
          const SizedBox(height: 12),
          TextField(controller: _invoice, maxLength: 60, decoration: const InputDecoration(labelText: 'Qaimə nömrəsi (istəyə bağlı)')),
          if (_companyId != null)
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Son ödəmə tarixi (istəyə bağlı)'),
              subtitle: Text(_due == null ? '—' : formatDate(_due)),
              trailing: const Icon(Icons.event),
              onTap: () async {
                final d = await showDatePicker(
                    context: context, firstDate: DateTime.now(), lastDate: DateTime.now().add(const Duration(days: 365 * 3)));
                if (d != null) setState(() => _due = d);
              },
            ),
          const SizedBox(height: 8),
          TextField(
            controller: _search,
            onChanged: (_) => setState(() {}),
            onSubmitted: (q) {
              final exact = _products.where((p) => p['barcode'] == q.trim() || '${p['code']}'.toLowerCase() == q.trim().toLowerCase());
              if (exact.isNotEmpty) {
                _add(exact.first);
              } else if (matches.length == 1) {
                _add(matches.first);
              }
            },
            decoration: InputDecoration(
              labelText: 'Məhsul əlavə et',
              hintText: 'Ad, kod və ya barkod',
              prefixIcon: const Icon(Icons.search),
              suffixIcon: IconButton(icon: const Icon(Icons.qr_code_scanner), onPressed: _scan),
            ),
          ),
          for (final p in matches)
            ListTile(
              dense: true,
              title: Text('${p['name']}'),
              subtitle: Text('qalıq ${_q(_n(p['stockQty']))} · alış ${formatMoney(_n(p['purchasePrice']))}'),
              trailing: const Icon(Icons.add_circle_outline),
              onTap: () => _add(p),
            ),
          const SizedBox(height: 8),
          if (_lines.isEmpty) const EmptyState(title: 'Məhsul əlavə edin', text: 'Axtarın və ya barkodu skan edin'),
          for (final l in _lines)
            Card(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    Expanded(child: Text('${l.product['name']}', style: const TextStyle(fontWeight: FontWeight.w700))),
                    IconButton(
                      tooltip: 'Sil',
                      icon: Icon(Icons.close, color: Colors.red.shade400),
                      onPressed: () => setState(() {
                        _lines.remove(l);
                        l.dispose();
                      }),
                    ),
                  ]),
                  Row(children: [
                    Expanded(
                      child: TextField(
                        controller: l.qty,
                        keyboardType: const TextInputType.numberWithOptions(decimal: true),
                        onChanged: (_) => setState(() {}),
                        decoration: InputDecoration(labelText: 'Miqdar (${l.product['unit'] ?? 'əd'})', isDense: true),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextField(
                        controller: l.cost,
                        keyboardType: const TextInputType.numberWithOptions(decimal: true),
                        onChanged: (_) => setState(() {}),
                        decoration: const InputDecoration(labelText: 'Alış qiyməti', isDense: true),
                      ),
                    ),
                    const SizedBox(width: 8),
                    SizedBox(
                      width: 90,
                      child: Text(formatMoney(l.total), textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w800)),
                    ),
                    const SizedBox(width: 8),
                  ]),
                ]),
              ),
            ),
          const SizedBox(height: 12),
          Row(children: [
            Expanded(
              child: TextField(
                controller: _paid,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                onChanged: (_) => setState(() {}),
                decoration: const InputDecoration(labelText: 'İndi ödənilən', hintText: '0'),
              ),
            ),
            const SizedBox(width: 8),
            OutlinedButton(onPressed: () => setState(() => _paid.text = _total.toStringAsFixed(2)), child: const Text('Hamısı')),
          ]),
          const SizedBox(height: 8),
          SegmentedButton<String>(
            segments: [
              const ButtonSegment(value: 'CASH', label: Text('Nağd')),
              const ButtonSegment(value: 'CARD', label: Text('Kart')),
              if (_companyId != null) const ButtonSegment(value: 'TRANSFER', label: Text('Köçürmə')),
            ],
            selected: {_method},
            onSelectionChanged: (s) => setState(() => _method = s.first),
          ),
          if (unpaid > 0)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text('Borca yazılacaq: ${formatMoney(unpaid)}', style: TextStyle(color: Colors.red.shade700, fontWeight: FontWeight.w700)),
            ),
          const SizedBox(height: 12),
          TextField(controller: _note, maxLength: 500, decoration: const InputDecoration(labelText: 'Qeyd')),
        ],
      ),
    );
  }
}
