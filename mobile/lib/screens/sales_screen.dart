import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'cash_screen.dart' show newRequestId;
import 'common.dart';
import 'finance_cards.dart' show pickParty;

double _n(Object? v) => (v as num?)?.toDouble() ?? 0;
double _p(String s) => double.tryParse(s.trim().replaceAll(',', '.')) ?? 0;

/// Satış: today's sales list and the new-sale screen.
class SalesScreen extends StatefulWidget {
  const SalesScreen({super.key, required this.session});

  final Session session;

  @override
  State<SalesScreen> createState() => _SalesScreenState();
}

class _SalesScreenState extends State<SalesScreen> {
  late Future<Map<String, dynamic>> _data = _fetch();

  Future<Map<String, dynamic>> _fetch() {
    final now = DateTime.now();
    final from = '${now.year}-${now.month.toString().padLeft(2, '0')}-01';
    return widget.session.api.get('/api/sales', query: {'from': from, 'size': '100'}).then((j) => j as Map<String, dynamic>);
  }

  void _reload() => setState(() => _data = _fetch());

  Future<void> _new() async {
    final sale = await Navigator.of(context).push<Map<String, dynamic>>(MaterialPageRoute(builder: (_) => NewSaleScreen(session: widget.session)));
    if (sale == null || !mounted) return;
    _reload();
    _share(sale);
  }

  Future<void> _share(Map<String, dynamic> s) async {
    try {
      final bytes = await widget.session.api.getBytes('/api/sales/${s['id']}/receipt');
      final name = 'cek-${s['number']}.pdf';
      await SharePlus.instance.share(ShareParams(
        files: [XFile.fromData(Uint8List.fromList(bytes), mimeType: 'application/pdf', name: name)],
        fileNameOverrides: [name],
      ));
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _cancel(Map<String, dynamic> s) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('${s['number']} ləğv edilsin?'),
        content: const Text('Mallar anbara qayıdır, borc azalır, ödənilən pul kassadan qaytarılır.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Xeyr')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Ləğv et')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await widget.session.api.post('/api/sales/${s['id']}/cancel', {});
      _reload();
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Satış')),
        floatingActionButton: FloatingActionButton.extended(
            onPressed: _new, icon: const Icon(Icons.add_shopping_cart), label: const Text('Yeni satış')),
        body: FutureBuilder<Map<String, dynamic>>(
          future: _data,
          builder: (context, snap) {
            if (snap.hasError) return ListView(children: [Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!)))]);
            if (!snap.hasData) return const Center(child: CircularProgressIndicator());
            final items = (snap.data!['items'] as List).cast<Map<String, dynamic>>();
            return RefreshIndicator(
              onRefresh: () async => _reload(),
              child: ListView(padding: const EdgeInsets.fromLTRB(12, 12, 12, 96), children: [
                StatTile(label: 'Bu ayın satışları (xalis)', value: formatMoney(_n(snap.data!['sum'])), hint: '${snap.data!['total']} satış'),
                const SizedBox(height: 8),
                if (items.isEmpty) const EmptyState(title: 'Bu ay satış yoxdur'),
                for (final s in items)
                  Card(
                    child: ListTile(
                      title: Text('${s['number']}${s['customerName'] != null ? ' · ${s['customerName']}' : ''}',
                          style: const TextStyle(fontWeight: FontWeight.w600)),
                      subtitle: Text([
                        formatDateTime(DateTime.parse(s['createdAt'] as String)),
                        if (_n(s['creditAmount']) > 0) 'borca ${formatMoney(_n(s['creditAmount']))}',
                        if (s['status'] == 'CANCELLED') 'LƏĞV',
                        if (_n(s['returnedAmount']) > 0 && s['status'] != 'CANCELLED') 'qaytarılıb ${formatMoney(_n(s['returnedAmount']))}',
                      ].join(' · ')),
                      trailing: Text(formatMoney(_n(s['total'])),
                          style: TextStyle(
                              fontWeight: FontWeight.w800,
                              decoration: s['status'] == 'CANCELLED' ? TextDecoration.lineThrough : null)),
                      onTap: () => _share(s),
                      onLongPress: s['status'] == 'COMPLETED' ? () => _cancel(s) : null,
                    ),
                  ),
                if (items.isNotEmpty)
                  Text('Çeki paylaşmaq üçün toxunun, ləğv etmək üçün basıb saxlayın. Qismən qaytarma veb paneldədir.',
                      style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
              ]),
            );
          },
        ),
      );
}

class _Line {
  _Line(this.product) : qty = 1, price = _n(product['salePrice']);
  final Map<String, dynamic> product;
  double qty;
  double price;
  double get total => (qty * price * 100).roundToDouble() / 100;
}

/// New sale: search products, cart, discount, cash / card / credit (customer).
class NewSaleScreen extends StatefulWidget {
  const NewSaleScreen({super.key, required this.session});

  final Session session;

  @override
  State<NewSaleScreen> createState() => _NewSaleScreenState();
}

class _NewSaleScreenState extends State<NewSaleScreen> {
  final _search = TextEditingController();
  final _discount = TextEditingController();
  final _cash = TextEditingController();
  final _card = TextEditingController();
  final List<_Line> _cart = [];
  List<Map<String, dynamic>> _results = [];
  String? _customerId;
  String? _customerName;
  final _requestId = newRequestId();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    for (final c in [_search, _discount, _cash, _card]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _find(String q) async {
    if (q.trim().isEmpty) {
      setState(() => _results = []);
      return;
    }
    try {
      final r = (await widget.session.api.get('/api/products', query: {'active': 'true', 'q': q.trim()}) as List).cast<Map<String, dynamic>>();
      if (mounted) setState(() => _results = r.take(8).toList());
    } catch (_) {}
  }

  void _add(Map<String, dynamic> p) {
    setState(() {
      final i = _cart.indexWhere((l) => l.product['id'] == p['id']);
      if (i >= 0) {
        _cart[i].qty += 1;
      } else {
        _cart.add(_Line(p));
      }
      _results = [];
      _search.clear();
    });
  }

  double get _subtotal => _cart.fold<double>(0, (s, l) => s + l.total);
  double get _total => ((_subtotal - _p(_discount.text)).clamp(0, double.infinity) * 100).roundToDouble() / 100;
  double get _paid => _p(_cash.text) + _p(_card.text);
  double get _credit => ((_total - _paid) * 100).roundToDouble() / 100;

  Future<void> _pickCustomer() async {
    final id = await pickParty(context, session: widget.session, isCustomer: true);
    if (id == null) return;
    try {
      final p = await widget.session.api.get('/api/customers/$id/profile') as Map<String, dynamic>;
      setState(() {
        _customerId = id;
        _customerName = (p['party'] as Map)['name'] as String?;
      });
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _submit() async {
    if (_busy || _cart.isEmpty) return;
    if (_paid > _total + 0.001) {
      setState(() => _error = 'Ödənilən məbləğ yekundan çoxdur');
      return;
    }
    if (_credit > 0 && _customerId == null) {
      setState(() => _error = 'Ödənilməyən hissə üçün müştəri seçin');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final s = await widget.session.api.post('/api/sales', {
        'items': [
          for (final l in _cart) {'productId': l.product['id'], 'quantity': l.qty.toString(), 'unitPrice': l.price.toStringAsFixed(2)}
        ],
        'customerId': _customerId,
        'discount': _p(_discount.text).toStringAsFixed(2),
        'paidCash': _p(_cash.text).toStringAsFixed(2),
        'paidCard': _p(_card.text).toStringAsFixed(2),
        'clientRequestId': _requestId,
      }) as Map<String, dynamic>;
      if (mounted) Navigator.pop(context, s);
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Yeni satış')),
        bottomNavigationBar: SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
            child: FilledButton(
              style: FilledButton.styleFrom(backgroundColor: gold, foregroundColor: navy, padding: const EdgeInsets.symmetric(vertical: 16)),
              onPressed: _busy || _cart.isEmpty ? null : _submit,
              child: Text(_busy ? 'Saxlanılır…' : 'Satışı tamamla — ${formatMoney(_total)}'),
            ),
          ),
        ),
        body: ListView(padding: const EdgeInsets.fromLTRB(12, 12, 12, 24), children: [
          TextField(
            controller: _search,
            decoration: const InputDecoration(prefixIcon: Icon(Icons.search), hintText: 'Məhsul adı, kod və ya barkod'),
            onChanged: _find,
          ),
          for (final p in _results)
            ListTile(
              dense: true,
              title: Text('${p['name']}'),
              subtitle: Text('${p['code']} · qalıq ${p['stockQty']}'),
              trailing: Text(formatMoney(_n(p['salePrice']))),
              onTap: () => _add(p),
            ),
          const SizedBox(height: 8),
          if (_cart.isEmpty) const EmptyState(title: 'Səbət boşdur', text: 'Məhsul axtarıb əlavə edin.'),
          for (final l in _cart)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(10),
                child: Row(children: [
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text('${l.product['name']}', style: const TextStyle(fontWeight: FontWeight.w600)),
                      Text('${formatMoney(l.price)} × ${l.qty}${l.qty > _n(l.product['stockQty']) ? '  (anbarda ${l.product['stockQty']})' : ''}',
                          style: TextStyle(fontSize: 12, color: l.qty > _n(l.product['stockQty']) ? Colors.red : Colors.grey.shade700)),
                    ]),
                  ),
                  IconButton(onPressed: () => setState(() => l.qty > 1 ? l.qty -= 1 : _cart.remove(l)), icon: const Icon(Icons.remove_circle_outline)),
                  Text('${l.qty}'),
                  IconButton(onPressed: () => setState(() => l.qty += 1), icon: const Icon(Icons.add_circle_outline)),
                  SizedBox(width: 72, child: Text(formatMoney(l.total), textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w700))),
                ]),
              ),
            ),
          const Divider(),
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.person_outline),
            title: Text(_customerName ?? 'Müştəri seçin (borcla satış üçün)'),
            trailing: _customerId == null ? const Icon(Icons.chevron_right) : IconButton(icon: const Icon(Icons.clear), onPressed: () => setState(() {
                  _customerId = null;
                  _customerName = null;
                })),
            onTap: _pickCustomer,
          ),
          TextField(
              controller: _discount,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(labelText: 'Ümumi endirim (₼)'),
              onChanged: (_) => setState(() {})),
          Row(children: [
            Expanded(
                child: TextField(
                    controller: _cash,
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    decoration: const InputDecoration(labelText: 'Nağd (₼)'),
                    onChanged: (_) => setState(() {}))),
            const SizedBox(width: 10),
            Expanded(
                child: TextField(
                    controller: _card,
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    decoration: const InputDecoration(labelText: 'Kart (₼)'),
                    onChanged: (_) => setState(() {}))),
          ]),
          Wrap(spacing: 6, children: [
            ActionChip(label: const Text('Hamısı nağd'), onPressed: () => setState(() {
                  _cash.text = _total.toStringAsFixed(2);
                  _card.clear();
                })),
            ActionChip(label: const Text('Hamısı kart'), onPressed: () => setState(() {
                  _card.text = _total.toStringAsFixed(2);
                  _cash.clear();
                })),
            ActionChip(label: const Text('Tam borca'), onPressed: () => setState(() {
                  _cash.clear();
                  _card.clear();
                })),
          ]),
          const SizedBox(height: 8),
          if (_credit > 0)
            Text(_customerId == null
                ? 'Ödənilməyən ${formatMoney(_credit)} üçün müştəri seçin'
                : '${formatMoney(_credit)} $_customerName adlı müştərinin borcuna yazılacaq',
                style: TextStyle(color: _customerId == null ? Colors.red.shade700 : Colors.orange.shade800)),
          if (_error != null) ErrorBanner(_error!),
        ]),
      );
}
