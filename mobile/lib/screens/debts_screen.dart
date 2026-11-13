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
import 'debt_edit_sheet.dart';
import 'debt_increase_sheet.dart';
import 'payment_edit_sheet.dart';
import 'party_profile_screen.dart';
import 'parties_screen.dart';

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
                                    Text(formatMoney(d.remainingAmount),
                                        style: TextStyle(fontWeight: FontWeight.w700, color: d.isPaid ? Colors.green.shade700 : Colors.red.shade700)),
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

  /// Ledger entry of a payment that is still in effect: no REVERSAL of the same payment follows it.
  static bool _isLivePayment(List<Map<String, dynamic>> entries, int i) {
    final e = entries[i];
    if (e['paymentId'] == null || !(e['type'] as String).startsWith('PAYMENT')) return false;
    return !entries.skip(i + 1).any((x) => x['type'] == 'REVERSAL' && x['paymentId'] == e['paymentId']);
  }

  /// Owner: cancel a "Borc artırıldı" entry recorded by mistake; the debt goes back to what it was before it.
  Future<void> _cancelIncrease(Map<String, dynamic> e) async {
    final amount = (e['amount'] as num).toDouble();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Borc artımını ləğv et?'),
        content: Text('${formatMoney(amount)} artım ləğv ediləcək, borc bu məbləğ qədər azalacaq. '
            'Əməliyyat tarixçədə “ləğv edilib” kimi qalır.'),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('Bağla')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red.shade700),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Ləğv et'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      final res = await widget.session.api.post('/api/debts/${_debt.id}/history/${e['id']}/cancel', <String, dynamic>{});
      if (!mounted) return;
      setState(() {
        _debt = Debt.fromJson(res as Map<String, dynamic>);
        _changed = true;
        _history = _loadHistory();
      });
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Borc artımı ləğv edildi')));
    } catch (err) {
      if (mounted) showError(context, err);
    }
  }

  /// Owner: tap on a payment in the history → receipt, edit or cancel.
  Future<void> _paymentTapped(String paymentId, double amount) async {
    final choice = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          if (_canNotify)
            ListTile(
              leading: const Icon(Icons.receipt_long_outlined),
              title: const Text('Çeki göndər'),
              onTap: () => Navigator.of(ctx).pop('receipt'),
            ),
          ListTile(
            leading: const Icon(Icons.edit_outlined),
            title: const Text('Düzəlt və ya ləğv et'),
            onTap: () => Navigator.of(ctx).pop('change'),
          ),
        ]),
      ),
    );
    if (choice == null || !mounted) return;
    if (choice == 'receipt') {
      _offerReceipt(paymentId, amount);
      return;
    }
    try {
      final list = await widget.session.api.get('/api/payments',
          query: {'debtId': _debt.id, 'limit': '500', 'includeVoided': 'true'}) as List;
      final payment = list
          .map((e) => Payment.fromJson(e as Map<String, dynamic>))
          .firstWhere((p) => p.id == paymentId);
      if (!mounted) return;
      final changed = await showPaymentActions(context, widget.session, payment,
          maxAmount: _debt.remainingAmount + payment.amount);
      if (changed == null || !mounted) return;
      final fresh = await widget.session.api.get('/api/debts/${_debt.id}') as Map<String, dynamic>;
      if (!mounted) return;
      setState(() {
        _debt = Debt.fromJson(fresh);
        _changed = true;
        _history = _loadHistory();
      });
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(changed.isVoided ? 'Ödəniş ləğv edildi' : 'Ödəniş düzəldildi')));
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _openWhatsApp(Uri uri) async {
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication) && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('WhatsApp açıla bilmədi')));
    }
  }

  Future<void> _increase() async {
    final updated = await openDebtIncrease(context, session: widget.session, debt: _debt);
    if (updated == null || !mounted) return;
    setState(() {
      _debt = updated;
      _changed = true;
      _history = _loadHistory();
    });
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text('Borc artırıldı. Yeni qalıq: ${formatMoney(updated.remainingAmount)}')));
  }

  Future<void> _openProfile() async {
    await Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => PartyProfileScreen(
            session: widget.session, isCustomer: _debt.debtType == 'CUSTOMER_DEBT', partyId: _debt.partyId)));
    // the profile may have changed this debt (payment / increase): reload it
    try {
      final fresh = await widget.session.api.get('/api/debts/${_debt.id}') as Map<String, dynamic>;
      if (!mounted) return;
      setState(() {
        _debt = Debt.fromJson(fresh);
        _changed = true;
        _history = _loadHistory();
      });
    } catch (_) {}
  }

  Future<void> _edit() async {
    final updated = await showModalBottomSheet<Debt>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
        child: EditDebtSheet(session: widget.session, debt: _debt),
      ),
    );
    if (updated == null || !mounted) return;
    setState(() {
      _debt = updated;
      _changed = true;
      _history = _loadHistory();
    });
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Borc yeniləndi')));
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
    'DEBT_INCREASED': 'Borc artırıldı',
    'PAYMENT_CASH': 'Nağd ödəniş',
    'PAYMENT_CARD': 'Kart ödənişi',
    'PAYMENT_TRANSFER': 'Köçürmə ilə ödəniş',
    'PAYMENT_OTHER': 'Digər ödəniş',
    'REVERSAL': 'Ödəniş ləğvi',
  };

  @override
  Widget build(BuildContext context) {
    final canWrite = widget.session.me!.can(_sectionPermission[_debt.debtType]!);
    final canPay = !_debt.isPaid && canWrite;
    return Scaffold(
        appBar: AppBar(
          title: Text(_debt.partyName),
          leading: BackButton(onPressed: () => Navigator.of(context).pop(_changed)),
          actions: [
            if (widget.session.me!.can(_sectionPermission[_debt.debtType]!))
              IconButton(tooltip: 'Redaktə et', icon: const Icon(Icons.edit_outlined), onPressed: _edit),
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
        bottomNavigationBar: canWrite
            ? SafeArea(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                  child: Row(children: [
                    Expanded(
                      child: OutlinedButton.icon(
                        onPressed: _increase,
                        icon: const Icon(Icons.add),
                        label: const FittedBox(child: Text('Borc artır')),
                      ),
                    ),
                    if (canPay) ...[
                      const SizedBox(width: 10),
                      Expanded(
                        child: FilledButton.icon(
                          style: FilledButton.styleFrom(backgroundColor: gold, foregroundColor: navy),
                          onPressed: _pay,
                          icon: const Icon(Icons.payments_outlined),
                          label: const FittedBox(child: Text('Ödəniş qəbul et')),
                        ),
                      ),
                    ],
                  ]),
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
                  const SizedBox(height: 4),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton.icon(
                      onPressed: _openProfile,
                      icon: const Icon(Icons.person_outline, size: 18),
                      label: Text(_debt.debtType == 'CUSTOMER_DEBT' ? 'Müştərinin bütün borcları' : 'Şirkətin bütün borcları'),
                    ),
                  ),
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
                final entries = snap.data!;
                final owner = widget.session.me!.isOwner;
                return Column(children: [
                  for (final (i, e) in entries.indexed) _historyTile(entries, i, e, owner),
                ]);
              },
            ),
          ],
        ),
    );
  }

  /// One history row. The owner gets a visible ⋮ for a live payment (edit / cancel) or an uncancelled "Borc artırıldı".
  Widget _historyTile(List<Map<String, dynamic>> entries, int i, Map<String, dynamic> e, bool owner) {
    final VoidCallback? ownerAction = !owner
        ? null
        : e['type'] == 'DEBT_INCREASED' && e['cancelled'] != true
            ? () => _cancelIncrease(e)
            : e['paymentId'] != null && e['type'] != 'REVERSAL' && _isLivePayment(entries, i)
                ? () => _paymentTapped(e['paymentId'] as String, (e['amount'] as num).toDouble())
                : null;
    return ListTile(
      contentPadding: EdgeInsets.zero,
      onTap: owner && e['type'] == 'DEBT_INCREASED' && e['cancelled'] != true
          ? () => _cancelIncrease(e)
          : e['paymentId'] == null || e['type'] == 'REVERSAL'
          ? null
          : owner && _isLivePayment(entries, i)
              ? () => _paymentTapped(e['paymentId'] as String, (e['amount'] as num).toDouble())
              : !_canNotify
                  ? null
                  : () => _offerReceipt(e['paymentId'] as String, (e['amount'] as num).toDouble()),
      leading: Icon(
        (e['type'] as String).startsWith('PAYMENT') ? Icons.south_west : Icons.receipt_long_outlined,
        color: (e['type'] as String).startsWith('PAYMENT') ? Colors.green.shade700 : Colors.red.shade700,
      ),
      title: Text([
        e['reversesEntryId'] != null ? 'Borc artımı ləğv edildi' : _labels[e['type']] ?? '${e['type']}',
        if (e['cancelled'] == true) '(ləğv edilib)',
      ].join(' ')),
      subtitle: Text([
        '${formatDateTime(DateTime.parse(e['createdAt'] as String))} · ${e['actorName']}',
        if (e['entryDate'] != null) 'Tarix: ${formatDate(DateTime.parse(e['entryDate'] as String))}',
        if (e['receiptNumber'] != null) 'Çek: ${e['receiptNumber']}',
        if (e['note'] != null) '${e['note']}',
      ].join('\n')),
      trailing: Row(mainAxisSize: MainAxisSize.min, children: [
        Text(
        '${e['type'] == 'DEBT_INCREASED' ? '+' : ''}${formatMoney((e['amount'] as num).toDouble())}\nqalıq ${formatMoney((e['balanceAfter'] as num).toDouble())}',
        textAlign: TextAlign.right,
        style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w600,
            color: (e['type'] as String).startsWith('PAYMENT') ? Colors.green.shade700 : Colors.red.shade700),
        ),
        if (ownerAction != null)
          IconButton(tooltip: 'Düzəlt / ləğv et', icon: const Icon(Icons.more_vert), onPressed: ownerAction),
      ]),
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
  const AddDebtScreen({super.key, required this.session, required this.debtType, this.initialPartyId});

  final Session session;
  final String debtType;

  /// Opened from a customer / company profile: that party is already selected.
  final String? initialPartyId;

  @override
  State<AddDebtScreen> createState() => _AddDebtScreenState();
}

class _AddDebtScreenState extends State<AddDebtScreen> {
  final _formKey = GlobalKey<FormState>();
  final _amount = TextEditingController();
  final _description = TextEditingController();
  late Future<List<Party>> _parties;
  late String? _partyId = widget.initialPartyId;
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

  Future<void> _addParty(List<Party> current) async {
    final created = await openPartyForm(context, session: widget.session, isCustomer: _isCustomer);
    if (created == null || !mounted) return;
    setState(() {
      _parties = Future.value([created, ...current.where((p) => p.id != created.id)]);
      _partyId = created.id;
    });
  }

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
      // Adds to the party's account: an existing balance grows instead of a separate new debt appearing.
      await widget.session.api.post('/api/${_isCustomer ? 'customers' : 'companies'}/$_partyId/charge', {
        'debtType': widget.debtType,
        'amount': double.parse(_amount.text.replaceAll(',', '.')).toStringAsFixed(2),
        'date': _iso(_debtDate),
        if (_description.text.trim().isNotEmpty) 'note': _description.text.trim(),
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
      appBar: AppBar(title: const Text('Borc əlavə et')),
      body: FutureBuilder<List<Party>>(
        future: _parties,
        builder: (context, snap) {
          final parties = snap.data ?? const <Party>[];
          return Form(
            key: _formKey,
            child: ListView(padding: const EdgeInsets.all(16), children: [
              if (_error != null) ErrorBanner(_error!),
              DropdownButtonFormField<String>(
                key: ValueKey('party-${parties.length}-$_partyId'),
                initialValue: _partyId,
                isExpanded: true,
                decoration: InputDecoration(labelText: _isCustomer ? 'Müştəri' : 'Şirkət'),
                items: [for (final p in parties) DropdownMenuItem(value: p.id, child: Text(p.name, overflow: TextOverflow.ellipsis))],
                onChanged: (v) => setState(() => _partyId = v),
                validator: (v) => v == null ? 'Seçin' : null,
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: _busy ? null : () => _addParty(parties),
                  icon: const Icon(Icons.person_add_alt_1_outlined, size: 18),
                  label: Text('Yeni ${_isCustomer ? 'müştəri' : 'şirkət'} əlavə et'),
                ),
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
              const SizedBox(height: 6),
              TextFormField(
                controller: _description,
                maxLength: 255,
                decoration: const InputDecoration(labelText: 'Qeyd', hintText: 'Məs: 2 kisə un'),
              ),
              Text('Müştərinin artıq borcu varsa, məbləğ onun ümumi borcunun üstünə gəlir.',
                  style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
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
