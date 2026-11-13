import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';

import '../api/api_client.dart';
import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'barcode_scanner.dart';
import 'common.dart';

double _n(Object? v) => (v as num?)?.toDouble() ?? 0;
String _q(Object? v) {
  final d = _n(v);
  return d == d.roundToDouble() ? d.toStringAsFixed(0) : d.toStringAsFixed(3).replaceAll(RegExp(r'0+$'), '');
}

String _signed(double v) => '${v > 0 ? '+' : ''}${_q(v)}';
String _signedMoney(double v) => v < 0 ? '−${formatMoney(-v)}' : '${v > 0 ? '+' : ''}${formatMoney(v)}';
Color _tone(Object? diff) {
  if (diff == null || _n(diff) == 0) return Colors.blueGrey;
  return _n(diff) > 0 ? Colors.green.shade700 : Colors.red.shade700;
}

DateTime? _date(Object? v) => v == null ? null : DateTime.tryParse('$v');

/// Totals of the counted lines (same rules as the server): +/− items and their value at the purchase price.
({int total, int counted, int matched, int surplusItems, int shortageItems, double surplus, double shortage}) _totals(
    List<Map<String, dynamic>> lines) {
  var counted = 0, matched = 0, si = 0, sh = 0;
  var surplus = 0.0, shortage = 0.0;
  for (final l in lines) {
    if (l['countedQty'] == null) continue;
    counted++;
    final d = _n(l['difference']);
    final v = _n(l['differenceValue']);
    if (d > 0) {
      si++;
      surplus += v;
    } else if (d < 0) {
      sh++;
      shortage -= v;
    } else {
      matched++;
    }
  }
  return (total: lines.length, counted: counted, matched: matched, surplusItems: si, shortageItems: sh, surplus: surplus, shortage: shortage);
}

/// Anbar sayımı: list of counts with their +/− result; start a new count or continue the open one.
class StockCountsScreen extends StatefulWidget {
  const StockCountsScreen({super.key, required this.session});

  final Session session;

  @override
  State<StockCountsScreen> createState() => _StockCountsScreenState();
}

class _StockCountsScreenState extends State<StockCountsScreen> {
  late Future<List<dynamic>> _data = _fetch();

  Future<List<dynamic>> _fetch() async => await widget.session.api.get('/api/stock-counts') as List<dynamic>;

  void _reload() => setState(() {
        _data = _fetch();
      });

  Future<void> _openCount(String id) async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => StockCountScreen(session: widget.session, countId: id)));
    if (mounted) _reload();
  }

  Future<void> _start() async {
    final id = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
        child: _StartSheet(session: widget.session),
      ),
    );
    if (id != null && mounted) await _openCount(id);
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<List<dynamic>>(
      future: _data,
      builder: (context, snap) {
        final items = snap.hasData ? snap.data!.cast<Map<String, dynamic>>() : <Map<String, dynamic>>[];
        final open = items.where((c) => c['status'] == 'IN_PROGRESS').firstOrNull;
        return Scaffold(
          appBar: AppBar(title: const Text('Anbar sayımı')),
          floatingActionButton: !snap.hasData
              ? null
              : open != null
                  ? FloatingActionButton.extended(
                      onPressed: () => _openCount('${open['id']}'),
                      icon: const Icon(Icons.play_arrow),
                      label: Text('Davam et: ${open['number']}'))
                  : FloatingActionButton.extended(onPressed: _start, icon: const Icon(Icons.add), label: const Text('Yeni sayım')),
          body: RefreshIndicator(
            onRefresh: () async => _reload(),
            child: ListView(padding: const EdgeInsets.fromLTRB(12, 12, 12, 96), children: [
              if (snap.hasError) ErrorBanner(errorMessage(snap.error!)),
              if (!snap.hasData && !snap.hasError) const Center(child: CircularProgressIndicator()),
              if (snap.hasData && items.isEmpty)
                const EmptyState(title: 'Hələ sayım yoxdur', text: 'Məhsulları sayın, sayılan miqdarı daxil edin — +/− fərq hesabatı avtomatik çıxır.'),
              for (final c in items) _CountCard(count: c, onTap: () => _openCount('${c['id']}')),
            ]),
          ),
        );
      },
    );
  }
}

class _CountCard extends StatelessWidget {
  const _CountCard({required this.count, required this.onTap});

  final Map<String, dynamic> count;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final c = count;
    final t = c['totals'] as Map<String, dynamic>;
    final open = c['status'] == 'IN_PROGRESS';
    final net = _n(t['netValue']);
    final when = _date(c['completedAt'] ?? c['createdAt']);
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Text('${c['number']}', style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16, color: navy)),
              const SizedBox(width: 8),
              _Chip(open ? 'davam edir' : 'tamamlanıb', open ? Colors.orange : Colors.green),
              const Spacer(),
              Text(_signedMoney(net), style: TextStyle(fontWeight: FontWeight.w800, color: _tone(net))),
            ]),
            const SizedBox(height: 4),
            Text('${c['categoryName'] ?? 'Bütün məhsullar'}${c['note'] != null ? ' · ${c['note']}' : ''}',
                style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
            Text('${when == null ? '' : formatDateTime(when)} · ${c['completedByName'] ?? c['createdByName']}',
                style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
            const SizedBox(height: 6),
            Wrap(spacing: 12, children: [
              Text('Sayılıb ${t['countedItems']} / ${t['totalItems']}'),
              Text('+${t['surplusItems']} · ${formatMoney(_n(t['surplusValue']))}', style: TextStyle(color: Colors.green.shade700)),
              Text('−${t['shortageItems']} · ${formatMoney(_n(t['shortageValue']))}', style: TextStyle(color: Colors.red.shade700)),
            ]),
          ]),
        ),
      ),
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip(this.text, this.color);

  final String text;
  final MaterialColor color;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(999)),
        child: Text(text, style: TextStyle(color: color.shade800, fontSize: 11, fontWeight: FontWeight.w600)),
      );
}

/// Choose what to count (all products or one category) and start; pops with the new count's id.
class _StartSheet extends StatefulWidget {
  const _StartSheet({required this.session});

  final Session session;

  @override
  State<_StartSheet> createState() => _StartSheetState();
}

class _StartSheetState extends State<_StartSheet> {
  final _note = TextEditingController();
  late final Future<dynamic> _categories = widget.session.api.get('/api/product-categories');
  String? _category;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  Future<void> _go() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final d = await widget.session.api.post('/api/stock-counts', {
        'categoryId': _category,
        'note': _note.text.trim().isEmpty ? null : _note.text.trim(),
      }) as Map<String, dynamic>;
      if (mounted) Navigator.pop(context, '${(d['count'] as Map)['id']}');
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
        const Text('Yeni sayım', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
        const SizedBox(height: 4),
        Text('Seçilən məhsullar indiki sistem qalığı ilə siyahıya düşür. Qalıqlar yalnız “Tamamla” basılanda düzəlir.',
            style: TextStyle(color: Colors.grey.shade600)),
        const SizedBox(height: 12),
        if (_error != null) ErrorBanner(_error!),
        FutureBuilder<dynamic>(
          future: _categories,
          builder: (context, snap) {
            final cats = snap.hasData ? (snap.data as List).cast<Map<String, dynamic>>() : <Map<String, dynamic>>[];
            return DropdownButtonFormField<String?>(
              initialValue: _category,
              decoration: const InputDecoration(labelText: 'Nə sayılır?'),
              items: [
                const DropdownMenuItem<String?>(value: null, child: Text('Bütün aktiv məhsullar')),
                for (final c in cats) DropdownMenuItem<String?>(value: '${c['id']}', child: Text('${c['name']}')),
              ],
              onChanged: (v) => setState(() => _category = v),
            );
          },
        ),
        TextField(controller: _note, decoration: const InputDecoration(labelText: 'Qeyd (məs. Ay sonu sayımı)')),
        const SizedBox(height: 16),
        FilledButton.icon(onPressed: _busy ? null : _go, icon: const Icon(Icons.play_arrow), label: const Text('Sayımı başla')),
      ]);
}

/// One count: enter counted quantities (by tapping a product or scanning its barcode) while it is open;
/// afterwards it is the count report with the +/− difference of every counted product.
class StockCountScreen extends StatefulWidget {
  const StockCountScreen({super.key, required this.session, required this.countId});

  final Session session;
  final String countId;

  @override
  State<StockCountScreen> createState() => _StockCountScreenState();
}

class _StockCountScreenState extends State<StockCountScreen> {
  Map<String, dynamic>? _count;
  List<Map<String, dynamic>> _lines = [];
  Object? _error;
  String _search = '';
  String _show = 'ALL';
  bool _busy = false;

  bool get _open => _count?['status'] == 'IN_PROGRESS';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      _apply(await widget.session.api.get('/api/stock-counts/${widget.countId}') as Map<String, dynamic>);
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  void _apply(Map<String, dynamic> d) {
    if (!mounted) return;
    setState(() {
      _error = null;
      _count = d['count'] as Map<String, dynamic>;
      _lines = (d['lines'] as List).cast<Map<String, dynamic>>();
    });
  }

  void _replace(Map<String, dynamic> line) {
    setState(() {
      final i = _lines.indexWhere((l) => l['productId'] == line['productId']);
      if (i >= 0) {
        _lines[i] = line;
      } else {
        _lines = [..._lines, line]..sort((a, b) => '${a['name']}'.toLowerCase().compareTo('${b['name']}'.toLowerCase()));
      }
    });
  }

  /// Asks the counted quantity of [line] and saves it. Returns true when saved.
  Future<bool> _enter(Map<String, dynamic> line) async {
    final result = await showDialog<({String? value})>(context: context, builder: (_) => _QtyDialog(line: line));
    if (result == null || !mounted) return false;
    final raw = result.value?.trim().replaceAll(',', '.');
    if (raw != null && raw.isEmpty) return false;
    if (raw != null && (double.tryParse(raw) == null || double.parse(raw) < 0)) {
      showError(context, const ApiException(0, 'INVALID_QUANTITY', 'Sayılan miqdar 0 və ya daha çox olmalıdır'));
      return false;
    }
    try {
      _replace(await widget.session.api.put('/api/stock-counts/${widget.countId}/lines/${line['productId']}', {'countedQty': raw})
          as Map<String, dynamic>);
      return true;
    } catch (e) {
      if (mounted) showError(context, e);
      return false;
    }
  }

  /// Scan → enter quantity → scan the next one, until the scanner is closed.
  Future<void> _scanLoop() async {
    while (true) {
      if (!mounted) return;
      final code = await scanBarcode(context);
      if (code == null || !mounted) return;
      var line = _lines.where((l) => l['barcode'] == code || '${l['code']}'.toLowerCase() == code.toLowerCase()).firstOrNull;
      if (line == null) {
        try {
          final p = await productByBarcode(widget.session, code);
          if (!mounted) return;
          if (p == null) {
            ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Bu barkodla məhsul tapılmadı: $code')));
            continue;
          }
          line = _lines.where((l) => l['productId'] == p['id']).firstOrNull ??
              await widget.session.api.put('/api/stock-counts/${widget.countId}/lines/${p['id']}', {'countedQty': null})
                  as Map<String, dynamic>;
          _replace(line);
        } catch (e) {
          if (mounted) showError(context, e);
          return;
        }
      }
      if (!await _enter(line)) return;
    }
  }

  Future<void> _complete() async {
    final t = _totals(_lines);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Sayım tamamlansın?'),
        content: Text('Sayılan ${t.counted} məhsulun fərqi anbar qalığına yazılacaq:\n'
            'artıq ${_signedMoney(t.surplus)}, kəsir ${_signedMoney(-t.shortage)}.'
            '${t.total > t.counted ? '\nSayılmayan ${t.total - t.counted} məhsula toxunulmayacaq.' : ''}'
            '\nTamamlanmış sayım dəyişdirilə bilməz.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Xeyr')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Tamamla')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    setState(() => _busy = true);
    try {
      _apply(await widget.session.api.post('/api/stock-counts/${widget.countId}/complete') as Map<String, dynamic>);
      if (mounted) {
        setState(() => _show = 'ALL');
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Sayım tamamlandı, qalıqlar düzəldildi')));
      }
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _delete() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Sayım ${_count!['number']} silinsin?'),
        content: const Text('Anbar qalıqları dəyişməyəcək.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Xeyr')),
          FilledButton(
              style: FilledButton.styleFrom(backgroundColor: Colors.red.shade700),
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Sil')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      await widget.session.api.delete('/api/stock-counts/${widget.countId}');
      if (mounted) Navigator.pop(context);
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  /// Shares the count report as PDF or Excel through the system share sheet (WhatsApp, e-mail, Drive …).
  Future<void> _share(String format) async {
    try {
      final bytes = await widget.session.api.getBytes('/api/stock-counts/${widget.countId}/export?format=$format');
      final name = 'sayim-${_count!['number']}.$format';
      final mime = format == 'pdf' ? 'application/pdf' : 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
      await SharePlus.instance.share(ShareParams(
        files: [XFile.fromData(Uint8List.fromList(bytes), mimeType: mime, name: name)],
        fileNameOverrides: [name],
        text: 'Sayım hesabatı ${_count!['number']}',
      ));
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = _count;
    return Scaffold(
      appBar: AppBar(title: Text(c == null ? 'Sayım' : 'Sayım ${c['number']}'), actions: [
        if (c != null && _open) IconButton(tooltip: 'Barkod skan et', icon: const Icon(Icons.qr_code_scanner), onPressed: _scanLoop),
        if (c != null)
          PopupMenuButton<String>(
            onSelected: (v) => v == 'delete' ? _delete() : _share(v),
            itemBuilder: (_) => [
              const PopupMenuItem(value: 'pdf', child: ListTile(leading: Icon(Icons.picture_as_pdf_outlined), title: Text('PDF paylaş'))),
              const PopupMenuItem(value: 'xlsx', child: ListTile(leading: Icon(Icons.table_chart_outlined), title: Text('Excel paylaş'))),
              if (_open)
                PopupMenuItem(
                    value: 'delete',
                    child: ListTile(leading: Icon(Icons.delete_outline, color: Colors.red.shade700), title: const Text('Sayımı sil'))),
            ],
          ),
      ]),
      bottomNavigationBar: c != null && _open
          ? SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 6, 12, 10),
                child: Row(children: [
                  Expanded(
                    child: OutlinedButton.icon(onPressed: _scanLoop, icon: const Icon(Icons.qr_code_scanner), label: const Text('Skan et')),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: _busy || _totals(_lines).counted == 0 ? null : _complete,
                      icon: const Icon(Icons.check),
                      label: const Text('Tamamla'),
                    ),
                  ),
                ]),
              ),
            )
          : null,
      body: _error != null && c == null
          ? ListView(children: [Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(_error!)))])
          : c == null
              ? const Center(child: CircularProgressIndicator())
              : RefreshIndicator(onRefresh: _load, child: _body(c)),
    );
  }

  Widget _body(Map<String, dynamic> c) {
    final t = _totals(_lines);
    final s = _search.trim().toLowerCase();
    final visible = _lines.where((l) {
      if (_show == 'TODO' && l['countedQty'] != null) return false;
      if (_show == 'DIFF' && _n(l['difference']) == 0) return false;
      if (!_open && _show == 'ALL' && l['countedQty'] == null) return false;
      return s.isEmpty ||
          '${l['name']}'.toLowerCase().contains(s) ||
          '${l['code']}'.toLowerCase().contains(s) ||
          l['barcode'] == _search.trim();
    }).toList();
    final started = _date(c['createdAt']);
    final completed = _date(c['completedAt']);
    return ListView(padding: const EdgeInsets.fromLTRB(12, 12, 12, 24), children: [
      Text('${c['categoryName'] ?? 'Bütün məhsullar'}${c['note'] != null ? ' · ${c['note']}' : ''}',
          style: const TextStyle(fontWeight: FontWeight.w600)),
      Text('Başladı: ${started == null ? '' : formatDateTime(started)} · ${c['createdByName']}',
          style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
      if (completed != null)
        Text('Tamamladı: ${formatDateTime(completed)} · ${c['completedByName']}', style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
      const SizedBox(height: 6),
      Align(
        alignment: Alignment.centerLeft,
        child: _open
            ? const _Chip('davam edir — qalıqlar “Tamamla” basılanda düzəlir', Colors.orange)
            : const _Chip('tamamlanıb', Colors.green),
      ),
      const SizedBox(height: 10),
      Row(children: [
        Expanded(child: StatTile(label: 'Sayılıb', value: '${t.counted} / ${t.total}', hint: '${t.matched} uyğun')),
        const SizedBox(width: 8),
        Expanded(child: StatTile(label: 'Xalis fərq', value: _signedMoney(t.surplus - t.shortage), hint: 'alış qiyməti ilə')),
      ]),
      const SizedBox(height: 8),
      Row(children: [
        Expanded(child: StatTile(label: 'Artıq (+)', value: _signedMoney(t.surplus), hint: '${t.surplusItems} məhsul')),
        const SizedBox(width: 8),
        Expanded(child: StatTile(label: 'Kəsir (−)', value: _signedMoney(-t.shortage), hint: '${t.shortageItems} məhsul')),
      ]),
      const SizedBox(height: 10),
      TextField(
        decoration: const InputDecoration(prefixIcon: Icon(Icons.search), hintText: 'Ad, kod və ya barkod'),
        onChanged: (v) => setState(() => _search = v),
      ),
      const SizedBox(height: 6),
      Wrap(spacing: 6, children: [
        for (final k in [
          ['ALL', _open ? 'Hamısı' : 'Sayılanlar'],
          if (_open) ['TODO', 'Sayılmayanlar'],
          ['DIFF', 'Fərqi olanlar'],
        ])
          ChoiceChip(label: Text(k[1]), selected: _show == k[0], onSelected: (_) => setState(() => _show = k[0])),
      ]),
      const SizedBox(height: 6),
      if (visible.isEmpty) const EmptyState(title: 'Məhsul yoxdur'),
      for (final l in visible)
        Card(
          child: ListTile(
            onTap: _open ? () => _enter(l) : null,
            title: Text('${l['name']}', style: const TextStyle(fontWeight: FontWeight.w600)),
            subtitle: Text('${l['code']} · sistem ${_q(l['systemQty'])} ${l['unit']}'
                '${l['countedByName'] != null ? ' · ${l['countedByName']}' : ''}'),
            trailing: l['countedQty'] == null
                ? Text(_open ? 'say' : '—', style: TextStyle(color: Colors.grey.shade500))
                : Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                    Text('${_q(l['countedQty'])} ${l['unit']}', style: const TextStyle(fontWeight: FontWeight.w700)),
                    Text('${_signed(_n(l['difference']))} · ${_signedMoney(_n(l['differenceValue']))}',
                        style: TextStyle(color: _tone(l['difference']), fontSize: 12, fontWeight: FontWeight.w600)),
                  ]),
          ),
        ),
    ]);
  }
}

/// Counted-quantity dialog; owns its text controller so it is disposed only after the dialog is gone.
/// Pops with (value: text) to save, (value: null) to clear, or nothing when cancelled.
class _QtyDialog extends StatefulWidget {
  const _QtyDialog({required this.line});

  final Map<String, dynamic> line;

  @override
  State<_QtyDialog> createState() => _QtyDialogState();
}

class _QtyDialogState extends State<_QtyDialog> {
  late final TextEditingController _ctl =
      TextEditingController(text: widget.line['countedQty'] == null ? '' : _q(widget.line['countedQty']));

  @override
  void dispose() {
    _ctl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final line = widget.line;
    return AlertDialog(
      title: Text('${line['name']}'),
      content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('Sistemdə: ${_q(line['systemQty'])} ${line['unit']}', style: TextStyle(color: Colors.grey.shade700)),
        const SizedBox(height: 8),
        TextField(
          controller: _ctl,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(labelText: 'Sayılan miqdar', suffixText: '${line['unit']}'),
          onSubmitted: (v) => Navigator.pop(context, (value: v)),
        ),
      ]),
      actions: [
        if (line['countedQty'] != null) TextButton(onPressed: () => Navigator.pop(context, (value: null)), child: const Text('Təmizlə')),
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Ləğv et')),
        FilledButton(onPressed: () => Navigator.pop(context, (value: _ctl.text)), child: const Text('Saxla')),
      ],
    );
  }
}
