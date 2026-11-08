import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../whatsapp.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

const _sectionPermission = {
  'CUSTOMER_DEBT': 'CUSTOMER_DEBTS',
  'COMPANY_RECEIVABLE': 'COMPANY_RECEIVABLES',
  'COMPANY_PAYABLE': 'COMPANY_PAYABLES',
};

/// Entry point of the "Borclar" tab: the three independent sections, filtered by permission.
class DebtSectionsScreen extends StatelessWidget {
  const DebtSectionsScreen({super.key, required this.session});

  final Session session;

  @override
  Widget build(BuildContext context) {
    final me = session.me!;
    final sections = _sectionPermission.entries.where((e) => me.can(e.value)).toList();
    return Scaffold(
      appBar: AppBar(title: const Text('Borclar')),
      body: sections.isEmpty
          ? const EmptyState(title: 'Bu modullar üçün icazəniz yoxdur', text: 'Mağaza sahibindən icazə tələb edin.')
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                for (final s in sections)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Card(
                      child: ListTile(
                        contentPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
                        title: Text(debtSectionTitles[s.key]!, style: const TextStyle(fontWeight: FontWeight.w700, color: navy)),
                        subtitle: Text(s.key == 'CUSTOMER_DEBT' ? 'Müştərilərin borcları' : 'Şirkətlərlə hesablaşma'),
                        trailing: const Icon(Icons.chevron_right),
                        onTap: () => Navigator.of(context).push(MaterialPageRoute(
                          builder: (_) => DebtListScreen(session: session, debtType: s.key),
                        )),
                      ),
                    ),
                  ),
              ],
            ),
    );
  }
}

class DebtListScreen extends StatefulWidget {
  const DebtListScreen({super.key, required this.session, required this.debtType});

  final Session session;
  final String debtType;

  @override
  State<DebtListScreen> createState() => _DebtListScreenState();
}

class _DebtListScreenState extends State<DebtListScreen> {
  final _search = TextEditingController();
  String _status = '';
  String _query = '';
  late Future<DebtPage> _page;

  @override
  void initState() {
    super.initState();
    _page = _fetch();
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<DebtPage> _fetch() {
    final query = <String, String>{'type': widget.debtType, 'size': '100'};
    if (_status.isNotEmpty) query['status'] = _status;
    if (_query.trim().isNotEmpty) query['q'] = _query.trim();
    return widget.session.api.get('/api/debts', query: query).then((j) => DebtPage.fromJson(j as Map<String, dynamic>));
  }

  void _reload() => setState(() => _page = _fetch());

  Future<void> _addDebt() async {
    final created = await Navigator.of(context).push<bool>(MaterialPageRoute(
      builder: (_) => AddDebtScreen(session: widget.session, debtType: widget.debtType),
    ));
    if (created == true) _reload();
  }

  @override
  Widget build(BuildContext context) {
    final canWrite = widget.session.me!.can(_sectionPermission[widget.debtType]!);
    return Scaffold(
      appBar: AppBar(title: Text(debtSectionTitles[widget.debtType]!)),
      floatingActionButton: canWrite
          ? FloatingActionButton.extended(
              backgroundColor: gold,
              foregroundColor: navy,
              onPressed: _addDebt,
              icon: const Icon(Icons.add),
              label: const Text('Yeni borc'),
            )
          : null,
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
            child: TextField(
              controller: _search,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                hintText: 'Ad, telefon və ya təsvir üzrə axtar',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: _query.isEmpty
                    ? null
                    : IconButton(icon: const Icon(Icons.clear), onPressed: () {
                        _search.clear();
                        _query = '';
                        _reload();
                      }),
              ),
              onSubmitted: (v) {
                _query = v;
                _reload();
              },
            ),
          ),
          SizedBox(
            height: 44,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              children: [
                for (final f in const [
                  ('', 'Hamısı'),
                  ('UNPAID', 'Ödənilməmiş'),
                  ('OPEN', 'Açıq'),
                  ('PARTIAL', 'Qismən'),
                  ('PAID', 'Ödənilib'),
                ])
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: ChoiceChip(
                      label: Text(f.$2),
                      selected: _status == f.$1,
                      selectedColor: gold.withValues(alpha: 0.3),
                      onSelected: (_) {
                        _status = f.$1;
                        _reload();
                      },
                    ),
                  ),
              ],
            ),
          ),
          Expanded(
            child: FutureBuilder<DebtPage>(
              future: _page,
              builder: (context, snap) {
                if (snap.hasError) return Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!)));
                if (!snap.hasData) return const Center(child: CircularProgressIndicator());
                final page = snap.data!;
                return RefreshIndicator(
                  onRefresh: () async => _reload(),
                  child: ListView(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 96),
                    children: [
                      Row(children: [
                        Expanded(child: StatTile(label: 'Qalıq', value: formatMoney(page.totals.remaining), hint: '${page.total} qeyd')),
                        const SizedBox(width: 12),
                        Expanded(child: StatTile(label: 'Ödənilib', value: formatMoney(page.totals.paid))),
                      ]),
                      const SizedBox(height: 16),
                      if (page.items.isEmpty)
                        const EmptyState(title: 'Qeyd tapılmadı', text: 'Filtri dəyişin və ya yeni borc əlavə edin.')
                      else
                        for (final d in page.items)
                          Padding(
                            padding: const EdgeInsets.only(bottom: 10),
                            child: Card(
                              child: ListTile(
                                contentPadding: const EdgeInsets.fromLTRB(16, 8, 12, 8),
                                title: Text(d.partyName, style: const TextStyle(fontWeight: FontWeight.w700)),
                                subtitle: Text('${formatDate(d.debtDate)}${d.description == null ? '' : ' · ${d.description}'}'),
                                trailing: Column(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  crossAxisAlignment: CrossAxisAlignment.end,
                                  children: [
                                    Text(formatMoney(d.remainingAmount), style: const TextStyle(fontWeight: FontWeight.w700, color: navy)),
                                    const SizedBox(height: 4),
                                    StatusChip(d.status),
                                  ],
                                ),
                                onTap: () async {
                                  final changed = await Navigator.of(context).push<bool>(MaterialPageRoute(
                                    builder: (_) => DebtDetailScreen(session: widget.session, debt: d),
                                  ));
                                  if (changed == true) _reload();
                                },
                              ),
                            ),
                          ),
                    ],
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class DebtDetailScreen extends StatefulWidget {
  const DebtDetailScreen({super.key, required this.session, required this.debt});

  final Session session;
  final Debt debt;

  @override
  State<DebtDetailScreen> createState() => _DebtDetailScreenState();
}

class _DebtDetailScreenState extends State<DebtDetailScreen> {
  late Debt _debt = widget.debt;
  bool _changed = false;
  late Future<List<Map<String, dynamic>>> _history = _loadHistory();

  Future<List<Map<String, dynamic>>> _loadHistory() async {
    final j = await widget.session.api.get('/api/debts/${_debt.id}/history') as List;
    return j.cast<Map<String, dynamic>>();
  }

  bool get _canNotify => _debt.debtType != 'COMPANY_PAYABLE';

  Future<void> _openWhatsApp(Uri uri) async {
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication) && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('WhatsApp açıla bilmədi')));
    }
  }

  Future<void> _pay() async {
    final payment = await showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => PaymentSheet(session: widget.session, debt: _debt),
    );
    if (payment != null) {
      final fresh = await widget.session.api.get('/api/debts/${_debt.id}') as Map<String, dynamic>;
      if (!mounted) return;
      setState(() {
        _debt = Debt.fromJson(fresh);
        _changed = true;
        _history = _loadHistory();
      });
      final paid = (payment['amount'] as num?)?.toDouble() ?? 0;
      final paymentId = payment['id'] as String?;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Ödəniş qeydə alındı: ${formatMoney(paid)}')));
      if (paymentId != null && _canNotify) _offerReceipt(paymentId, paid);
    }
  }

  /// After a payment: ask whether to send the receipt (link via WhatsApp, or the PDF file itself).
  Future<void> _offerReceipt(String paymentId, double paid) async {
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text('Ödəniş çeki — ${formatMoney(paid)}',
                style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: navy)),
            const SizedBox(height: 4),
            Text(_debt.partyName, style: TextStyle(color: Colors.grey.shade700)),
            const SizedBox(height: 16),
            _receiptActions(ctx, paymentId, paid),
          ]),
        ),
      ),
    );
  }

  Widget _receiptActions(BuildContext ctx, String paymentId, double paid) {
    final hasWhatsApp = waNumber(_debt.partyPhone) != null;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (hasWhatsApp)
        FilledButton.icon(
          style: FilledButton.styleFrom(backgroundColor: Colors.green.shade600),
          icon: const Icon(Icons.chat),
          label: const Text('Çek linkini WhatsApp-a göndər'),
          onPressed: () async {
            Navigator.of(ctx).pop();
            await _sendReceiptLink(paymentId, paid);
          },
        ),
      const SizedBox(height: 8),
      OutlinedButton.icon(
        icon: const Icon(Icons.picture_as_pdf_outlined),
        label: const Text('PDF faylı paylaş (WhatsApp və s.)'),
        onPressed: () async {
          Navigator.of(ctx).pop();
          await _shareReceiptPdf(paymentId, paid);
        },
      ),
      if (!hasWhatsApp)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text('Müştərinin telefon nömrəsi yoxdur — PDF-i paylaşıb WhatsApp-da kontaktı özünüz seçin.',
              style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
        ),
    ]);
  }

  Future<void> _sendReceiptLink(String paymentId, double paid) async {
    try {
      final link = (await widget.session.api.get('/api/payments/$paymentId/share-link') as Map<String, dynamic>)['url'] as String;
      final uri = waUri(_debt.partyPhone, receiptMessageText(widget.session.me?.storeName ?? '', _debt.partyName, paid, link));
      if (uri != null) await _openWhatsApp(uri);
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  /// Shares the PDF itself through the system share sheet; the user picks WhatsApp and the chat.
  Future<void> _shareReceiptPdf(String paymentId, double paid) async {
    try {
      final bytes = await widget.session.api.getBytes('/api/payments/$paymentId/receipt');
      final name = 'cek-${paymentId.substring(0, 8)}.pdf';
      await SharePlus.instance.share(ShareParams(
        files: [XFile.fromData(Uint8List.fromList(bytes), mimeType: 'application/pdf', name: name)],
        fileNameOverrides: [name],
        text: 'Ödəniş çeki — ${_debt.partyName}, ${formatMoney(paid)}',
      ));
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  static const _labels = {
    'DEBT_CREATED': 'Borc yaradıldı',
    'DEBT_ADJUSTED': 'Məbləğ dəyişdirildi',
    'PAYMENT_CASH': 'Nağd ödəniş',
    'PAYMENT_CARD': 'Kart ödənişi',
    'PAYMENT_TRANSFER': 'Köçürmə ilə ödəniş',
    'PAYMENT_OTHER': 'Digər ödəniş',
  };

  @override
  Widget build(BuildContext context) {
    final canPay = !_debt.isPaid && widget.session.me!.can(_sectionPermission[_debt.debtType]!);
    return Scaffold(
        appBar: AppBar(
          title: Text(_debt.partyName),
          leading: BackButton(onPressed: () => Navigator.of(context).pop(_changed)),
          actions: [
            if (_canNotify && !_debt.isPaid && waNumber(_debt.partyPhone) != null)
              IconButton(
                tooltip: 'WhatsApp ilə borc xatırlatması',
                icon: const Icon(Icons.chat_outlined, color: Colors.green),
                onPressed: () => _openWhatsApp(waUri(
                    _debt.partyPhone,
                    debtReminderText(widget.session.me?.storeName ?? '', _debt.partyName, _debt.remainingAmount,
                        _debt.dueDate))!),
              ),
          ],
        ),
        bottomNavigationBar: canPay
            ? SafeArea(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: FilledButton.icon(
                    style: FilledButton.styleFrom(backgroundColor: gold, foregroundColor: navy),
                    onPressed: _pay,
                    icon: const Icon(Icons.payments_outlined),
                    label: const Text('Ödəniş qəbul et'),
                  ),
                ),
              )
            : null,
        body: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Row(children: [
              Expanded(child: StatTile(label: 'Məbləğ', value: formatMoney(_debt.amount))),
              const SizedBox(width: 12),
              Expanded(child: StatTile(label: 'Qalıq', value: formatMoney(_debt.remainingAmount), hint: 'Ödənilib: ${formatMoney(_debt.paidAmount)}')),
            ]),
            const SizedBox(height: 16),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [StatusChip(_debt.status), const Spacer(), Text(debtSectionTitles[_debt.debtType]!, style: TextStyle(color: Colors.grey.shade600, fontSize: 12))]),
                  const SizedBox(height: 12),
                  _line('Borc tarixi', formatDate(_debt.debtDate)),
                  _line('Son ödəmə', formatDate(_debt.dueDate)),
                  _line('Telefon', _debt.partyPhone ?? '—'),
                  if (_debt.description != null) _line('Təsvir', _debt.description!),
                ]),
              ),
            ),
            const SizedBox(height: 20),
            const Text('Tarixçə', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: navy)),
            const SizedBox(height: 8),
            FutureBuilder<List<Map<String, dynamic>>>(
              future: _history,
              builder: (context, snap) {
                if (snap.hasError) return ErrorBanner(errorMessage(snap.error!));
                if (!snap.hasData) return const Center(child: CircularProgressIndicator());
                return Column(children: [
                  for (final e in snap.data!)
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      onTap: e['paymentId'] == null || !_canNotify
                          ? null
                          : () => _offerReceipt(e['paymentId'] as String, (e['amount'] as num).toDouble()),
                      leading: Icon(
                        (e['type'] as String).startsWith('PAYMENT') ? Icons.south_west : Icons.receipt_long_outlined,
                        color: (e['type'] as String).startsWith('PAYMENT') ? Colors.green : navy,
                      ),
                      title: Text(_labels[e['type']] ?? '${e['type']}'),
                      subtitle: Text('${formatDateTime(DateTime.parse(e['createdAt'] as String))} · ${e['actorName']}'),
                      trailing: Text(
                        '${formatMoney((e['amount'] as num).toDouble())}\nqalıq ${formatMoney((e['balanceAfter'] as num).toDouble())}',
                        textAlign: TextAlign.right,
                        style: const TextStyle(fontSize: 12),
                      ),
                    ),
                ]);
              },
            ),
          ],
        ),
    );
  }

  Widget _line(String k, String v) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(children: [
          SizedBox(width: 110, child: Text(k, style: TextStyle(color: Colors.grey.shade600))),
          Expanded(child: Text(v, style: const TextStyle(fontWeight: FontWeight.w600))),
        ]),
      );
}

/// Records a cash, card, transfer or other payment. The server rejects amounts above the remaining balance.
class PaymentSheet extends StatefulWidget {
  const PaymentSheet({super.key, required this.session, required this.debt});

  final Session session;
  final Debt debt;

  @override
  State<PaymentSheet> createState() => _PaymentSheetState();
}

class _PaymentSheetState extends State<PaymentSheet> {
  final _amount = TextEditingController();
  final _description = TextEditingController();
  String _method = 'CASH';
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _amount.text = widget.debt.remainingAmount.toStringAsFixed(2);
  }

  @override
  void dispose() {
    _amount.dispose();
    _description.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final value = double.tryParse(_amount.text.replaceAll(',', '.'));
    if (value == null || value <= 0) {
      setState(() => _error = 'Məbləği düzgün daxil edin');
      return;
    }
    if (value > widget.debt.remainingAmount + 0.001) {
      setState(() => _error = 'Məbləğ qalıq borcdan (${formatMoney(widget.debt.remainingAmount)}) çox ola bilməz');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final res = await widget.session.api.post('/api/debts/${widget.debt.id}/payments', {
        'amount': value.toStringAsFixed(2),
        'paymentMethod': _method,
        if (_description.text.trim().isNotEmpty) 'description': _description.text.trim(),
      }) as Map<String, dynamic>;
      if (mounted) Navigator.of(context).pop(res);
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.fromLTRB(20, 0, 20, MediaQuery.viewInsetsOf(context).bottom + 20),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text('Ödəniş — ${widget.debt.partyName}', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: navy)),
        const SizedBox(height: 4),
        Text('Qalıq: ${formatMoney(widget.debt.remainingAmount)}', style: TextStyle(color: Colors.grey.shade700)),
        const SizedBox(height: 16),
        if (_error != null) ErrorBanner(_error!),
        TextField(
          controller: _amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Məbləğ (₼)'),
        ),
        const SizedBox(height: 12),
        Wrap(spacing: 8, runSpacing: 8, children: [
          for (final m in const [
            ('CASH', 'Nağd', Icons.money),
            ('CARD', 'Kart', Icons.credit_card),
            ('TRANSFER', 'Köçürmə', Icons.swap_horiz),
            ('OTHER', 'Digər', Icons.more_horiz),
          ])
            ChoiceChip(
              avatar: Icon(m.$3, size: 18),
              label: Text(m.$2),
              selected: _method == m.$1,
              onSelected: (_) => setState(() => _method = m.$1),
            ),
        ]),
        const SizedBox(height: 12),
        TextField(controller: _description, decoration: const InputDecoration(labelText: 'Təsvir (istəyə bağlı)')),
        const SizedBox(height: 20),
        FilledButton(
          onPressed: _busy ? null : _submit,
          child: _busy
              ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : const Text('Ödənişi qeydə al'),
        ),
      ]),
    );
  }
}

class AddDebtScreen extends StatefulWidget {
  const AddDebtScreen({super.key, required this.session, required this.debtType});

  final Session session;
  final String debtType;

  @override
  State<AddDebtScreen> createState() => _AddDebtScreenState();
}

class _AddDebtScreenState extends State<AddDebtScreen> {
  final _formKey = GlobalKey<FormState>();
  final _amount = TextEditingController();
  final _description = TextEditingController();
  late Future<List<Party>> _parties;
  String? _partyId;
  DateTime _debtDate = DateTime.now();
  DateTime? _dueDate;
  bool _busy = false;
  String? _error;

  bool get _isCustomer => widget.debtType == 'CUSTOMER_DEBT';

  @override
  void initState() {
    super.initState();
    _parties = widget.session.api
        .get(_isCustomer ? '/api/customers' : '/api/companies')
        .then((j) => (j as List).map((e) => Party.fromJson(e as Map<String, dynamic>)).toList());
  }

  @override
  void dispose() {
    _amount.dispose();
    _description.dispose();
    super.dispose();
  }

  Future<void> _pickDate(bool due) async {
    final picked = await showDatePicker(
      context: context,
      initialDate: due ? (_dueDate ?? _debtDate) : _debtDate,
      firstDate: DateTime(2020),
      lastDate: DateTime(2100),
      locale: const Locale('az'),
    );
    if (picked != null) setState(() => due ? _dueDate = picked : _debtDate = picked);
  }

  String _iso(DateTime d) => '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  Future<void> _save() async {
    if (!(_formKey.currentState?.validate() ?? false) || _partyId == null) {
      setState(() => _error = 'Tərəfi və məbləği daxil edin');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.session.api.post('/api/debts', {
        'debtType': widget.debtType,
        'partyId': _partyId,
        'amount': double.parse(_amount.text.replaceAll(',', '.')).toStringAsFixed(2),
        'debtDate': _iso(_debtDate),
        if (_dueDate != null) 'dueDate': _iso(_dueDate!),
        if (_description.text.trim().isNotEmpty) 'description': _description.text.trim(),
      });
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_isCustomer ? 'Yeni müştəri borcu' : 'Yeni şirkət borcu')),
      body: FutureBuilder<List<Party>>(
        future: _parties,
        builder: (context, snap) {
          final parties = snap.data ?? const <Party>[];
          return Form(
            key: _formKey,
            child: ListView(padding: const EdgeInsets.all(16), children: [
              if (_error != null) ErrorBanner(_error!),
              DropdownButtonFormField<String>(
                initialValue: _partyId,
                decoration: InputDecoration(labelText: _isCustomer ? 'Müştəri' : 'Şirkət'),
                items: [for (final p in parties) DropdownMenuItem(value: p.id, child: Text(p.name))],
                onChanged: (v) => setState(() => _partyId = v),
                validator: (v) => v == null ? 'Seçin' : null,
              ),
              if (snap.connectionState == ConnectionState.done && parties.isEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text('Əvvəlcə ${_isCustomer ? 'müştəri' : 'şirkət'} əlavə edin (veb paneldə).', style: TextStyle(color: Colors.grey.shade700)),
                ),
              const SizedBox(height: 14),
              TextFormField(
                controller: _amount,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(labelText: 'Məbləğ (₼)'),
                validator: (v) {
                  final n = double.tryParse((v ?? '').replaceAll(',', '.'));
                  return (n == null || n <= 0) ? 'Düzgün məbləğ daxil edin' : null;
                },
              ),
              const SizedBox(height: 14),
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Borc tarixi'),
                subtitle: Text(formatDate(_debtDate)),
                trailing: const Icon(Icons.calendar_today_outlined),
                onTap: () => _pickDate(false),
              ),
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Son ödəmə tarixi (istəyə bağlı)'),
                subtitle: Text(formatDate(_dueDate)),
                trailing: const Icon(Icons.event_outlined),
                onTap: () => _pickDate(true),
              ),
              const SizedBox(height: 6),
              TextFormField(controller: _description, decoration: const InputDecoration(labelText: 'Təsvir')),
              const SizedBox(height: 24),
              FilledButton(
                onPressed: _busy ? null : _save,
                child: _busy
                    ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                    : const Text('Borcu yadda saxla'),
              ),
            ]),
          );
        },
      ),
    );
  }
}
