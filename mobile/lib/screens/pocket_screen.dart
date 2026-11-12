import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

double _n(Object? v) => v == null ? 0 : (v as num).toDouble();
String _iso(DateTime d) => DateFormat('yyyy-MM-dd').format(d);

class PocketEntry {
  const PocketEntry(this.id, this.kind, this.amount, this.note, this.createdAt);

  final String id;
  final String kind; // IN | OUT
  final double amount;
  final String? note;
  final DateTime createdAt;

  bool get isIn => kind == 'IN';

  factory PocketEntry.fromJson(Map<String, dynamic> j) => PocketEntry(
        j['id'] as String,
        j['kind'] as String,
        _n(j['amount']),
        j['note'] as String?,
        DateTime.parse(j['createdAt'] as String),
      );
}

class PocketDay {
  const PocketDay(this.date, this.opening, this.added, this.spent, this.closing, this.entries);

  final DateTime date;
  final double opening, added, spent, closing;
  final List<PocketEntry> entries;

  factory PocketDay.fromJson(Map<String, dynamic> j) => PocketDay(
        DateTime.parse(j['date'] as String),
        _n(j['openingBalance']),
        _n(j['added']),
        _n(j['spent']),
        _n(j['closingBalance']),
        (j['entries'] as List).map((e) => PocketEntry.fromJson(e as Map<String, dynamic>)).toList(),
      );
}

Future<PocketDay> _fetchDay(Session s, DateTime? date) => s.api
    .get('/api/pocket', query: date == null ? null : {'date': _iso(date)})
    .then((j) => PocketDay.fromJson(j as Map<String, dynamic>));

/// Opens the "add to pocket" / "spend" sheet. Returns true when something was saved.
Future<bool> openPocketSheet(BuildContext context, {required Session session, required bool isIn, DateTime? date}) async {
  final saved = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    useSafeArea: true,
    builder: (ctx) => Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
      child: _EntrySheet(session: session, isIn: isIn, date: date),
    ),
  );
  return saved ?? false;
}

/// Panel card: what is in the pocket now, with quick "+ Pul qoy" and "− Xərclə" buttons.
class PocketCard extends StatefulWidget {
  const PocketCard({super.key, required this.session});

  final Session session;

  @override
  State<PocketCard> createState() => PocketCardState();
}

class PocketCardState extends State<PocketCard> {
  late Future<PocketDay> _day = _fetchDay(widget.session, null);

  void reload() => setState(() => _day = _fetchDay(widget.session, null));

  Future<void> _quick(bool isIn) async {
    if (await openPocketSheet(context, session: widget.session, isIn: isIn) && mounted) reload();
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<PocketDay>(
      future: _day,
      builder: (context, snap) {
        // The card is a convenience: if the server does not know "Cib pulu" yet, simply hide it.
        if (snap.hasError) return const SizedBox.shrink();
        final d = snap.data;
        return Padding(
          padding: const EdgeInsets.only(bottom: 16),
          child: Material(
            borderRadius: BorderRadius.circular(22),
            clipBehavior: Clip.antiAlias,
            child: Ink(
              decoration: const BoxDecoration(
                gradient: LinearGradient(colors: [Color(0xFF1E3A5F), navy], begin: Alignment.topLeft, end: Alignment.bottomRight),
              ),
              child: InkWell(
                onTap: () async {
                  await Navigator.of(context).push(MaterialPageRoute(builder: (_) => PocketScreen(session: widget.session)));
                  reload();
                },
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(18, 16, 18, 14),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Row(children: [
                      Container(
                        padding: const EdgeInsets.all(8),
                        decoration: BoxDecoration(color: Colors.white12, borderRadius: BorderRadius.circular(12)),
                        child: const Icon(Icons.account_balance_wallet, color: gold, size: 22),
                      ),
                      const SizedBox(width: 10),
                      const Expanded(
                        child: Text('Cib pulu', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 15)),
                      ),
                      const Icon(Icons.chevron_right, color: Colors.white54),
                    ]),
                    const SizedBox(height: 12),
                    const Text('Cibimdə qalan', style: TextStyle(color: Colors.white60, fontSize: 12)),
                    if (d == null)
                      const Padding(
                        padding: EdgeInsets.symmetric(vertical: 8),
                        child: SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2, color: gold)),
                      )
                    else ...[
                      FittedBox(
                        fit: BoxFit.scaleDown,
                        alignment: Alignment.centerLeft,
                        child: Text(formatMoney(d.closing),
                            style: const TextStyle(color: gold, fontSize: 28, fontWeight: FontWeight.w800)),
                      ),
                      Text(
                        'Bu gün: +${formatMoney(d.added)} qoyuldu · −${formatMoney(d.spent)} xərcləndi',
                        style: const TextStyle(color: Colors.white70, fontSize: 11),
                      ),
                    ],
                    const SizedBox(height: 12),
                    Row(children: [
                      Expanded(
                        child: FilledButton.icon(
                          style: FilledButton.styleFrom(
                              backgroundColor: Colors.green.shade600, padding: const EdgeInsets.symmetric(vertical: 10)),
                          onPressed: () => _quick(true),
                          icon: const Icon(Icons.add, size: 18),
                          label: const Text('Pul qoy'),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: FilledButton.icon(
                          style: FilledButton.styleFrom(
                              backgroundColor: Colors.red.shade600, padding: const EdgeInsets.symmetric(vertical: 10)),
                          onPressed: () => _quick(false),
                          icon: const Icon(Icons.remove, size: 18),
                          label: const Text('Xərclə'),
                        ),
                      ),
                    ]),
                  ]),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

/// "Cib pulu": one day at a time — left from earlier days, put in, spent, and what is left at the end of the day.
class PocketScreen extends StatefulWidget {
  const PocketScreen({super.key, required this.session});

  final Session session;

  @override
  State<PocketScreen> createState() => _PocketScreenState();
}

class _PocketScreenState extends State<PocketScreen> {
  DateTime _date = _today();
  late Future<PocketDay> _day = _fetchDay(widget.session, _date);
  late Future<List<Map<String, dynamic>>> _history = _fetchHistory();

  static DateTime _today() {
    final n = DateTime.now();
    return DateTime(n.year, n.month, n.day);
  }

  bool get _isToday => _date == _today();

  Future<List<Map<String, dynamic>>> _fetchHistory() => widget.session.api
      .get('/api/pocket/history', query: {'days': '30'}).then((j) => (j as List).cast<Map<String, dynamic>>());

  void _reload() => setState(() {
        _day = _fetchDay(widget.session, _date);
        _history = _fetchHistory();
      });

  void _go(DateTime d) {
    _date = d;
    _reload();
  }

  Future<void> _add(bool isIn) async {
    if (await openPocketSheet(context, session: widget.session, isIn: isIn, date: _date) && mounted) _reload();
  }

  Future<void> _delete(PocketEntry e) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Qeydi silək?'),
        content: Text('${e.isIn ? 'Qoyulan' : 'Xərclənən'}: ${formatMoney(e.amount)}${e.note == null ? '' : ' — ${e.note}'}'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Ləğv et')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Sil')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await widget.session.api.delete('/api/pocket/${e.id}');
      _reload();
    } catch (err) {
      if (mounted) showError(context, err);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Cib pulu')),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
          child: Row(children: [
            Expanded(
              child: FilledButton.icon(
                style: FilledButton.styleFrom(backgroundColor: Colors.green.shade600),
                onPressed: () => _add(true),
                icon: const Icon(Icons.add),
                label: const Text('Pul qoy'),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: FilledButton.icon(
                style: FilledButton.styleFrom(backgroundColor: Colors.red.shade600),
                onPressed: () => _add(false),
                icon: const Icon(Icons.remove),
                label: const Text('Xərclə'),
              ),
            ),
          ]),
        ),
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          _reload();
          await _day.catchError((_) => PocketDay(_date, 0, 0, 0, 0, const []));
        },
        child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 24), children: [
          Row(children: [
            IconButton(
                tooltip: 'Əvvəlki gün', icon: const Icon(Icons.chevron_left), onPressed: () => _go(_date.subtract(const Duration(days: 1)))),
            Expanded(
              child: TextButton(
                onPressed: () async {
                  final d = await showDatePicker(
                      context: context, initialDate: _date, firstDate: DateTime(2020), lastDate: _today(), locale: const Locale('az'));
                  if (d != null) _go(d);
                },
                child: Text(_isToday ? 'Bu gün · ${formatDate(_date)}' : formatDate(_date),
                    style: const TextStyle(fontWeight: FontWeight.w700, color: navy, fontSize: 16)),
              ),
            ),
            IconButton(
              tooltip: 'Növbəti gün',
              icon: const Icon(Icons.chevron_right),
              onPressed: _isToday ? null : () => _go(_date.add(const Duration(days: 1))),
            ),
          ]),
          FutureBuilder<PocketDay>(
            future: _day,
            builder: (context, snap) {
              if (snap.hasError) return ErrorBanner(errorMessage(snap.error!));
              if (!snap.hasData) return const Padding(padding: EdgeInsets.all(32), child: Center(child: CircularProgressIndicator()));
              final d = snap.data!;
              return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                StatTile(
                  label: _isToday ? 'Cibimdə qalan' : 'Gün sonu qalan',
                  value: formatMoney(d.closing),
                  hint: 'Gün başı: ${formatMoney(d.opening)}',
                ),
                const SizedBox(height: 10),
                Row(children: [
                  Expanded(child: _Figure(label: 'Qoyulan', value: '+${formatMoney(d.added)}', color: Colors.green.shade700)),
                  const SizedBox(width: 10),
                  Expanded(child: _Figure(label: 'Xərclənən', value: '−${formatMoney(d.spent)}', color: Colors.red.shade700)),
                ]),
                const SizedBox(height: 6),
                Text('Dünəndən qalan pul avtomatik bu günə keçir. Səhər yalnız cibinizə əlavə etdiyiniz pulu yazın.',
                    style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
                const SizedBox(height: 16),
                if (d.entries.isEmpty)
                  const EmptyState(title: 'Bu gün üçün qeyd yoxdur', text: '"Pul qoy" və ya "Xərclə" düyməsi ilə yazın.')
                else
                  Card(
                    margin: EdgeInsets.zero,
                    child: Column(children: [
                      for (final (i, e) in d.entries.indexed) ...[
                        if (i > 0) const Divider(height: 1),
                        ListTile(
                          leading: CircleAvatar(
                            backgroundColor: e.isIn ? Colors.green.shade50 : Colors.red.shade50,
                            child: Icon(e.isIn ? Icons.south_west : Icons.north_east,
                                color: e.isIn ? Colors.green.shade700 : Colors.red.shade700, size: 18),
                          ),
                          title: Text(e.note ?? (e.isIn ? 'Cibə qoyuldu' : 'Xərc')),
                          subtitle: Text(DateFormat('HH:mm').format(e.createdAt.toLocal())),
                          trailing: Text('${e.isIn ? '+' : '−'}${formatMoney(e.amount)}',
                              style: TextStyle(fontWeight: FontWeight.w800, color: e.isIn ? Colors.green.shade700 : Colors.red.shade700)),
                          onLongPress: () => _delete(e),
                        ),
                      ],
                    ]),
                  ),
                if (d.entries.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text('Silmək üçün qeydə basıb saxlayın.', style: TextStyle(color: Colors.grey.shade500, fontSize: 11)),
                  ),
              ]);
            },
          ),
          const SizedBox(height: 22),
          const Text('Son günlər', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
          const SizedBox(height: 8),
          FutureBuilder<List<Map<String, dynamic>>>(
            future: _history,
            builder: (context, snap) {
              if (!snap.hasData || snap.data!.isEmpty) return const SizedBox.shrink();
              return Card(
                margin: EdgeInsets.zero,
                child: Column(children: [
                  for (final (i, h) in snap.data!.indexed) ...[
                    if (i > 0) const Divider(height: 1),
                    ListTile(
                      dense: true,
                      onTap: () => _go(DateTime.parse(h['date'] as String)),
                      title: Text(formatDate(DateTime.parse(h['date'] as String)), style: const TextStyle(fontWeight: FontWeight.w600)),
                      subtitle: Text.rich(TextSpan(children: [
                        TextSpan(text: '+${formatMoney(_n(h['added']))}', style: TextStyle(color: Colors.green.shade700)),
                        const TextSpan(text: '  '),
                        TextSpan(text: '−${formatMoney(_n(h['spent']))}', style: TextStyle(color: Colors.red.shade700)),
                      ])),
                      trailing: Text(formatMoney(_n(h['closingBalance'])), style: const TextStyle(fontWeight: FontWeight.w800, color: navy)),
                    ),
                  ],
                ]),
              );
            },
          ),
        ]),
      ),
    );
  }
}

class _Figure extends StatelessWidget {
  const _Figure({required this.label, required this.value, required this.color});

  final String label;
  final String value;
  final Color color;

  @override
  Widget build(BuildContext context) => Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
            const SizedBox(height: 4),
            FittedBox(
              fit: BoxFit.scaleDown,
              alignment: Alignment.centerLeft,
              child: Text(value, style: TextStyle(fontWeight: FontWeight.w800, fontSize: 18, color: color)),
            ),
          ]),
        ),
      );
}

class _EntrySheet extends StatefulWidget {
  const _EntrySheet({required this.session, required this.isIn, this.date});

  final Session session;
  final bool isIn;
  final DateTime? date;

  @override
  State<_EntrySheet> createState() => _EntrySheetState();
}

class _EntrySheetState extends State<_EntrySheet> {
  final _amount = TextEditingController();
  final _note = TextEditingController();
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _amount.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final amount = double.tryParse(_amount.text.trim().replaceAll(',', '.'));
    if (amount == null || amount <= 0) {
      setState(() => _error = 'Məbləği düzgün yazın');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await widget.session.api.post('/api/pocket', {
        'kind': widget.isIn ? 'IN' : 'OUT',
        'amount': amount.toStringAsFixed(2),
        if (_note.text.trim().isNotEmpty) 'note': _note.text.trim(),
        if (widget.date != null) 'date': _iso(widget.date!),
      });
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final color = widget.isIn ? Colors.green.shade700 : Colors.red.shade700;
    final suggestions = widget.isIn ? const ['Səhər', 'Maaş', 'Bankdan'] : const ['Yemək', 'Taksi', 'Yanacaq', 'Market', 'Telefon'];
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(widget.isIn ? 'Cibə pul qoy' : 'Cibdən xərclə',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: color)),
        const SizedBox(height: 14),
        if (_error != null) ErrorBanner(_error!),
        TextField(
          controller: _amount,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9.,]'))],
          decoration: const InputDecoration(labelText: 'Məbləğ', suffixText: '₼'),
          style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _note,
          maxLength: 200,
          textCapitalization: TextCapitalization.sentences,
          decoration: InputDecoration(labelText: widget.isIn ? 'Qeyd (istəyə görə)' : 'Nəyə xərclədiniz?'),
        ),
        Wrap(spacing: 6, children: [
          for (final s in suggestions)
            ActionChip(label: Text(s), onPressed: () => setState(() => _note.text = s)),
        ]),
        const SizedBox(height: 14),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: color),
          onPressed: _saving ? null : _save,
          child: _saving
              ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : const Text('Yadda saxla'),
        ),
      ]),
    );
  }
}
