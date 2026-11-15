import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';

import '../format.dart';
import '../state/receipt_printer.dart';
import '../state/session.dart';
import '../theme.dart';
import 'barcode_scanner.dart';
import 'cash_screen.dart' show newRequestId;
import 'common.dart';
import 'swipe_actions.dart';
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
    final printer = ReceiptPrinter.instance;
    await printer.load();
    if (printer.autoPrint) {
      try {
        await printer.printSale(widget.session, sale);
        return;
      } catch (e) {
        if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Çek çap olunmadı: ${errorMessage(e)}')));
      }
    }
    if (mounted) _share(sale);
  }

  Future<void> _printerSettings() async {
    Map<String, dynamic>? last;
    try {
      final items = ((await _data)['items'] as List).cast<Map<String, dynamic>>();
      if (items.isNotEmpty) last = items.first;
    } catch (_) {}
    if (mounted) await showReceiptPrinterSheet(context, widget.session, lastSale: last);
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

  /// Return some or all goods of a sale: stock comes back, the sale's unpaid debt drops first, the rest is refunded.
  Future<void> _return(Map<String, dynamic> s) async {
    final msg = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (_) => _ReturnSheet(session: widget.session, saleId: '${s['id']}'),
    );
    if (msg == null || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
    _reload();
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
        appBar: AppBar(title: const Text('Satış'), actions: [
          IconButton(tooltip: 'Çek printeri', icon: const Icon(Icons.print_outlined), onPressed: _printerSettings),
        ]),
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
                    clipBehavior: Clip.antiAlias,
                    child: SwipeActions(
                      actions: [
                        if (s['status'] == 'COMPLETED' && _n(s['returnedAmount']) < _n(s['total']))
                          SwipeAction(
                            icon: Icons.assignment_return_outlined,
                            label: 'Qaytar',
                            color: const Color(0xFFEA580C),
                            onTap: () => _return(s),
                          ),
                        if (s['status'] == 'COMPLETED' && widget.session.me!.mayChange(s['createdBy']))
                          SwipeAction.delete(() => _cancel(s), label: 'Ləğv et'),
                      ],
                      child: ListTile(
                      title: Text('${s['number']}${s['customerName'] != null ? ' · ${s['customerName']}' : ''}',
                          style: const TextStyle(fontWeight: FontWeight.w600)),
                      subtitle: Text([
                        formatDateTime(DateTime.parse(s['createdAt'] as String)),
                        if (_n(s['creditAmount']) > 0) 'borca ${formatMoney(_n(s['creditAmount']))}',
                        if (s['status'] == 'CANCELLED') 'LƏĞV',
                        if (_n(s['returnedAmount']) > 0 && s['status'] != 'CANCELLED') 'qaytarılıb ${formatMoney(_n(s['returnedAmount']))}',
                      ].join(' · ')),
                      trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                        Text(formatMoney(_n(s['total'])),
                            style: TextStyle(
                                fontWeight: FontWeight.w800,
                                decoration: s['status'] == 'CANCELLED' ? TextDecoration.lineThrough : null)),
                        if (s['status'] == 'COMPLETED' && widget.session.me!.mayChange(s['createdBy']))
                          IconButton(
                            tooltip: 'Ləğv et',
                            visualDensity: VisualDensity.compact,
                            icon: Icon(Icons.delete_outline, color: Colors.red.shade400),
                            onPressed: () => _cancel(s),
                          ),
                      ]),
                      onTap: () => _share(s),
                      onLongPress: s['status'] == 'COMPLETED' && widget.session.me!.mayChange(s['createdBy']) ? () => _cancel(s) : null,
                    ),
                    ),
                  ),
                if (items.isNotEmpty)
                  Text('Çeki paylaşmaq üçün toxunun. Mal qaytarmaq və ya satışı ləğv etmək üçün sətri sola sürüşdürün.',
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
  double get _total => _r((_subtotal - _p(_discount.text)).clamp(0, double.infinity).toDouble());
  double get _cardPaid => _p(_card.text);

  /// Cash the customer handed over (may be more than owed, e.g. 10 ₼ for 4 ₼).
  double get _tendered => _p(_cash.text);

  /// Cash that pays the sale: never more than what is left after the card part.
  double get _cashPaid => _r(_tendered.clamp(0, (_total - _cardPaid).clamp(0, double.infinity)).toDouble());

  /// Change to give back to the customer.
  double get _change => _r((_tendered - _cashPaid).clamp(0, double.infinity).toDouble());
  double get _credit => _r((_total - _cashPaid - _cardPaid).clamp(0, double.infinity).toDouble());

  static double _r(double v) => (v * 100).roundToDouble() / 100;
  static String _amount(double v) => v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toStringAsFixed(2);

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
    if (_cardPaid > _total + 0.001) {
      setState(() => _error = 'Kartla ödənilən məbləğ yekundan çoxdur');
      return;
    }
    if (_credit > 0 && _customerId == null) {
      setState(() => _error = 'Ödənilməyən ${formatMoney(_credit)} üçün müştəri seçin');
      return;
    }
    final change = _change;
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
        'paidCash': _cashPaid.toStringAsFixed(2),
        'paidCard': _cardPaid.toStringAsFixed(2),
        'clientRequestId': _requestId,
      }) as Map<String, dynamic>;
      if (!mounted) return;
      if (change > 0) {
        await showDialog<void>(
          context: context,
          builder: (ctx) => AlertDialog(
            icon: Icon(Icons.payments_outlined, color: Colors.green.shade700, size: 40),
            title: const Text('Satış tamamlandı'),
            content: Column(mainAxisSize: MainAxisSize.min, children: [
              Text('Müştəri verdi: ${formatMoney(_tendered)}', style: TextStyle(color: Colors.grey.shade700)),
              const SizedBox(height: 12),
              const Text('Qaytarın'),
              Text(formatMoney(change), style: TextStyle(fontSize: 34, fontWeight: FontWeight.w800, color: Colors.green.shade700)),
            ]),
            actions: [FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('Oldu'))],
          ),
        );
      }
      if (mounted) Navigator.pop(context, s);
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Widget _section(String title, {Widget? trailing}) => Padding(
        padding: const EdgeInsets.fromLTRB(4, 18, 4, 8),
        child: Row(children: [
          Expanded(
            child: Text(title.toUpperCase(),
                style: TextStyle(fontSize: 12, letterSpacing: 0.8, fontWeight: FontWeight.w700, color: Colors.grey.shade600)),
          ),
          ?trailing,
        ]),
      );

  Widget _sumRow(String label, String value, {bool big = false, Color? color}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(children: [
          Expanded(
            child: Text(label,
                style: TextStyle(fontSize: big ? 18 : 15, fontWeight: big ? FontWeight.w800 : FontWeight.w500, color: color ?? navy)),
          ),
          Text(value, style: TextStyle(fontSize: big ? 22 : 15, fontWeight: big ? FontWeight.w800 : FontWeight.w600, color: color ?? navy)),
        ]),
      );

  /// Coloured result box (change to give back / amount that goes on the customer's debt).
  Widget _notice(IconData icon, String label, String value, MaterialColor color) => Container(
        margin: const EdgeInsets.only(top: 12),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: color.shade50,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: color.shade200),
        ),
        child: Row(children: [
          Icon(icon, color: color.shade800),
          const SizedBox(width: 10),
          Expanded(child: Text(label, style: TextStyle(color: color.shade900, fontWeight: FontWeight.w600))),
          Text(value, style: TextStyle(color: color.shade800, fontSize: 22, fontWeight: FontWeight.w800)),
        ]),
      );

  static const _decimal = TextInputType.numberWithOptions(decimal: true);

  @override
  Widget build(BuildContext context) {
    final change = _change;
    final credit = _credit;
    return Scaffold(
      appBar: AppBar(title: const Text('Yeni satış'), actions: [
        IconButton(tooltip: 'Barkod skan et', icon: const Icon(Icons.qr_code_scanner), onPressed: _scan),
      ]),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
          child: SizedBox(
            height: 54,
            child: FilledButton.icon(
              style: FilledButton.styleFrom(backgroundColor: gold, foregroundColor: navy),
              onPressed: _busy || _cart.isEmpty ? null : _submit,
              icon: _busy
                  ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.check_circle_outline),
              label: Text(_busy ? 'Saxlanılır…' : 'Satışı tamamla — ${formatMoney(_total)}',
                  style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
            ),
          ),
        ),
      ),
      body: ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 24), children: [
        TextField(
          controller: _search,
          decoration: InputDecoration(
            prefixIcon: const Icon(Icons.search),
            hintText: 'Məhsul adı, kod və ya barkod',
            suffixIcon: IconButton(tooltip: 'Barkod skan et', icon: const Icon(Icons.qr_code_scanner), onPressed: _scan),
          ),
          onChanged: _find,
        ),
        if (_results.isNotEmpty)
          Card(
            margin: const EdgeInsets.only(top: 6),
            child: Column(children: [
              for (final p in _results)
                ListTile(
                  leading: const Icon(Icons.add_shopping_cart_outlined),
                  title: Text('${p['name']}', style: const TextStyle(fontWeight: FontWeight.w600)),
                  subtitle: Text('${p['code']} · anbarda ${_amount(_n(p['stockQty']))} ${p['unit'] ?? ''}'),
                  trailing: Text(formatMoney(_n(p['salePrice'])), style: const TextStyle(fontWeight: FontWeight.w700)),
                  onTap: () => _add(p),
                ),
            ]),
          ),

        // ----- cart -----
        _section('Səbət${_cart.isEmpty ? '' : ' (${_cart.length})'}'),
        if (_cart.isEmpty)
          Container(
            padding: const EdgeInsets.symmetric(vertical: 28),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Colors.grey.shade300),
            ),
            child: Column(children: [
              Icon(Icons.shopping_cart_outlined, size: 40, color: Colors.grey.shade400),
              const SizedBox(height: 8),
              const Text('Səbət boşdur', style: TextStyle(fontWeight: FontWeight.w700, color: navy)),
              const SizedBox(height: 2),
              Text('Məhsul axtarın və ya barkodu skan edin', style: TextStyle(color: Colors.grey.shade600)),
            ]),
          ),
        for (final l in _cart)
          Card(
            margin: const EdgeInsets.only(bottom: 8),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
              child: Row(children: [
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('${l.product['name']}', style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
                    const SizedBox(height: 2),
                    Text(
                      '${formatMoney(l.price)} / ${l.product['unit'] ?? 'ədəd'}'
                      '${l.qty > _n(l.product['stockQty']) ? ' · anbarda ${_amount(_n(l.product['stockQty']))}' : ''}',
                      style: TextStyle(fontSize: 12, color: l.qty > _n(l.product['stockQty']) ? Colors.red : Colors.grey.shade700),
                    ),
                    const SizedBox(height: 4),
                    Text(formatMoney(l.total), style: const TextStyle(fontWeight: FontWeight.w800, color: navy)),
                  ]),
                ),
                Container(
                  decoration: BoxDecoration(color: Colors.grey.shade100, borderRadius: BorderRadius.circular(24)),
                  child: Row(mainAxisSize: MainAxisSize.min, children: [
                    IconButton(
                      tooltip: l.qty > 1 ? 'Azalt' : 'Səbətdən çıxar',
                      onPressed: () => setState(() => l.qty > 1 ? l.qty -= 1 : _cart.remove(l)),
                      icon: Icon(l.qty > 1 ? Icons.remove : Icons.delete_outline, color: l.qty > 1 ? navy : Colors.red.shade700),
                    ),
                    Text(_amount(l.qty), style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                    IconButton(tooltip: 'Artır', onPressed: () => setState(() => l.qty += 1), icon: const Icon(Icons.add, color: navy)),
                  ]),
                ),
              ]),
            ),
          ),

        // ----- totals -----
        if (_cart.isNotEmpty) ...[
          _section('Hesab'),
          Card(
            margin: EdgeInsets.zero,
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(children: [
                _sumRow('Ara cəm', formatMoney(_subtotal)),
                const SizedBox(height: 8),
                TextField(
                  controller: _discount,
                  keyboardType: _decimal,
                  decoration: const InputDecoration(labelText: 'Endirim', suffixText: '₼', prefixIcon: Icon(Icons.local_offer_outlined)),
                  onChanged: (_) => setState(() {}),
                ),
                const Divider(height: 24),
                _sumRow('Yekun', formatMoney(_total), big: true),
              ]),
            ),
          ),
        ],

        // ----- payment -----
        _section('Ödəniş'),
        Wrap(spacing: 8, runSpacing: 8, children: [
          ActionChip(
            avatar: const Icon(Icons.payments_outlined, size: 18),
            label: const Text('Hamısı nağd'),
            onPressed: () => setState(() {
              _cash.text = _total.toStringAsFixed(2);
              _card.clear();
            }),
          ),
          ActionChip(
            avatar: const Icon(Icons.credit_card, size: 18),
            label: const Text('Hamısı kart'),
            onPressed: () => setState(() {
              _card.text = _total.toStringAsFixed(2);
              _cash.clear();
            }),
          ),
          ActionChip(
            avatar: const Icon(Icons.person_outline, size: 18),
            label: const Text('Tam borca'),
            onPressed: () => setState(() {
              _cash.clear();
              _card.clear();
            }),
          ),
        ]),
        const SizedBox(height: 12),
        TextField(
          controller: _cash,
          keyboardType: _decimal,
          decoration: const InputDecoration(
            labelText: 'Müştəri verdi (nağd)',
            suffixText: '₼',
            prefixIcon: Icon(Icons.payments_outlined),
          ),
          onChanged: (_) => setState(() {}),
        ),
        const SizedBox(height: 8),
        // quick banknotes: tap to set what the customer handed over
        Wrap(spacing: 8, runSpacing: 4, children: [
          for (final note in const [5, 10, 20, 50, 100])
            if (note >= _total - _cardPaid)
              ChoiceChip(
                label: Text('$note ₼'),
                selected: _tendered == note,
                onSelected: (_) => setState(() => _cash.text = '$note'),
              ),
        ]),
        const SizedBox(height: 12),
        TextField(
          controller: _card,
          keyboardType: _decimal,
          decoration: const InputDecoration(labelText: 'Kartla', suffixText: '₼', prefixIcon: Icon(Icons.credit_card)),
          onChanged: (_) => setState(() {}),
        ),
        if (_cart.isNotEmpty && change > 0)
          _notice(Icons.currency_exchange, 'Müştəriyə qaytarın', formatMoney(change), Colors.green),
        if (_cart.isNotEmpty && credit > 0)
          _notice(Icons.account_balance_wallet_outlined,
              _customerId == null ? 'Ödənilməyən hissə — müştəri seçin' : 'Borca yazılacaq: $_customerName', formatMoney(credit),
              _customerId == null ? Colors.red : Colors.orange),

        // ----- customer -----
        _section('Müştəri'),
        Card(
          margin: EdgeInsets.zero,
          child: ListTile(
            leading: CircleAvatar(
              backgroundColor: navy.withValues(alpha: 0.08),
              child: const Icon(Icons.person_outline, color: navy),
            ),
            title: Text(_customerName ?? 'Müştəri seçin', style: const TextStyle(fontWeight: FontWeight.w600)),
            subtitle: Text(_customerName == null ? 'Borcla satış üçün lazımdır' : 'Seçildi'),
            trailing: _customerId == null
                ? const Icon(Icons.chevron_right)
                : IconButton(
                    tooltip: 'Müştərini sil',
                    icon: const Icon(Icons.clear),
                    onPressed: () => setState(() {
                      _customerId = null;
                      _customerName = null;
                    }),
                  ),
            onTap: _pickCustomer,
          ),
        ),
        if (_error != null) ...[const SizedBox(height: 12), ErrorBanner(_error!)],
      ]),
    );
  }
}

/// Return sheet: how many of each line come back (up to what is not returned yet), an optional reason.
class _ReturnSheet extends StatefulWidget {
  const _ReturnSheet({required this.session, required this.saleId});

  final Session session;
  final String saleId;

  @override
  State<_ReturnSheet> createState() => _ReturnSheetState();
}

class _ReturnSheetState extends State<_ReturnSheet> {
  Map<String, dynamic>? _sale;
  final Map<String, double> _qty = {};
  final _reason = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final sale = await widget.session.api.get('/api/sales/${widget.saleId}') as Map<String, dynamic>;
      if (mounted) setState(() => _sale = sale);
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    }
  }

  List<Map<String, dynamic>> get _items => ((_sale?['items'] as List?) ?? const []).cast<Map<String, dynamic>>();

  static double _left(Map<String, dynamic> i) => _n(i['quantity']) - _n(i['returnedQty']);

  /// Price actually charged per unit (line discount included).
  static double _unit(Map<String, dynamic> i) => _n(i['quantity']) == 0 ? 0 : _n(i['lineTotal']) / _n(i['quantity']);

  static String _q(double v) => v == v.roundToDouble() ? v.toInt().toString() : v.toStringAsFixed(3);

  double get _total => _items.fold(0, (a, i) => a + (_qty['${i['id']}'] ?? 0) * _unit(i));

  void _set(Map<String, dynamic> i, double v) => setState(() => _qty['${i['id']}'] = v.clamp(0, _left(i)).toDouble());

  Future<void> _save() async {
    final items = [
      for (final i in _items)
        if ((_qty['${i['id']}'] ?? 0) > 0) {'saleItemId': i['id'], 'quantity': _qty['${i['id']}']},
    ];
    if (items.isEmpty) {
      setState(() => _error = 'Qaytarılan malın sayını seçin');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final res = await widget.session.api.post('/api/sales/${widget.saleId}/returns',
          {'items': items, 'reason': _reason.text.trim().isEmpty ? null : _reason.text.trim()}) as Map<String, dynamic>;
      final returns = ((res['returns'] as List?) ?? const []).cast<Map<String, dynamic>>();
      final last = returns.isEmpty ? null : returns.last;
      final parts = [
        'Qaytarıldı: ${formatMoney(last == null ? _total : _n(last['amount']))}',
        if (_n(last?['debtReduced']) > 0) 'borc azaldı ${formatMoney(_n(last?['debtReduced']))}',
        if (_n(last?['refunded']) > 0) 'müştəriyə pul qaytarın: ${formatMoney(_n(last?['refunded']))}',
      ];
      if (mounted) Navigator.of(context).pop(parts.join(' · '));
    } catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = errorMessage(e);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final sale = _sale;
    if (sale == null) {
      return Padding(
        padding: const EdgeInsets.all(24),
        child: _error != null ? ErrorBanner(_error!) : const Center(child: CircularProgressIndicator()),
      );
    }
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 0, 16, 16 + MediaQuery.of(context).viewInsets.bottom),
      child: ListView(shrinkWrap: true, children: [
        Text('Mal qaytarma · ${sale['number']}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: navy)),
        if (sale['customerName'] != null) Text('${sale['customerName']}', style: TextStyle(color: Colors.grey.shade600)),
        const SizedBox(height: 12),
        for (final i in _items)
          Card(
            margin: const EdgeInsets.only(bottom: 8),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
              child: Row(children: [
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('${i['name']}', maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600)),
                    Text(
                      'Satılıb ${_q(_n(i['quantity']))} × ${formatMoney(_unit(i))}'
                      '${_n(i['returnedQty']) > 0 ? ' · qaytarılıb ${_q(_n(i['returnedQty']))}' : ''}',
                      style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
                    ),
                  ]),
                ),
                if (_left(i) <= 0)
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: Text('Qaytarılıb', style: TextStyle(color: Colors.grey.shade600)),
                  )
                else ...[
                  IconButton(
                    visualDensity: VisualDensity.compact,
                    onPressed: (_qty['${i['id']}'] ?? 0) > 0 ? () => _set(i, (_qty['${i['id']}'] ?? 0) - 1) : null,
                    icon: const Icon(Icons.remove_circle_outline),
                  ),
                  SizedBox(
                    width: 40,
                    child: Text(_q(_qty['${i['id']}'] ?? 0),
                        textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                  ),
                  IconButton(
                    visualDensity: VisualDensity.compact,
                    onPressed: (_qty['${i['id']}'] ?? 0) < _left(i) ? () => _set(i, (_qty['${i['id']}'] ?? 0) + 1) : null,
                    icon: const Icon(Icons.add_circle_outline),
                  ),
                  TextButton(onPressed: () => _set(i, _left(i)), child: const Text('Hamısı')),
                ],
              ]),
            ),
          ),
        const SizedBox(height: 4),
        TextField(controller: _reason, maxLength: 500, decoration: const InputDecoration(labelText: 'Səbəb (istəyə bağlı)')),
        Text('Mal anbara qayıdır. Satışın ödənilməmiş borcu əvvəlcə azalır, qalanı müştəriyə pul kimi qaytarılır.',
            style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
        if (_error != null) Padding(padding: const EdgeInsets.only(top: 8), child: ErrorBanner(_error!)),
        const SizedBox(height: 12),
        SizedBox(
          height: 52,
          child: FilledButton.icon(
            style: FilledButton.styleFrom(backgroundColor: const Color(0xFFEA580C)),
            onPressed: _busy || _total <= 0 ? null : _save,
            icon: const Icon(Icons.assignment_return_outlined),
            label: Text(_busy ? 'Saxlanılır…' : 'Qaytar · ${formatMoney(_total)}', style: const TextStyle(fontSize: 16)),
          ),
        ),
      ]),
    );
  }
}
