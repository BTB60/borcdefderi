import 'dart:async';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:share_plus/share_plus.dart';

import '../api/api_client.dart';
import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'barcode_scanner.dart';
import 'common.dart';
import 'label_print_sheet.dart';
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

  static const _xlsx = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

  /// Downloads a product list / template and hands it to the system share sheet (WhatsApp, e-mail, Drive, Files …).
  Future<void> _share(String path, String name, String mime) async {
    try {
      final bytes = await widget.session.api.getBytes(path);
      await SharePlus.instance.share(ShareParams(
        files: [XFile.fromData(Uint8List.fromList(bytes), mimeType: mime, name: name)],
        fileNameOverrides: [name],
      ));
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  /// Excel import: pick a file, send it, show what was created / updated or the wrong rows.
  Future<void> _import() async {
    final picked = await FilePicker.pickFiles(type: FileType.custom, allowedExtensions: const ['xlsx', 'xls']);
    if (picked.isEmpty || !mounted) return;
    final file = picked.first;
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Import edilir…')));
    try {
      final bytes = await file.readAsBytes();
      final r = await widget.session.api.upload('/api/products/import', 'file', bytes, file.name, _xlsx) as Map<String, dynamic>;
      if (!mounted) return;
      ScaffoldMessenger.of(context).hideCurrentSnackBar();
      final errors = (r['errors'] as List).cast<Map<String, dynamic>>();
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          icon: Icon(r['applied'] == true ? Icons.check_circle_outline : Icons.error_outline,
              color: r['applied'] == true ? Colors.green.shade700 : Colors.red.shade700, size: 40),
          title: Text(r['applied'] == true ? 'Import tamamlandı' : 'Faylda səhv var'),
          content: r['applied'] == true
              ? Text('${r['created']} yeni məhsul\n${r['updated']} yenilənən\n${r['unchanged']} dəyişməyən',
                  textAlign: TextAlign.center, style: const TextStyle(fontSize: 16, height: 1.5))
              : SizedBox(
                  width: double.maxFinite,
                  child: ListView(shrinkWrap: true, children: [
                    const Text('Heç nə saxlanmadı. Düzəldib yenidən yükləyin.'),
                    const SizedBox(height: 8),
                    for (final e in errors)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 3),
                        child: Text('${e['row'] == 0 ? 'Fayl' : 'Sətir ${e['row']}'}: ${e['message']}',
                            style: TextStyle(color: Colors.red.shade800)),
                      ),
                  ]),
                ),
          actions: [FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('Oldu'))],
        ),
      );
      if (r['applied'] == true) _reload();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).hideCurrentSnackBar();
        showError(context, e);
      }
    }
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
        if (_manage)
          PopupMenuButton<String>(
            tooltip: 'Export / import',
            onSelected: (v) => v == 'labels'
                ? showLabelPrintSheet(context, widget.session)
                : v == 'import'
                ? _import()
                : v == 'template'
                    ? _share('/api/products/import-template', 'mehsul-import-sablonu.xlsx', _xlsx)
                    : _share('/api/products/export?format=$v', 'mehsullar.$v', v == 'pdf' ? 'application/pdf' : _xlsx),
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'pdf', child: ListTile(leading: Icon(Icons.picture_as_pdf_outlined), title: Text('Bütün məhsullar — PDF'))),
              PopupMenuItem(value: 'xlsx', child: ListTile(leading: Icon(Icons.table_chart_outlined), title: Text('Bütün məhsullar — Excel'))),
              PopupMenuItem(value: 'labels', child: ListTile(leading: Icon(Icons.print_outlined), title: Text('Bütün barkodları çap et'))),
              PopupMenuDivider(),
              PopupMenuItem(value: 'import', child: ListTile(leading: Icon(Icons.upload_file_outlined), title: Text('Excel-dən import'))),
              PopupMenuItem(value: 'template', child: ListTile(leading: Icon(Icons.description_outlined), title: Text('Import şablonu'))),
            ],
          ),
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
  final _old = TextEditingController();
  ({Uint8List bytes, bool png})? _image;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    for (final c in [_name, _barcode, _buy, _sell, _stock, _min, _unit, _old]) {
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
        'oldPrice': _d(_old).isEmpty ? null : _d(_old),
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

  /// Purchase → sale margin shown under the prices while typing.
  Widget _margin() {
    final buy = double.tryParse(_d(_buy));
    final sell = double.tryParse(_d(_sell));
    if (buy == null || sell == null) return const SizedBox.shrink();
    final profit = sell - buy;
    final pct = buy > 0 ? profit / buy * 100 : null;
    final color = profit < 0 ? Colors.red.shade700 : Colors.green.shade700;
    return Padding(
      padding: const EdgeInsets.only(top: 6, left: 4),
      child: Row(children: [
        Icon(profit < 0 ? Icons.trending_down : Icons.trending_up, size: 18, color: color),
        const SizedBox(width: 6),
        Text(
          'Qazanc: ${formatMoney(profit)}${pct == null ? '' : ' (${pct.toStringAsFixed(0)}%)'}',
          style: TextStyle(color: color, fontWeight: FontWeight.w600),
        ),
      ]),
    );
  }

  Widget _section(String title) => Padding(
        padding: const EdgeInsets.only(top: 18, bottom: 8),
        child: Text(title.toUpperCase(),
            style: TextStyle(fontSize: 12, letterSpacing: 0.8, fontWeight: FontWeight.w700, color: Colors.grey.shade600)),
      );

  static const _gap = SizedBox(height: 12);
  static const _decimal = TextInputType.numberWithOptions(decimal: true);

  @override
  Widget build(BuildContext context) => ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(20, 0, 20, 24), children: [
        const Text('Yeni məhsul', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: navy)),
        const SizedBox(height: 4),
        Text('Ulduzlu (*) sahələr mütləqdir', style: TextStyle(color: Colors.grey.shade600)),
        if (_error != null) ...[const SizedBox(height: 12), ErrorBanner(_error!)],
        const SizedBox(height: 16),
        // picture: the whole card is the button
        Material(
          color: Colors.white,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16), side: BorderSide(color: Colors.grey.shade300)),
          child: InkWell(
            borderRadius: BorderRadius.circular(16),
            onTap: _busy
                ? null
                : () async {
                    final img = await _pickProductImage(context);
                    if (img != null && mounted) setState(() => _image = img);
                  },
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Row(children: [
                ProductThumb(session: widget.session, product: null, size: 64, local: _image?.bytes),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(_image == null ? 'Şəkil əlavə et' : 'Şəkli dəyiş',
                        style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16, color: navy)),
                    const SizedBox(height: 2),
                    Text('Kamera və ya qalereya', style: TextStyle(color: Colors.grey.shade600)),
                  ]),
                ),
                Icon(_image == null ? Icons.add_a_photo_outlined : Icons.edit_outlined, color: navy),
              ]),
            ),
          ),
        ),
        _section('Əsas məlumat'),
        TextField(
          controller: _name,
          textCapitalization: TextCapitalization.sentences,
          decoration: const InputDecoration(labelText: 'Məhsulun adı *', prefixIcon: Icon(Icons.inventory_2_outlined)),
        ),
        _gap,
        TextField(
          controller: _barcode,
          decoration: InputDecoration(
            labelText: 'Barkod (istəyə bağlı)',
            prefixIcon: const Icon(Icons.qr_code_2),
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
        _section('Qiymət'),
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(
            child: TextField(
              controller: _buy,
              keyboardType: _decimal,
              onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(labelText: 'Alış qiyməti *', suffixText: '₼'),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: TextField(
              controller: _sell,
              keyboardType: _decimal,
              onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(labelText: 'Satış qiyməti *', suffixText: '₼'),
            ),
          ),
        ]),
        _margin(),
        _gap,
        TextField(
          controller: _old,
          keyboardType: _decimal,
          decoration: const InputDecoration(
            labelText: 'Endirimdən əvvəlki qiymət (istəyə bağlı)',
            suffixText: '₼',
            helperText: 'Etiketdə xətli göstərilir, satış qiyməti isə yeni qiymətdir',
            helperMaxLines: 2,
          ),
        ),
        _section('Anbar'),
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(
            flex: 3,
            child: TextField(
              controller: _stock,
              keyboardType: _decimal,
              decoration: const InputDecoration(labelText: 'Başlanğıc qalıq', hintText: '0'),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            flex: 2,
            child: TextField(controller: _unit, decoration: const InputDecoration(labelText: 'Vahid')),
          ),
        ]),
        _gap,
        TextField(
          controller: _min,
          keyboardType: _decimal,
          decoration: const InputDecoration(
            labelText: 'Minimum stok',
            helperText: 'Qalıq bundan az olanda "Az qalan" kimi göstərilir',
            prefixIcon: Icon(Icons.notifications_active_outlined),
          ),
        ),
        const SizedBox(height: 24),
        SizedBox(
          height: 52,
          child: FilledButton.icon(
            onPressed: _busy ? null : _save,
            icon: _busy
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                : const Icon(Icons.check),
            label: const Text('Məhsulu saxla', style: TextStyle(fontSize: 16)),
          ),
        ),
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

  /// Sale price and the price before a discount (shown struck through on the barcode label).
  Future<void> _editPrice() async {
    final p = _product;
    final sale = TextEditingController(text: _n(p['salePrice']).toStringAsFixed(2));
    final old = TextEditingController(text: p['oldPrice'] == null ? '' : _n(p['oldPrice']).toStringAsFixed(2));
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Qiymət və endirim'),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(
            controller: sale,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: 'Satış qiyməti (indiki)', suffixText: '₼'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: old,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(
              labelText: 'Endirimdən əvvəlki qiymət',
              suffixText: '₼',
              helperText: 'Boş = endirim yoxdur. Etiketdə xətli göstərilir',
              helperMaxLines: 2,
            ),
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
    if (ok != true || !mounted) return;
    final newSale = double.tryParse(saleText);
    final newOld = oldText.isEmpty ? 0.0 : double.tryParse(oldText);
    if (newSale == null || newOld == null) {
      showError(context, 'Qiyməti düzgün yazın');
      return;
    }
    if (newOld != 0 && newOld <= newSale) {
      showError(context, 'Endirimdən əvvəlki qiymət indiki qiymətdən böyük olmalıdır');
      return;
    }
    try {
      final saved = await widget.session.api.put('/api/products/${p['id']}', {
        'name': p['name'],
        'code': p['code'],
        'barcode': p['barcode'],
        'categoryId': p['categoryId'],
        'supplierId': p['supplierId'],
        'unit': p['unit'],
        'purchasePrice': _n(p['purchasePrice']).toStringAsFixed(2),
        'salePrice': newSale.toStringAsFixed(2),
        'minStock': '${p['minStock']}',
        'active': p['active'],
        'version': p['version'],
        'oldPrice': newOld.toStringAsFixed(2),
      }) as Map<String, dynamic>;
      if (!mounted) return;
      setState(() => _product = saved);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(newOld == 0 ? 'Qiymət saxlandı' : 'Endirim: ${formatMoney(newOld)} → ${formatMoney(newSale)}')));
    } catch (e) {
      if (mounted) showError(context, e);
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
        Wrap(spacing: 4, children: [
          TextButton.icon(
            onPressed: _imageBusy ? null : _changeImage,
            icon: _imageBusy
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.add_a_photo_outlined),
            label: Text(_product['hasImage'] == true ? 'Şəkli dəyiş' : 'Şəkil əlavə et'),
          ),
          TextButton.icon(
            onPressed: () => showLabelPrintSheet(context, widget.session, product: _product),
            icon: const Icon(Icons.print_outlined),
            label: const Text('Barkod çap et'),
          ),
          TextButton.icon(
            onPressed: _editPrice,
            icon: const Icon(Icons.local_offer_outlined),
            label: const Text('Qiymət / endirim'),
          ),
        ]),
        const SizedBox(height: 4),
        if (_error != null) ErrorBanner(_error!),
        const Divider(height: 24),
        Text('STOK HƏRƏKƏTİ', style: TextStyle(fontSize: 12, letterSpacing: 0.8, fontWeight: FontWeight.w700, color: Colors.grey.shade600)),
        const SizedBox(height: 8),
        SegmentedButton<String>(
          showSelectedIcon: false,
          segments: const [
            ButtonSegment(value: 'RECEIPT', icon: Icon(Icons.south_west), label: Text('Giriş')),
            ButtonSegment(value: 'ISSUE', icon: Icon(Icons.north_east), label: Text('Çıxış')),
            ButtonSegment(value: 'ADJUSTMENT', icon: Icon(Icons.tune), label: Text('Düzəliş')),
          ],
          selected: {_kind},
          onSelectionChanged: (v) => setState(() => _kind = v.first),
        ),
        const SizedBox(height: 12),
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(
            child: TextField(
              controller: _qty,
              autofocus: true,
              keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
              decoration: InputDecoration(
                labelText: _kind == 'ADJUSTMENT' ? 'Fərq (+ və ya −)' : 'Miqdar',
                suffixText: '${_product['unit'] ?? ''}',
              ),
            ),
          ),
          if (_kind == 'RECEIPT') ...[
            const SizedBox(width: 12),
            Expanded(
              child: TextField(
                controller: _cost,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(labelText: 'Alış qiyməti', suffixText: '₼'),
              ),
            ),
          ],
        ]),
        const SizedBox(height: 12),
        TextField(controller: _note, decoration: const InputDecoration(labelText: 'Qeyd (istəyə bağlı)')),
        const SizedBox(height: 16),
        SizedBox(
          height: 52,
          child: FilledButton.icon(
            onPressed: _busy ? null : _save,
            icon: const Icon(Icons.check),
            label: Text(_kind == 'RECEIPT' ? 'Anbara giriş et' : _kind == 'ISSUE' ? 'Anbardan çıxar' : 'Düzəlişi yaz',
                style: const TextStyle(fontSize: 16)),
          ),
        ),
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
          Wrap(crossAxisAlignment: WrapCrossAlignment.center, spacing: 8, children: [
            Text('Satış: ${formatMoney(_n(p['salePrice']))}', style: const TextStyle(fontWeight: FontWeight.w600)),
            if (p['oldPrice'] != null && _n(p['oldPrice']) > _n(p['salePrice']))
              Text(formatMoney(_n(p['oldPrice'])),
                  style: TextStyle(color: Colors.red.shade700, decoration: TextDecoration.lineThrough, fontSize: 13)),
          ]),
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
