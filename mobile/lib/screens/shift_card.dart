import 'package:flutter/material.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

double _n(Object? v) => (v as num?)?.toDouble() ?? 0;

const _kind = {
  'SALE': 'Satış (nağd)', 'SALE_REFUND': 'Qaytarılan pul', 'DEBT_COLLECTION': 'Borc ödənişi', 'SUPPLIER_PAYMENT': 'Tədarükçüyə ödəniş',
  'EXPENSE': 'Xərc', 'DEPOSIT': 'Mədaxil', 'WITHDRAWAL': 'Məxaric', 'PURCHASE': 'Mal alışı', 'TRANSFER_IN': 'Köçürmə (daxil)',
  'TRANSFER_OUT': 'Köçürmə (çıxış)', 'COUNT_ADJUSTMENT': 'Sayım fərqi', 'PAYMENT_REVERSAL': 'Ödəniş ləğvi', 'EXPENSE_REVERSAL': 'Xərc qaytarıldı',
};

/// Kassir növbəsi on the phone: open with the drawer cash, see the running totals, close with the counted cash
/// and read the Z-report.
class ShiftCard extends StatefulWidget {
  const ShiftCard({super.key, required this.session});

  final Session session;

  @override
  State<ShiftCard> createState() => ShiftCardState();
}

class ShiftCardState extends State<ShiftCard> {
  Map<String, dynamic>? _shift;
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    reload();
  }

  Future<void> reload() async {
    try {
      final r = await widget.session.api.get('/api/shifts/current');
      if (mounted) setState(() { _shift = r as Map<String, dynamic>?; _loaded = true; });
    } catch (_) {
      if (mounted) setState(() => _loaded = true);
    }
  }

  Future<String?> _askCash(String title, String label, {String? hint}) {
    final c = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          if (hint != null) Padding(padding: const EdgeInsets.only(bottom: 8), child: Text(hint)),
          TextField(
            controller: c,
            autofocus: true,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: label, suffixText: '₼'),
          ),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Ləğv et')),
          FilledButton(onPressed: () => Navigator.pop(ctx, c.text.trim().replaceAll(',', '.')), child: const Text('Təsdiqlə')),
        ],
      ),
    ).whenComplete(c.dispose);
  }

  Future<void> _open() async {
    final v = await _askCash('Növbəni aç', 'Kassada olan pul (açılış)');
    if (v == null) return;
    try {
      final r = await widget.session.api.post('/api/shifts/open', {'openingCash': double.tryParse(v) ?? 0}) as Map<String, dynamic>;
      if (mounted) setState(() => _shift = r);
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _close() async {
    final s = _shift;
    if (s == null) return;
    final v = await _askCash('Növbəni bağla', 'Kassadakı pulu sayın', hint: 'Kassada olmalıdır: ${formatMoney(_n(s['expectedCash']))}');
    if (v == null || !mounted) return;
    final counted = double.tryParse(v);
    if (counted == null) return showError(context, 'Kassadakı pulu yazın');
    try {
      final z = await widget.session.api.post('/api/shifts/${s['id']}/close', {'countedCash': counted}) as Map<String, dynamic>;
      if (!mounted) return;
      setState(() => _shift = null);
      await showReport(context, z);
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_loaded) return const SizedBox.shrink();
    final s = _shift;
    final open = s != null;
    return Card(
      color: open ? Colors.green.shade50 : Colors.orange.shade50,
      child: ListTile(
        leading: Icon(open ? Icons.lock_open : Icons.lock_clock_outlined, color: open ? Colors.green.shade800 : Colors.orange.shade800),
        title: Text(open ? 'Növbə açıqdır' : 'Növbə açılmayıb', style: const TextStyle(fontWeight: FontWeight.w700)),
        subtitle: Text(open
            ? '${s['salesCount']} satış · ${formatMoney(_n(s['salesTotal']))} · kassada olmalı ${formatMoney(_n(s['expectedCash']))}'
            : 'Gün sonu Z-hesabatı üçün işə başlayanda növbəni açın'),
        trailing: Row(mainAxisSize: MainAxisSize.min, children: [
          if (open) IconButton(tooltip: 'X-hesabat', icon: const Icon(Icons.receipt_long_outlined), onPressed: () async {
            await reload();
            if (!context.mounted || _shift == null) return;
            await showReport(context, _shift!);
          }),
          FilledButton(
            style: FilledButton.styleFrom(visualDensity: VisualDensity.compact, backgroundColor: open ? navy : gold, foregroundColor: open ? Colors.white : navy),
            onPressed: open ? _close : _open,
            child: Text(open ? 'Bağla' : 'Aç'),
          ),
        ]),
      ),
    );
  }
}

/// X / Z-report of a shift.
Future<void> showReport(BuildContext context, Map<String, dynamic> r) {
  Widget row(String l, Object? v, {bool bold = false, Color? color}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Row(children: [
          Expanded(child: Text(l, style: TextStyle(color: color, fontWeight: bold ? FontWeight.w700 : null))),
          Text(v is num ? formatMoney(v.toDouble()) : '$v', style: TextStyle(color: color, fontWeight: bold ? FontWeight.w800 : FontWeight.w600)),
        ]),
      );
  final diff = r['difference'] == null ? null : _n(r['difference']);
  final cash = (r['cashByKind'] as Map?)?.cast<String, dynamic>() ?? {};
  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (ctx) => ListView(
      shrinkWrap: true,
      padding: EdgeInsets.fromLTRB(20, 0, 20, 20 + sheetBottomInset(ctx)),
      children: [
        Text(r['open'] == true ? 'X-hesabat (növbə davam edir)' : 'Z-hesabat',
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: navy)),
        Text('${r['cashierName']} · ${formatDateTime(DateTime.tryParse('${r['openedAt']}') ?? DateTime.now())}',
            style: TextStyle(color: Colors.grey.shade600)),
        const Divider(),
        row('Satış sayı', r['salesCount']),
        row('Satış cəmi', _n(r['salesTotal']), bold: true),
        row('Nağd', _n(r['paidCash'])),
        row('Kart', _n(r['paidCard'])),
        row('Borca', _n(r['credit'])),
        if (_n(r['returnsTotal']) > 0) row('Qaytarma (${r['returnsCount']})', _n(r['returnsTotal']), color: Colors.red.shade700),
        const Divider(),
        row('Açılışda kassada', _n(r['openingCash'])),
        for (final e in cash.entries) row(_kind[e.key] ?? e.key, _n(e.value)),
        row('Kassada olmalıdır', _n(r['expectedCash']), bold: true),
        if (r['countedCash'] != null) row('Sayıldı', _n(r['countedCash']), bold: true),
        if (diff != null)
          row(diff < 0 ? 'Əskik' : diff > 0 ? 'Artıq' : 'Fərq yoxdur', diff,
              bold: true, color: diff < 0 ? Colors.red.shade700 : diff > 0 ? Colors.orange.shade800 : Colors.green.shade700),
      ],
    ),
  );
}
