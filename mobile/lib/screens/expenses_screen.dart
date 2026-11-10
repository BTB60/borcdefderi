import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'cash_screen.dart' show newRequestId;
import 'common.dart';

const _freqLabels = {'DAILY': 'Gündəlik', 'WEEKLY': 'Həftəlik', 'MONTHLY': 'Aylıq', 'YEARLY': 'İllik'};

String _iso(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

double _n(Object? v) => (v as num?)?.toDouble() ?? 0;

/// Xərclər: this month's expenses, recurring expenses and budgets. Never mixed with debts.
class ExpensesScreen extends StatefulWidget {
  const ExpensesScreen({super.key, required this.session});

  final Session session;

  @override
  State<ExpensesScreen> createState() => _ExpensesScreenState();
}

class _ExpensesScreenState extends State<ExpensesScreen> {
  List<Map<String, dynamic>> _categories = [];
  late Future<List<dynamic>> _data = _fetch();
  DateTime _month = DateTime(DateTime.now().year, DateTime.now().month);

  Future<List<dynamic>> _fetch() async {
    final from = _iso(_month);
    final to = _iso(DateTime(_month.year, _month.month + 1, 0));
    final res = await Future.wait([
      widget.session.api.get('/api/expenses', query: {'from': from, 'to': to, 'size': '200'}),
      widget.session.api.get('/api/recurring-expenses'),
      widget.session.api.get('/api/budgets', query: {'month': from}),
      widget.session.api.get('/api/expense-categories'),
    ]);
    _categories = (res[3] as List).cast<Map<String, dynamic>>();
    return res;
  }

  void _reload() => setState(() => _data = _fetch());

  void _snack(String m) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(m)));

  Future<void> _edit([Map<String, dynamic>? e]) async {
    final msg = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
        child: _ExpenseSheet(session: widget.session, categories: _categories, expense: e),
      ),
    );
    if (msg == null || !mounted) return;
    _snack(msg);
    _reload();
  }

  Future<void> _delete(Map<String, dynamic> e) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Xərc silinsin?'),
        content: Text('“${e['title']}” (${formatMoney(_n(e['amount']))}). Pul ${e['accountName']} hesabına geri yazılacaq; tarixçə qalır.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Ləğv et')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Sil')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await widget.session.api.delete('/api/expenses/${e['id']}');
      _snack('Xərc silindi');
      _reload();
    } catch (err) {
      if (mounted) showError(context, err);
    }
  }

  Future<void> _payRecurring(Map<String, dynamic> r) async {
    final method = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          ListTile(title: Text('${r['title']} — ${formatMoney(_n(r['amount']))}', style: const TextStyle(fontWeight: FontWeight.w700)),
              subtitle: Text('Dövr: ${formatDate(DateTime.parse(r['nextDueDate'] as String))}')),
          for (final m in const ['CASH', 'CARD', 'TRANSFER', 'OTHER'])
            ListTile(leading: const Icon(Icons.payments_outlined), title: Text('${methodLabels[m]} ilə ödə'), onTap: () => Navigator.pop(ctx, m)),
        ]),
      ),
    );
    if (method == null) return;
    try {
      await widget.session.api.post('/api/recurring-expenses/${r['id']}/pay', {'periodDate': r['nextDueDate'], 'paymentMethod': method});
      _snack('${r['title']} ödənildi');
      _reload();
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _newRecurring() async {
    final msg = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
        child: _RecurringSheet(session: widget.session, categories: _categories),
      ),
    );
    if (msg == null || !mounted) return;
    _snack(msg);
    _reload();
  }

  Future<void> _setBudget() async {
    String? cat;
    final limit = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, set) => AlertDialog(
          title: Text('Büdcə — ${_month.month.toString().padLeft(2, '0')}.${_month.year}'),
          content: Column(mainAxisSize: MainAxisSize.min, children: [
            DropdownButtonFormField<String>(
              initialValue: cat,
              isExpanded: true,
              decoration: const InputDecoration(labelText: 'Kateqoriya'),
              items: [for (final c in _categories) DropdownMenuItem(value: c['id'] as String, child: Text('${c['name']}'))],
              onChanged: (v) => set(() => cat = v),
            ),
            TextField(
                controller: limit,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(labelText: 'Aylıq limit (₼)')),
          ]),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Ləğv et')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Saxla')),
          ],
        ),
      ),
    );
    if (ok != true || cat == null) return;
    try {
      await widget.session.api.put('/api/budgets', {'categoryId': cat, 'month': _iso(_month), 'limit': limit.text.trim().replaceAll(',', '.')});
      _snack('Büdcə saxlanıldı');
      _reload();
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Xərclər'),
          actions: [
            IconButton(
              icon: const Icon(Icons.chevron_left),
              onPressed: () => setState(() {
                _month = DateTime(_month.year, _month.month - 1);
                _data = _fetch();
              }),
            ),
            Center(child: Text('${_month.month.toString().padLeft(2, '0')}.${_month.year}')),
            IconButton(
              icon: const Icon(Icons.chevron_right),
              onPressed: () => setState(() {
                _month = DateTime(_month.year, _month.month + 1);
                _data = _fetch();
              }),
            ),
          ],
          bottom: const TabBar(tabs: [Tab(text: 'Xərclər'), Tab(text: 'Təkrarlanan'), Tab(text: 'Büdcə')]),
        ),
        floatingActionButton: FloatingActionButton.extended(
            onPressed: () => _edit(), icon: const Icon(Icons.add), label: const Text('Xərc əlavə et')),
        body: FutureBuilder<List<dynamic>>(
          future: _data,
          builder: (context, snap) {
            if (snap.hasError) return ListView(children: [Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!)))]);
            if (!snap.hasData) return const Center(child: CircularProgressIndicator());
            final page = snap.data![0] as Map<String, dynamic>;
            final items = (page['items'] as List).cast<Map<String, dynamic>>();
            final recurring = (snap.data![1] as List).cast<Map<String, dynamic>>();
            final budgets = (snap.data![2] as List).cast<Map<String, dynamic>>();
            return TabBarView(children: [
              RefreshIndicator(
                onRefresh: () async => _reload(),
                child: ListView(padding: const EdgeInsets.fromLTRB(12, 12, 12, 96), children: [
                  StatTile(label: 'Bu ayın xərci', value: formatMoney(_n(page['sum'])), hint: '${page['total']} qeyd'),
                  const SizedBox(height: 8),
                  if (items.isEmpty) const EmptyState(title: 'Xərc yoxdur', text: '“Xərc əlavə et” ilə başlayın.'),
                  for (final e in items)
                    Card(
                      child: ListTile(
                        onTap: () => _edit(e),
                        onLongPress: () => _delete(e),
                        title: Text('${e['title']}', style: const TextStyle(fontWeight: FontWeight.w600)),
                        subtitle: Text([
                          formatDate(DateTime.parse(e['expenseDate'] as String)),
                          '${e['categoryName']}',
                          '${e['accountName']}',
                          if ((e['attachmentCount'] as num) > 0) '📎',
                        ].join(' · ')),
                        trailing: Text(formatMoney(_n(e['amount'])), style: TextStyle(fontWeight: FontWeight.w800, color: Colors.red.shade700)),
                      ),
                    ),
                  if (items.isNotEmpty)
                    Text('Redaktə üçün toxunun, silmək üçün basıb saxlayın.', style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
                ]),
              ),
              ListView(padding: const EdgeInsets.fromLTRB(12, 12, 12, 96), children: [
                OutlinedButton.icon(onPressed: _newRecurring, icon: const Icon(Icons.repeat), label: const Text('Təkrarlanan xərc əlavə et')),
                Text('Plan kimi saxlanır; “Ödə” basılanda faktiki xərc yaranır. Eyni dövr iki dəfə ödənmir.',
                    style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
                if (recurring.isEmpty) const EmptyState(title: 'Təkrarlanan xərc yoxdur'),
                for (final r in recurring)
                  Card(
                    child: ListTile(
                      title: Text('${r['title']} · ${_freqLabels[r['frequency']]}'),
                      subtitle: Text('Növbəti: ${formatDate(DateTime.parse(r['nextDueDate'] as String))} · ${r['categoryName']}'
                          '${r['active'] == true ? '' : ' · deaktiv'}'),
                      trailing: r['active'] == true
                          ? FilledButton(
                              style: r['due'] == true ? FilledButton.styleFrom(backgroundColor: Colors.red.shade700) : null,
                              onPressed: () => _payRecurring(r),
                              child: Text('Ödə ${formatMoney(_n(r['amount']))}'),
                            )
                          : null,
                    ),
                  ),
              ]),
              ListView(padding: const EdgeInsets.fromLTRB(12, 12, 12, 96), children: [
                OutlinedButton.icon(onPressed: _setBudget, icon: const Icon(Icons.savings_outlined), label: const Text('Limit təyin et')),
                if (budgets.isEmpty) const EmptyState(title: 'Bu ay üçün büdcə yoxdur'),
                for (final b in budgets)
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Row(children: [
                          Expanded(child: Text('${b['categoryName']}', style: const TextStyle(fontWeight: FontWeight.w700))),
                          Text('${formatMoney(_n(b['actual']))} / ${formatMoney(_n(b['limit']))}'),
                        ]),
                        const SizedBox(height: 6),
                        LinearProgressIndicator(
                          value: ((b['percent'] as num).toDouble() / 100).clamp(0, 1),
                          minHeight: 8,
                          borderRadius: BorderRadius.circular(8),
                          color: b['state'] == 'OVER' ? Colors.red : b['state'] == 'NEAR' ? Colors.orange : Colors.green,
                        ),
                        const SizedBox(height: 4),
                        Text(
                            b['state'] == 'OVER'
                                ? 'Limit keçilib (${b['percent']}%)'
                                : b['state'] == 'NEAR'
                                    ? 'Limitə yaxınlaşır (${b['percent']}%)'
                                    : '${b['percent']}% · qalıq ${formatMoney(_n(b['remaining']))}',
                            style: TextStyle(
                                fontSize: 12,
                                color: b['state'] == 'OVER' ? Colors.red.shade700 : b['state'] == 'NEAR' ? Colors.orange.shade800 : Colors.grey.shade700)),
                      ]),
                    ),
                  ),
              ]),
            ]);
          },
        ),
      ),
    );
  }
}

class _ExpenseSheet extends StatefulWidget {
  const _ExpenseSheet({required this.session, required this.categories, this.expense});

  final Session session;
  final List<Map<String, dynamic>> categories;
  final Map<String, dynamic>? expense;

  @override
  State<_ExpenseSheet> createState() => _ExpenseSheetState();
}

class _ExpenseSheetState extends State<_ExpenseSheet> {
  late final _title = TextEditingController(text: widget.expense?['title'] as String? ?? '');
  late final _amount = TextEditingController(text: widget.expense == null ? '' : _n(widget.expense!['amount']).toStringAsFixed(2));
  late final _payee = TextEditingController(text: widget.expense?['payee'] as String? ?? '');
  late final _note = TextEditingController(text: widget.expense?['note'] as String? ?? '');
  late String? _category = widget.expense?['categoryId'] as String?;
  late String _method = widget.expense?['paymentMethod'] as String? ?? 'CASH';
  late DateTime _date = widget.expense == null ? DateTime.now() : DateTime.parse(widget.expense!['expenseDate'] as String);
  XFile? _photo;
  final _requestId = newRequestId();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _title.dispose();
    _amount.dispose();
    _payee.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final v = double.tryParse(_amount.text.trim().replaceAll(',', '.'));
    if (_title.text.trim().isEmpty || _category == null || v == null || v <= 0) {
      setState(() => _error = 'Ad, kateqoriya və məbləği düzgün daxil edin');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    final body = {
      'title': _title.text.trim(),
      'categoryId': _category,
      'amount': v.toStringAsFixed(2),
      'expenseDate': _iso(_date),
      'paymentMethod': _method,
      'payee': _payee.text.trim().isEmpty ? null : _payee.text.trim(),
      'note': _note.text.trim().isEmpty ? null : _note.text.trim(),
    };
    try {
      final saved = widget.expense == null
          ? await widget.session.api.post('/api/expenses', {...body, 'clientRequestId': _requestId}) as Map<String, dynamic>
          : await widget.session.api.put('/api/expenses/${widget.expense!['id']}', {...body, 'version': widget.expense!['version']})
              as Map<String, dynamic>;
      if (_photo != null) {
        final bytes = await _photo!.readAsBytes();
        await widget.session.api.upload('/api/expenses/${saved['id']}/attachments', 'file', bytes, _photo!.name, 'image/jpeg');
      }
      if (mounted) Navigator.pop(context, widget.expense == null ? 'Xərc əlavə edildi: ${formatMoney(v)}' : 'Xərc yeniləndi');
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
        Text(widget.expense == null ? 'Yeni xərc' : 'Xərci redaktə et',
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: navy)),
        const SizedBox(height: 12),
        if (_error != null) ErrorBanner(_error!),
        TextField(controller: _title, maxLength: 150, decoration: const InputDecoration(labelText: 'Xərcin adı', hintText: 'Məs: Noyabr icarəsi')),
        DropdownButtonFormField<String>(
          initialValue: _category,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Kateqoriya'),
          items: [for (final c in widget.categories) DropdownMenuItem(value: c['id'] as String, child: Text('${c['name']}'))],
          onChanged: (v) => setState(() => _category = v),
        ),
        TextField(
            controller: _amount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: 'Məbləğ (₼)')),
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Tarix'),
          subtitle: Text(formatDate(_date)),
          trailing: const Icon(Icons.calendar_today_outlined),
          onTap: () async {
            final p = await showDatePicker(
                context: context, initialDate: _date, firstDate: DateTime(2020), lastDate: DateTime.now(), locale: const Locale('az'));
            if (p != null) setState(() => _date = p);
          },
        ),
        Wrap(spacing: 6, children: [
          for (final m in const ['CASH', 'CARD', 'TRANSFER', 'OTHER'])
            ChoiceChip(label: Text(methodLabels[m] ?? m), selected: _method == m, onSelected: (_) => setState(() => _method = m)),
        ]),
        TextField(controller: _payee, maxLength: 150, decoration: const InputDecoration(labelText: 'Ödəniş edilən şəxs / şirkət')),
        TextField(controller: _note, maxLength: 1000, decoration: const InputDecoration(labelText: 'Qeyd')),
        Row(children: [
          OutlinedButton.icon(
            onPressed: () async {
              final p = await ImagePicker().pickImage(source: ImageSource.camera, maxWidth: 1600, imageQuality: 85);
              if (p != null) setState(() => _photo = p);
            },
            icon: const Icon(Icons.photo_camera_outlined),
            label: const Text('Qəbz şəkli'),
          ),
          const SizedBox(width: 8),
          TextButton(
            onPressed: () async {
              final p = await ImagePicker().pickImage(source: ImageSource.gallery, maxWidth: 1600, imageQuality: 85);
              if (p != null) setState(() => _photo = p);
            },
            child: const Text('Qalereyadan'),
          ),
          if (_photo != null) const Icon(Icons.check_circle, color: Colors.green),
        ]),
        const SizedBox(height: 12),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: gold, foregroundColor: navy),
          onPressed: _busy ? null : _save,
          child: _busy ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Text('Saxla'),
        ),
      ]);
}

class _RecurringSheet extends StatefulWidget {
  const _RecurringSheet({required this.session, required this.categories});

  final Session session;
  final List<Map<String, dynamic>> categories;

  @override
  State<_RecurringSheet> createState() => _RecurringSheetState();
}

class _RecurringSheetState extends State<_RecurringSheet> {
  final _title = TextEditingController();
  final _amount = TextEditingController();
  String? _category;
  String _freq = 'MONTHLY';
  DateTime _start = DateTime.now();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _title.dispose();
    _amount.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final v = double.tryParse(_amount.text.trim().replaceAll(',', '.'));
    if (_title.text.trim().isEmpty || _category == null || v == null || v <= 0) {
      setState(() => _error = 'Ad, kateqoriya və məbləği daxil edin');
      return;
    }
    setState(() => _busy = true);
    try {
      await widget.session.api.post('/api/recurring-expenses', {
        'title': _title.text.trim(),
        'categoryId': _category,
        'amount': v.toStringAsFixed(2),
        'frequency': _freq,
        'startDate': _iso(_start),
      });
      if (mounted) Navigator.pop(context, 'Təkrarlanan xərc əlavə edildi');
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
        const Text('Təkrarlanan xərc', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: navy)),
        if (_error != null) ErrorBanner(_error!),
        TextField(controller: _title, decoration: const InputDecoration(labelText: 'Ad', hintText: 'Məs: İcarə')),
        DropdownButtonFormField<String>(
          initialValue: _category,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Kateqoriya'),
          items: [for (final c in widget.categories) DropdownMenuItem(value: c['id'] as String, child: Text('${c['name']}'))],
          onChanged: (v) => setState(() => _category = v),
        ),
        TextField(
            controller: _amount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: 'Məbləğ (₼)')),
        Wrap(spacing: 6, children: [
          for (final e in _freqLabels.entries)
            ChoiceChip(label: Text(e.value), selected: _freq == e.key, onSelected: (_) => setState(() => _freq = e.key)),
        ]),
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('İlk ödəniş günü'),
          subtitle: Text(formatDate(_start)),
          trailing: const Icon(Icons.event_outlined),
          onTap: () async {
            final p = await showDatePicker(context: context, initialDate: _start, firstDate: DateTime(2020), lastDate: DateTime(2100), locale: const Locale('az'));
            if (p != null) setState(() => _start = p);
          },
        ),
        FilledButton(onPressed: _busy ? null : _save, child: const Text('Saxla')),
      ]);
}
