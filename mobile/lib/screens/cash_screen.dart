import 'dart:math';

import 'package:flutter/material.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

const _kindLabels = {'CASH': 'Nağd kassa', 'BANK': 'Bank hesabı', 'CARD': 'Kart hesabı'};
const moveLabels = {
  'OPENING': 'Kassa açılışı',
  'DEPOSIT': 'Mədaxil',
  'WITHDRAWAL': 'Məxaric',
  'TRANSFER_IN': 'Köçürmə (daxil)',
  'TRANSFER_OUT': 'Köçürmə (çıxış)',
  'DEBT_COLLECTION': 'Borc ödənişi alındı',
  'SUPPLIER_PAYMENT': 'Şirkətə borc ödənildi',
  'EXPENSE': 'Xərc',
  'EXPENSE_REVERSAL': 'Xərc geri qaytarıldı',
  'PAYMENT_REVERSAL': 'Ödəniş ləğvi / düzəlişi',
  'SALE': 'Satış',
  'SALE_REFUND': 'Satış qaytarıldı',
  'COUNT_ADJUSTMENT': 'Sayım fərqi',
};

/// Random v4 UUID for "the same request sent twice is recorded once".
String newRequestId() {
  final r = Random.secure();
  final b = List<int>.generate(16, (_) => r.nextInt(256));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  final h = b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();
  return '${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}';
}

/// Kassa və bank: balances, movements, mədaxil / məxaric / köçürmə / sayım / açılış and the end-of-day report.
class CashScreen extends StatefulWidget {
  const CashScreen({super.key, required this.session});

  final Session session;

  @override
  State<CashScreen> createState() => _CashScreenState();
}

class _CashScreenState extends State<CashScreen> {
  late Future<List<dynamic>> _data = _fetch();

  Future<List<dynamic>> _fetch() => Future.wait([
        widget.session.api.get('/api/cash/accounts'),
        widget.session.api.get('/api/cash/movements', query: {'limit': '150'}),
      ]);

  void _reload() => setState(() => _data = _fetch());

  Future<void> _action(String type, List<Map<String, dynamic>> accounts, {String? accountId}) async {
    final msg = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
        child: _MoveSheet(session: widget.session, type: type, accounts: accounts, accountId: accountId),
      ),
    );
    if (msg == null || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
    _reload();
  }

  Future<void> _dayReport() async {
    try {
      final r = await widget.session.api.get('/api/cash/day-report') as Map<String, dynamic>;
      if (!mounted) return;
      double n(Object? v) => (v as num).toDouble();
      await showModalBottomSheet<void>(
        context: context,
        showDragHandle: true,
        builder: (ctx) => SafeArea(
          child: ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
            Text('Gün sonu — ${formatDate(DateTime.parse(r['date'] as String))}',
                style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: navy)),
            for (final a in (r['accounts'] as List).cast<Map<String, dynamic>>())
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text('${a['accountName']}'),
                subtitle: Text('Gün əvvəli ${formatMoney(n(a['opening']))} · +${formatMoney(n(a['in']))} · −${formatMoney(n(a['out']))}'),
                trailing: Text(formatMoney(n(a['closing'])), style: const TextStyle(fontWeight: FontWeight.w800)),
              ),
            const Divider(),
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Cəmi', style: TextStyle(fontWeight: FontWeight.w700)),
              subtitle: Text('+${formatMoney(n(r['totalIn']))} · −${formatMoney(n(r['totalOut']))}'),
              trailing: Text(formatMoney(n(r['totalClosing'])), style: const TextStyle(fontWeight: FontWeight.w800)),
            ),
          ]),
        ),
      );
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final owner = widget.session.me!.isOwner;
    return FutureBuilder<List<dynamic>>(
      future: _data,
      builder: (context, snap) {
        final accounts = snap.hasData ? (snap.data![0] as List).cast<Map<String, dynamic>>() : <Map<String, dynamic>>[];
        final moves = snap.hasData ? (snap.data![1] as List).cast<Map<String, dynamic>>() : <Map<String, dynamic>>[];
        double total(String k) =>
            accounts.where((a) => a['kind'] == k).fold<double>(0, (s, a) => s + (a['balance'] as num).toDouble());
        final active = accounts.where((a) => a['active'] == true).toList();
        return Scaffold(
          appBar: AppBar(title: const Text('Kassa və bank'), actions: [
            IconButton(tooltip: 'Gün sonu hesabatı', icon: const Icon(Icons.summarize_outlined), onPressed: _dayReport),
          ]),
          floatingActionButton: snap.hasData
              ? FloatingActionButton.extended(
                  onPressed: () => showModalBottomSheet<void>(
                    context: context,
                    showDragHandle: true,
                    builder: (ctx) => SafeArea(
                      child: Column(mainAxisSize: MainAxisSize.min, children: [
                        for (final a in [
                          ['deposit', 'Mədaxil', Icons.south_west],
                          ['withdraw', 'Məxaric', Icons.north_east],
                          ['transfer', 'Köçürmə', Icons.swap_horiz],
                          ['count', 'Sayım (inventarizasiya)', Icons.fact_check_outlined],
                          if (owner) ['opening', 'Kassa açılışı', Icons.lock_open],
                        ])
                          ListTile(
                            leading: Icon(a[2] as IconData, color: navy),
                            title: Text(a[1] as String),
                            onTap: () {
                              Navigator.pop(ctx);
                              _action(a[0] as String, active);
                            },
                          ),
                      ]),
                    ),
                  ),
                  icon: const Icon(Icons.add),
                  label: const Text('Əməliyyat'),
                )
              : null,
          body: snap.hasError
              ? ListView(children: [Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!)))])
              : !snap.hasData
                  ? const Center(child: CircularProgressIndicator())
                  : RefreshIndicator(
                      onRefresh: () async {
                        _reload();
                        await _data;
                      },
                      child: ListView(padding: const EdgeInsets.fromLTRB(12, 12, 12, 96), children: [
                        Row(children: [
                          for (final k in const [
                            ['CASH', Color(0xFF059669)],
                            ['BANK', Color(0xFF0284C7)],
                            ['CARD', Color(0xFF7C3AED)],
                          ])
                            Expanded(
                              child: Container(
                                margin: const EdgeInsets.symmetric(horizontal: 3),
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(color: k[1] as Color, borderRadius: BorderRadius.circular(16)),
                                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                                  Text(_kindLabels[k[0]]!, style: const TextStyle(color: Colors.white70, fontSize: 11)),
                                  FittedBox(
                                      child: Text(formatMoney(total(k[0] as String)),
                                          style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.w800))),
                                ]),
                              ),
                            ),
                        ]),
                        const SizedBox(height: 12),
                        for (final a in accounts)
                          Card(
                            child: ListTile(
                              title: Text('${a['name']}${a['isDefault'] == true ? ' · əsas' : ''}${a['active'] == true ? '' : ' (deaktiv)'}'),
                              subtitle: Text(_kindLabels[a['kind']] ?? '${a['kind']}'),
                              trailing: Text(formatMoney((a['balance'] as num).toDouble()),
                                  style: TextStyle(
                                      fontWeight: FontWeight.w800,
                                      color: (a['balance'] as num) < 0 ? Colors.red.shade700 : navy)),
                              onTap: a['active'] == true ? () => _action('count', active, accountId: a['id'] as String) : null,
                            ),
                          ),
                        Text('Borc ödənişləri avtomatik düşür: nağd → əsas kassa, kart → kart hesabı, köçürmə → bank.',
                            style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
                        const SizedBox(height: 16),
                        const Text('Hərəkətlər', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: navy)),
                        if (moves.isEmpty) const EmptyState(title: 'Hərəkət yoxdur'),
                        for (final m in moves)
                          ListTile(
                            contentPadding: EdgeInsets.zero,
                            leading: Icon(m['direction'] == 'IN' ? Icons.south_west : Icons.north_east,
                                color: m['direction'] == 'IN' ? Colors.green.shade700 : Colors.red.shade700),
                            title: Text(moveLabels[m['kind']] ?? '${m['kind']}'),
                            subtitle: Text([
                              formatDate(DateTime.parse(m['occurredOn'] as String)),
                              '${m['accountName']}',
                              if (m['note'] != null) '${m['note']}',
                              '${m['actorName']}',
                            ].join(' · ')),
                            trailing: Text('${m['direction'] == 'IN' ? '+' : '−'}${formatMoney((m['amount'] as num).toDouble())}',
                                style: TextStyle(
                                    fontWeight: FontWeight.w700,
                                    color: m['direction'] == 'IN' ? Colors.green.shade700 : Colors.red.shade700)),
                          ),
                      ]),
                    ),
        );
      },
    );
  }
}

class _MoveSheet extends StatefulWidget {
  const _MoveSheet({required this.session, required this.type, required this.accounts, this.accountId});

  final Session session;
  final String type;
  final List<Map<String, dynamic>> accounts;
  final String? accountId;

  @override
  State<_MoveSheet> createState() => _MoveSheetState();
}

class _MoveSheetState extends State<_MoveSheet> {
  late String? _from = widget.accountId ??
      (widget.accounts.where((a) => a['kind'] == 'CASH' && a['isDefault'] == true).firstOrNull ?? widget.accounts.firstOrNull)?['id']
          as String?;
  String? _to;
  final _amount = TextEditingController();
  final _note = TextEditingController();
  final _requestId = newRequestId();
  bool _busy = false;
  String? _error;

  static const _titles = {
    'deposit': 'Mədaxil',
    'withdraw': 'Məxaric',
    'transfer': 'Köçürmə',
    'count': 'Kassa sayımı',
    'opening': 'Kassa açılışı',
  };

  @override
  void dispose() {
    _amount.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final v = double.tryParse(_amount.text.trim().replaceAll(',', '.'));
    if (v == null || v < 0 || (widget.type != 'count' && v == 0) || _from == null || (widget.type == 'transfer' && _to == null)) {
      setState(() => _error = 'Hesabı və məbləği düzgün seçin');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    final amount = v.toStringAsFixed(2);
    final note = _note.text.trim().isEmpty ? null : _note.text.trim();
    try {
      String msg;
      if (widget.type == 'transfer') {
        await widget.session.api.post('/api/cash/transfer',
            {'fromAccountId': _from, 'toAccountId': _to, 'amount': amount, 'note': note, 'clientRequestId': _requestId});
        msg = 'Köçürmə edildi: ${formatMoney(v)}';
      } else if (widget.type == 'count') {
        final r = await widget.session.api.post('/api/cash/count', {'accountId': _from, 'countedAmount': amount, 'note': note})
            as Map<String, dynamic>;
        final d = (r['difference'] as num).toDouble();
        msg = d == 0 ? 'Sayım uyğundur' : 'Sayım fərqi: ${formatMoney(d)}';
      } else {
        await widget.session.api
            .post('/api/cash/${widget.type}', {'accountId': _from, 'amount': amount, 'note': note, 'clientRequestId': _requestId});
        msg = '${_titles[widget.type]}: ${formatMoney(v)}';
      }
      if (mounted) Navigator.pop(context, msg);
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  DropdownButtonFormField<String> _picker(String label, String? value, ValueChanged<String?> onChanged, {String? exclude}) =>
      DropdownButtonFormField<String>(
        initialValue: value,
        isExpanded: true,
        decoration: InputDecoration(labelText: label),
        items: [
          for (final a in widget.accounts)
            if (a['id'] != exclude)
              DropdownMenuItem(
                  value: a['id'] as String, child: Text('${a['name']} — ${formatMoney((a['balance'] as num).toDouble())}')),
        ],
        onChanged: onChanged,
      );

  @override
  Widget build(BuildContext context) => ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
        Text(_titles[widget.type]!, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: navy)),
        const SizedBox(height: 12),
        if (_error != null) ErrorBanner(_error!),
        _picker(widget.type == 'transfer' ? 'Haradan' : 'Hesab', _from, (v) => setState(() => _from = v)),
        if (widget.type == 'transfer') ...[
          const SizedBox(height: 10),
          _picker('Haraya', _to, (v) => setState(() => _to = v), exclude: _from),
        ],
        const SizedBox(height: 10),
        TextField(
          controller: _amount,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(labelText: widget.type == 'count' ? 'Sayılan pul (₼)' : 'Məbləğ (₼)'),
        ),
        TextField(controller: _note, maxLength: 500, decoration: const InputDecoration(labelText: 'Qeyd')),
        if (widget.type == 'deposit' || widget.type == 'withdraw')
          Text('Mədaxil / məxaric gəlir və ya xərc sayılmır. Xərc üçün “Xərclər” bölməsindən istifadə edin.',
              style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
        const SizedBox(height: 12),
        FilledButton(
          onPressed: _busy ? null : _save,
          child: _busy ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Text('Təsdiqlə'),
        ),
      ]);
}
