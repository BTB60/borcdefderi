import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../api/api_client.dart';
import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'barcode_scanner.dart';
import 'common.dart';
import 'stock_count_screen.dart';

double _n(Object? v) => (v as num?)?.toDouble() ?? 0;
String _q(Object? v) {
  final d = _n(v);
  return d == d.roundToDouble() ? d.toStringAsFixed(0) : d.toStringAsFixed(3).replaceAll(RegExp(r'0+$'), '');
}

/// Downloaded product pictures keyed by id + version (the version changes when the picture is replaced).
final Map<String, Future<Uint8List?>> _imageCache = {};

Future<Uint8List?> _productImage(Session session, Map<String, dynamic> p) {
  if (p['hasImage'] != true) return Future.value(null);
  return _imageCache.putIfAbsent(
    '${p['id']}:${p['version']}',
    () => session.api
        .getBytes('/api/products/${p['id']}/image')
        .then<Uint8List?>((b) => Uint8List.fromList(b))
        .catchError((Object _) => null),
  );
}

/// Product picture (or a placeholder icon) in a rounded square.
class ProductThumb extends StatelessWidget {
  const ProductThumb({super.key, required this.session, required this.product, this.size = 52, this.local});

  final Session session;
  final Map<String, dynamic>? product;
  final double size;

  /// A picture picked on the phone but not uploaded yet.
  final Uint8List? local;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(size > 100 ? 14 : 10);
    Widget box(Widget child) => ClipRRect(
          borderRadius: radius,
          child: Container(width: size, height: size, color: Colors.grey.shade200, alignment: Alignment.center, child: child),
        );
    final placeholder = Icon(Icons.inventory_2_outlined, color: Colors.grey.shade500, size: size * 0.45);
    if (local != null) return box(Image.memory(local!, width: size, height: size, fit: BoxFit.cover));
    if (product == null || product!['hasImage'] != true) return box(placeholder);
    return FutureBuilder<Uint8List?>(
      future: _productImage(session, product!),
      builder: (context, snap) => snap.data == null
          ? box(snap.connectionState == ConnectionState.done
              ? placeholder
              : SizedBox(width: size * 0.3, height: size * 0.3, child: const CircularProgressIndicator(strokeWidth: 2)))
          : GestureDetector(
              onTap: () => showProductImage(context, snap.data!, '${product!['name'] ?? ''}'),
              child: box(Image.memory(snap.data!, width: size, height: size, fit: BoxFit.cover)),
            ),
    );
  }
}

/// Shows a product picture full screen; pinch to zoom, tap or swipe the back button to close.
Future<void> showProductImage(BuildContext context, Uint8List bytes, String title) {
  return showDialog<void>(
    context: context,
    barrierColor: Colors.black,
    builder: (ctx) => Dialog.fullscreen(
      backgroundColor: Colors.black,
      child: Stack(children: [
        Positioned.fill(
          child: GestureDetector(
            onTap: () => Navigator.pop(ctx),
            child: InteractiveViewer(
              minScale: 1,
              maxScale: 5,
              child: Center(child: Image.memory(bytes, fit: BoxFit.contain)),
            ),
          ),
        ),
        SafeArea(
          child: Row(children: [
            IconButton(
              icon: const Icon(Icons.close, color: Colors.white),
              tooltip: 'Bağla',
              onPressed: () => Navigator.pop(ctx),
            ),
            Expanded(
              child: Text(title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.w600)),
            ),
          ]),
        ),
      ]),
    ),
  );
}

/// Lets the user take or choose a product picture. Returns null when cancelled or the file is too big.
Future<({Uint8List bytes, bool png})?> _pickProductImage(BuildContext context) async {
  final source = await showModalBottomSheet<ImageSource>(
    context: context,
    showDragHandle: true,
    builder: (ctx) => SafeArea(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        ListTile(
          leading: const Icon(Icons.photo_camera_outlined),
          title: const Text('Kamera ilə çək'),
          onTap: () => Navigator.pop(ctx, ImageSource.camera),
        ),
        ListTile(
          leading: const Icon(Icons.photo_library_outlined),
          title: const Text('Qalereyadan seç'),
          onTap: () => Navigator.pop(ctx, ImageSource.gallery),
        ),
      ]),
    ),
  );
  if (source == null) return null;
  final picked = await ImagePicker().pickImage(source: source, maxWidth: 1024, maxHeight: 1024, imageQuality: 85);
  if (picked == null) return null;
  final bytes = await picked.readAsBytes();
  if (bytes.length > 4 * 1024 * 1024) {
    if (context.mounted) showError(context, const ApiException(0, 'FILE_TOO_LARGE', 'Şəklin ölçüsü 4 MB-dan çox ola bilməz'));
    return null;
  }
  final png = bytes.length > 3 && bytes[0] == 0x89 && bytes[1] == 0x50;
  return (bytes: bytes, png: png);
}

/// Uploads [image] as the product's picture and returns the updated product.
Future<Map<String, dynamic>> _uploadProductImage(Session session, String productId, ({Uint8List bytes, bool png}) image) async =>
    await session.api.upload('/api/products/$productId/image', 'file', image.bytes, image.png ? 'product.png' : 'product.jpg',
        image.png ? 'image/png' : 'image/jpeg') as Map<String, dynamic>;

/// Anbar: products with stock, low-stock filter, add product and stock in / out; counts open from the app bar.
class InventoryScreen extends StatefulWidget {
  const InventoryScreen({super.key, required this.session});

  final Session session;

  @override
  State<InventoryScreen> createState() => _InventoryScreenState();
}

class _InventoryScreenState extends State<InventoryScreen> {
  String _q2 = '';
  bool _low = false;
  Timer? _debounce;
  late Future<List<dynamic>> _data = _fetch();

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  /// Search while typing (after a short pause).
  void _onSearch(String v) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 400), () {
      if (!mounted || v.trim() == _q2) return;
      _q2 = v.trim();
      _reload();
    });
  }

  /// Picture, price and stock of one product; stock in / out from here for users who manage the warehouse.
  Future<void> _open(Map<String, dynamic> p) =>
      _sheet(_manage ? _StockSheet(session: widget.session, product: p) : _ProductInfo(session: widget.session, product: p));

  bool get _manage => widget.session.me!.can('INVENTORY');

  Future<List<dynamic>> _fetch() => Future.wait([
        widget.session.api.get('/api/products', query: {
          'active': 'true',
          if (_q2.isNotEmpty) 'q': _q2,
          if (_low) 'stock': 'LOW',
        }),
        widget.session.api.get('/api/products/summary'),
      ]);

  void _reload() => setState(() {
        _data = _fetch();
      });

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

  /// Scan a product label: open its stock sheet, or offer to add a new product with that barcode.
  Future<void> _scan() async {
    final code = await scanBarcode(context);
    if (code == null || !mounted) return;
    try {
      final p = await productByBarcode(widget.session, code);
      if (!mounted) return;
      if (p != null) {
        await _open(p);
        return;
      }
      if (!_manage) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Bu barkodla məhsul tapılmadı: $code')));
        return;
      }
      final add = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Məhsul tapılmadı'),
          content: Text('Barkod $code heç bir məhsula bağlı deyil. Bu barkodla yeni məhsul əlavə edilsin?'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Xeyr')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Əlavə et')),
          ],
        ),
      );
      if (add == true && mounted) await _sheet(_ProductSheet(session: widget.session, barcode: code));
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Anbar'), actions: [
        if (_manage)
          TextButton.icon(
            onPressed: () async {
              await Navigator.of(context).push(MaterialPageRoute(builder: (_) => StockCountsScreen(session: widget.session)));
              if (mounted) _reload();
            },
            icon: const Icon(Icons.fact_check_outlined),
            label: const Text('Sayım'),
          ),
        IconButton(tooltip: 'Barkod skan et', icon: const Icon(Icons.qr_code_scanner), onPressed: _scan),
      ]),
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
                decoration: InputDecoration(
                  prefixIcon: const Icon(Icons.search),
                  hintText: 'Ad, kod və ya barkod',
                  suffixIcon: IconButton(tooltip: 'Barkod skan et', icon: const Icon(Icons.qr_code_scanner), onPressed: _scan),
                ),
                textInputAction: TextInputAction.search,
                onChanged: _onSearch,
                onSubmitted: (v) {
                  _debounce?.cancel();
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
                    onTap: () => _open(p),
                    contentPadding: const EdgeInsets.fromLTRB(10, 6, 14, 6),
                    leading: ProductThumb(session: widget.session, product: p),
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
                Text('Stok və şəkil üçün məhsula toxunun.', style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
            ]),
          );
        },
      ),
    );
  }
}

class _ProductSheet extends StatefulWidget {
  const _ProductSheet({required this.session, this.barcode});

  final Session session;
  final String? barcode;

  @override
  State<_ProductSheet> createState() => _ProductSheetState();
}

class _ProductSheetState extends State<_ProductSheet> {
  final _name = TextEditingController();
  late final _barcode = TextEditingController(text: widget.barcode ?? '');
  final _buy = TextEditingController();
  final _sell = TextEditingController();
  final _stock = TextEditingController();
  final _min = TextEditingController(text: '0');
  final _unit = TextEditingController(text: 'ədəd');
  ({Uint8List bytes, bool png})? _image;
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
      var msg = 'Məhsul əlavə edildi: ${p['code']}';
      if (_image != null) {
        try {
          await _uploadProductImage(widget.session, '${p['id']}', _image!);
        } catch (e) {
          msg = '$msg. Şəkil yüklənmədi: ${errorMessage(e)}';
        }
      }
      if (mounted) Navigator.pop(context, msg);
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
        const SizedBox(height: 8),
        Row(children: [
          ProductThumb(session: widget.session, product: null, size: 72, local: _image?.bytes),
          const SizedBox(width: 12),
          Expanded(
            child: OutlinedButton.icon(
              onPressed: _busy
                  ? null
                  : () async {
                      final img = await _pickProductImage(context);
                      if (img != null && mounted) setState(() => _image = img);
                    },
              icon: const Icon(Icons.add_a_photo_outlined),
              label: Text(_image == null ? 'Şəkil əlavə et' : 'Şəkli dəyiş'),
            ),
          ),
        ]),
        TextField(controller: _name, decoration: const InputDecoration(labelText: 'Ad')),
        TextField(
          controller: _barcode,
          decoration: InputDecoration(
            labelText: 'Barkod (istəyə bağlı)',
            suffixIcon: IconButton(
              tooltip: 'Barkod skan et',
              icon: const Icon(Icons.qr_code_scanner),
              onPressed: () async {
                final code = await scanBarcode(context);
                if (code != null && mounted) setState(() => _barcode.text = code);
              },
            ),
          ),
        ),
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
  late Map<String, dynamic> _product = widget.product;
  bool _imageBusy = false;
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

  Future<void> _changeImage() async {
    final img = await _pickProductImage(context);
    if (img == null || !mounted) return;
    setState(() => _imageBusy = true);
    try {
      final p = await _uploadProductImage(widget.session, '${_product['id']}', img);
      if (mounted) {
        setState(() => _product = p);
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Şəkil yadda saxlandı')));
      }
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _imageBusy = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
        _ProductHeader(session: widget.session, product: _product),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
            onPressed: _imageBusy ? null : _changeImage,
            icon: _imageBusy
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.add_a_photo_outlined),
            label: Text(_product['hasImage'] == true ? 'Şəkli dəyiş' : 'Şəkil əlavə et'),
          ),
        ),
        const SizedBox(height: 4),
        if (_error != null) ErrorBanner(_error!),
        Wrap(spacing: 6, children: [
          for (final k in const [['RECEIPT', 'Giriş'], ['ISSUE', 'Çıxış'], ['ADJUSTMENT', 'Düzəliş ±']])
            ChoiceChip(label: Text(k[1]), selected: _kind == k[0], onSelected: (_) => setState(() => _kind = k[0])),
        ]),
        TextField(
          controller: _qty,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
          decoration: InputDecoration(labelText: _kind == 'ADJUSTMENT' ? 'Fərq (+/−)' : 'Miqdar'),
        ),
        if (_kind == 'RECEIPT')
          TextField(controller: _cost, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: const InputDecoration(labelText: 'Alış qiyməti (₼)')),
        TextField(controller: _note, decoration: const InputDecoration(labelText: 'Qeyd')),
        const SizedBox(height: 14),
        FilledButton(onPressed: _busy ? null : _save, child: const Text('Təsdiqlə')),
      ]);
}

/// Large picture with name, code / barcode, prices and current stock.
class _ProductHeader extends StatelessWidget {
  const _ProductHeader({required this.session, required this.product});

  final Session session;
  final Map<String, dynamic> product;

  @override
  Widget build(BuildContext context) {
    final p = product;
    final stockColor = p['outOfStock'] == true
        ? Colors.red.shade700
        : p['lowStock'] == true
            ? Colors.orange.shade800
            : navy;
    return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      ProductThumb(session: session, product: p, size: 110),
      const SizedBox(width: 14),
      Expanded(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('${p['name']}', style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w700, color: navy)),
          const SizedBox(height: 2),
          Text([p['code'], p['barcode']].whereType<String>().where((s) => s.isNotEmpty).join(' · '),
              style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
          const SizedBox(height: 8),
          Text('Satış: ${formatMoney(_n(p['salePrice']))}', style: const TextStyle(fontWeight: FontWeight.w600)),
          Text('Alış: ${formatMoney(_n(p['purchasePrice']))}', style: TextStyle(color: Colors.grey.shade700)),
          const SizedBox(height: 4),
          Text('Qalıq: ${_q(p['stockQty'])} ${p['unit']}', style: TextStyle(fontWeight: FontWeight.w800, color: stockColor)),
        ]),
      ),
    ]);
  }
}

/// Read-only product card for users who may sell but not manage the warehouse.
class _ProductInfo extends StatelessWidget {
  const _ProductInfo({required this.session, required this.product});

  final Session session;
  final Map<String, dynamic> product;

  @override
  Widget build(BuildContext context) => ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
        _ProductHeader(session: session, product: product),
        const SizedBox(height: 16),
        OutlinedButton(onPressed: () => Navigator.pop(context), child: const Text('Bağla')),
      ]);
}
