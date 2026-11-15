import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../api/api_client.dart';
import 'session.dart';

/// Sales without internet. The product list is kept on the phone; a sale made while the server can't be reached
/// is saved in a queue and sent by itself when the connection is back. Every queued sale carries its
/// clientRequestId, so sending it twice still records it once on the server.
class OfflineSales extends ChangeNotifier {
  OfflineSales._(this._session) {
    _timer = Timer.periodic(const Duration(seconds: 30), (_) => sync());
    _load();
  }

  static final Map<String, OfflineSales> _byUser = {};

  /// One queue per signed-in user (another account on the same phone never sees it).
  static OfflineSales of(Session session) {
    final key = session.me?.userId ?? '-';
    return _byUser.putIfAbsent(key, () => OfflineSales._(session));
  }

  final Session _session;
  late final Timer _timer;
  List<Map<String, dynamic>> _queue = [];
  bool _syncing = false;
  bool _loaded = false;

  String get _key => 'offline_sales_v1_${_session.me?.userId ?? '-'}';
  String get _productsKey => 'product_cache_v1_${_session.me?.userId ?? '-'}';

  /// Sales waiting to be sent: {payload, savedAt, total, error?}.
  List<Map<String, dynamic>> get queue => List.unmodifiable(_queue);
  int get pending => _queue.length;
  bool get syncing => _syncing;

  static bool isOffline(Object e) => e is ApiException && e.status == 0;

  Future<void> _load() async {
    if (_loaded) return;
    final p = await SharedPreferences.getInstance();
    final raw = p.getString(_key);
    _queue = raw == null ? [] : (jsonDecode(raw) as List).cast<Map<String, dynamic>>();
    _loaded = true;
    notifyListeners();
    if (_queue.isNotEmpty) unawaited(sync());
  }

  Future<void> _save() async {
    final p = await SharedPreferences.getInstance();
    await p.setString(_key, jsonEncode(_queue));
    notifyListeners();
  }

  /// Saves a sale that could not be sent; the stock on the phone goes down so the next offline sale sees it.
  Future<void> enqueue(Map<String, dynamic> payload, double total, String? customerName) async {
    await _load();
    _queue.add({
      'payload': payload,
      'savedAt': DateTime.now().toIso8601String(),
      'total': total,
      'customerName': customerName,
    });
    await _save();
    final products = await cachedProducts();
    if (products.isNotEmpty) {
      for (final it in (payload['items'] as List).cast<Map<String, dynamic>>()) {
        final p = products.where((x) => x['id'] == it['productId']).firstOrNull;
        if (p != null) p['stockQty'] = ((p['stockQty'] as num?) ?? 0) - (double.tryParse('${it['quantity']}') ?? 0);
      }
      await _writeProducts(products);
    }
  }

  /// Sends the queued sales in order. A sale the server refuses (e.g. not enough stock) stays with its error,
  /// so the seller can fix or drop it; a network failure just stops until the next try.
  Future<int> sync() async {
    await _load();
    if (_syncing || _queue.isEmpty || _session.me == null) return 0;
    _syncing = true;
    notifyListeners();
    var sent = 0;
    try {
      for (final item in List<Map<String, dynamic>>.from(_queue)) {
        try {
          await _session.api.post('/api/sales', item['payload']);
          _queue.remove(item);
          sent++;
        } on ApiException catch (e) {
          if (e.status == 0) break; // still offline
          item['error'] = e.message;
        }
        await _save();
      }
      if (sent > 0) unawaited(refreshProducts());
    } finally {
      _syncing = false;
      notifyListeners();
    }
    return sent;
  }

  Future<void> remove(Map<String, dynamic> item) async {
    _queue.remove(item);
    await _save();
  }

  // ----- product cache -----

  Future<List<Map<String, dynamic>>> refreshProducts() async {
    final list = ((await _session.api.get('/api/products', query: {'active': 'true'})) as List).cast<Map<String, dynamic>>();
    await _writeProducts(list);
    return list;
  }

  Future<void> _writeProducts(List<Map<String, dynamic>> list) async {
    final p = await SharedPreferences.getInstance();
    await p.setString(_productsKey, jsonEncode(list));
  }

  Future<List<Map<String, dynamic>>> cachedProducts() async {
    final p = await SharedPreferences.getInstance();
    final raw = p.getString(_productsKey);
    return raw == null ? [] : (jsonDecode(raw) as List).cast<Map<String, dynamic>>();
  }

  /// Offline search: name contains the text, or code / barcode equals it.
  Future<List<Map<String, dynamic>>> search(String q) async {
    final t = q.trim().toLowerCase();
    if (t.isEmpty) return [];
    return (await cachedProducts())
        .where((p) => '${p['name']}'.toLowerCase().contains(t) || '${p['code']}'.toLowerCase() == t || '${p['barcode']}' == q.trim())
        .take(8)
        .toList();
  }

  Future<Map<String, dynamic>?> byCode(String code) async =>
      (await cachedProducts()).where((p) => p['barcode'] == code || p['code'] == code).firstOrNull;

  @override
  void dispose() {
    _timer.cancel();
    super.dispose();
  }
}
