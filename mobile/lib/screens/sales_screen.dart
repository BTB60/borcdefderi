import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';

import '../api/api_client.dart';
import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'barcode_scanner.dart';
import 'cash_screen.dart' show newRequestId;
import 'common.dart';
import 'finance_cards.dart' show pickParty;
import 'return_approval.dart';

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

  /// Sale details: receipt, return some of the goods, cancel.
  void _open(Map<String, dynamic> s) => showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        showDragHandle: true,
        useSafeArea: true,
        builder: (_) => _SaleSheet(
            session: widget.session, sale: s, onShare: () => _share(s), onCancel: () => _cancel(s), onChanged: _reload),
      );

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
    if (ok != true || !mounted) return;
    try {
      final done = await withReturnApproval(context,
          (pin) => widget.session.api.post('/api/sales/${s['id']}/cancel', {'approvalPin': pin}));
      if (done != null) _reload();
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
                      onTap: () => _open(s),
                      onLongPress: s['status'] == 'COMPLETED' ? () => _cancel(s) : null,
                    ),
                  ),
                if (items.isNotEmpty)
                  Text('Çek, qaytarma və ləğv üçün satışa toxunun.',
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

  /// Camera scanning: every recognised barcode goes straight into the cart; the scanner stays open for the next item.
  Future<void> _scan() async {
    await Navigator.of(context).push(MaterialPageRoute(
      fullscreenDialog: true,
      builder: (_) => BarcodeScannerScreen(
        title: 'Satış üçün skan',
        onCode: (code) async {
          try {
            final p = await productByBarcode(widget.session, code);
            if (p == null) return 'Tapılmadı: $code';
            if (!mounted) return null;
            _add(p);
            final line = _cart.firstWhere((l) => l.product['id'] == p['id']);
            return '✓ ${p['name']} — ${line.qty.toStringAsFixed(0)} ədəd · cəmi ${formatMoney(_subtotal)}';
          } catch (e) {
            return errorMessage(e);
          }
        },
      ),
    ));
    if (mounted) setState(() {});
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
        appBar: AppBar(title: const Text('Yeni satış'), actions: [
          IconButton(tooltip: 'Barkod skan et', icon: const Icon(Icons.qr_code_scanner), onPressed: _scan),
        ]),
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
            decoration: InputDecoration(
              prefixIcon: const Icon(Icons.search),
              hintText: 'Məhsul adı, kod və ya barkod',
              suffixIcon: IconButton(tooltip: 'Barkod skan et', icon: const Icon(Icons.qr_code_scanner), onPressed: _scan),
            ),
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
          if (_cart.isEmpty) const EmptyState(title: 'Səbət boşdur', text: 'Məhsul axtarın və ya barkodu skan edin.'),
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

/// Bottom sheet of one sale: lines with how many to return, the return itself (with manager approval when the
/// store asks for it), receipt and cancel.
class _SaleSheet extends StatefulWidget {
  const _SaleSheet({required this.session, required this.sale, required this.onShare, required this.onCancel, required this.onChanged});

  final Session session;
  final Map<String, dynamic> sale;
  final VoidCallback onShare;
  final Future<void> Function() onCancel;
  final VoidCallback onChanged;

  @override
  State<_SaleSheet> createState() => _SaleSheetState();
}

class _SaleSheetState extends State<_SaleSheet> {
  late Map<String, dynamic> _sale = widget.sale;
  final _qty = <String, TextEditingController>{};
  final _reason = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    for (final c in _qty.values) {
      c.dispose();
    }
    _reason.dispose();
    super.dispose();
  }

  List<Map<String, dynamic>> get _items => (_sale['items'] as List).cast<Map<String, dynamic>>();

  double _left(Map<String, dynamic> i) => _n(i['quantity']) - _n(i['returnedQty']);

  TextEditingController _ctrl(String id) =>
      _qty.putIfAbsent(id, () => TextEditingController()..addListener(() => setState(() {})));

  String _q(double v) => v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toString();

  List<Map<String, dynamic>> get _picked => [
        for (final i in _items)
          if (_p(_ctrl(i['id'] as String).text) > 0) {'saleItemId': i['id'], 'quantity': _p(_ctrl(i['id'] as String).text)},
      ];

  Future<void> _return() async {
    final items = _picked;
    if (items.isEmpty) return;
    for (final i in _items) {
      if (_p(_ctrl(i['id'] as String).text) > _left(i)) {
        showError(context, ApiException(0, 'RETURN_EXCEEDS', '“${i['name']}” üçün ən çox ${_q(_left(i))} qaytarmaq olar'));
        return;
      }
    }
    setState(() => _busy = true);
    try {
      final res = await withReturnApproval(
          context,
          (pin) => widget.session.api.post('/api/sales/${_sale['id']}/returns', {
                'items': items,
                'reason': _reason.text.trim().isEmpty ? null : _reason.text.trim(),
                'approvalPin': pin,
              }));
      if (res != null && mounted) {
        setState(() {
          _sale = res as Map<String, dynamic>;
          for (final c in _qty.values) {
            c.clear();
          }
        });
        widget.onChanged();
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Qaytarıldı')));
      }
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final active = _sale['status'] == 'COMPLETED';
    final returns = ((_sale['returns'] as List?) ?? const []).cast<Map<String, dynamic>>();
    return Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
        child: ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 16), children: [
          Text('Satış ${_sale['number']}', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: navy)),
          Text(
              [
                formatDateTime(DateTime.parse(_sale['createdAt'] as String)),
                if (_sale['customerName'] != null) '${_sale['customerName']}',
                if (!active) 'LƏĞV',
              ].join(' · '),
              style: TextStyle(color: Colors.grey.shade600)),
          const SizedBox(height: 12),
          for (final i in _items)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(children: [
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('${i['name']}', style: const TextStyle(fontWeight: FontWeight.w600)),
                    Text('${_q(_n(i['quantity']))} × ${formatMoney(_n(i['unitPrice']))} = ${formatMoney(_n(i['lineTotal']))}',
                        style: TextStyle(color: Colors.grey.shade700, fontSize: 13)),
                    if (_n(i['returnedQty']) > 0)
                      Text('qaytarılıb ${_q(_n(i['returnedQty']))}', style: const TextStyle(color: Colors.red, fontSize: 12)),
                  ]),
                ),
                if (active && _left(i) > 0)
                  SizedBox(
                    width: 84,
                    child: TextField(
                      controller: _ctrl(i['id'] as String),
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      textAlign: TextAlign.center,
                      decoration: InputDecoration(labelText: 'Qaytar', hintText: '≤ ${_q(_left(i))}', isDense: true),
                    ),
                  ),
              ]),
            ),
          const Divider(height: 24),
          Row(children: [
            const Expanded(child: Text('Yekun', style: TextStyle(fontWeight: FontWeight.w700))),
            Text(formatMoney(_n(_sale['total'])), style: const TextStyle(fontWeight: FontWeight.w700)),
          ]),
          for (final r in returns)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Row(children: [
                Expanded(
                  child: Text(
                      'Qaytarma ${formatDate(DateTime.tryParse('${r['returnedOn']}'))}'
                      '${r['approvedByName'] != null && r['approvedByName'] != r['createdByName'] ? ' · təsdiq: ${r['approvedByName']}' : ''}',
                      style: const TextStyle(color: Colors.red, fontSize: 13)),
                ),
                Text('−${formatMoney(_n(r['amount']))}', style: const TextStyle(color: Colors.red, fontSize: 13)),
              ]),
            ),
          if (active) ...[
            const SizedBox(height: 12),
            TextField(controller: _reason, maxLength: 500, decoration: const InputDecoration(labelText: 'Səbəb', counterText: '')),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: _busy || _picked.isEmpty ? null : _return,
              icon: const Icon(Icons.assignment_return_outlined),
              label: Text(_busy ? 'Qaytarılır…' : 'Seçilənləri qaytar'),
            ),
            const SizedBox(height: 6),
            Text('Qaytarma əvvəlcə bu satışın ödənilməmiş borcunu azaldır, qalanı kassadan pul kimi qaytarılır.',
                style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
          ],
          const SizedBox(height: 12),
          Row(children: [
            Expanded(
              child: OutlinedButton.icon(onPressed: widget.onShare, icon: const Icon(Icons.share_outlined), label: const Text('Çek')),
            ),
            if (active) ...[
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(foregroundColor: Colors.red),
                  onPressed: _busy
                      ? null
                      : () async {
                          Navigator.of(context).pop();
                          await widget.onCancel();
                        },
                  icon: const Icon(Icons.cancel_outlined),
                  label: const Text('Satışı ləğv et'),
                ),
              ),
            ],
          ]),
        ]),
    );
  }
}
