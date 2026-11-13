import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import '../whatsapp.dart';
import 'account_sheet.dart';
import 'common.dart';
import 'debts_screen.dart';
import 'parties_screen.dart';
import 'payment_edit_sheet.dart';

const _sectionPermission = {
  'CUSTOMER_DEBT': 'CUSTOMER_DEBTS',
  'COMPANY_RECEIVABLE': 'COMPANY_RECEIVABLES',
  'COMPANY_PAYABLE': 'COMPANY_PAYABLES',
};

const _entryLabels = {
  'DEBT_CREATED': 'Borc yazıldı',
  'DEBT_INCREASED': 'Borc artırıldı',
  'DEBT_ADJUSTED': 'Məbləğ dəyişdirildi',
  'PAYMENT_CASH': 'Ödəniş — nağd',
  'PAYMENT_CARD': 'Ödəniş — kart',
  'PAYMENT_TRANSFER': 'Ödəniş — köçürmə',
  'PAYMENT_OTHER': 'Ödəniş — digər',
  'REVERSAL': 'Ödəniş ləğvi',
};

class _Profile {
  final Party party;
  final double totalDebt;
  final double paid;
  final double remaining;
  final List<Debt> debts;
  final List<Payment> payments;
  final List<Map<String, dynamic>> history;

  _Profile(Map<String, dynamic> j, this.history)
      : party = Party.fromJson(j['party'] as Map<String, dynamic>),
        totalDebt = ((j['totals'] as Map)['totalDebt'] as num).toDouble(),
        paid = ((j['totals'] as Map)['paid'] as num).toDouble(),
        remaining = ((j['totals'] as Map)['remaining'] as num).toDouble(),
        debts = (j['debts'] as List).map((e) => Debt.fromJson(e as Map<String, dynamic>)).toList(),
        payments = (j['payments'] as List).map((e) => Payment.fromJson(e as Map<String, dynamic>)).toList();
}

/// Premium customer / company card: code, status, quick actions, history, notes and the customer portal link.
class PartyProfileScreen extends StatefulWidget {
  const PartyProfileScreen({super.key, required this.session, required this.isCustomer, required this.partyId});

  final Session session;
  final bool isCustomer;
  final String partyId;

  @override
  State<PartyProfileScreen> createState() => _PartyProfileScreenState();
}

class _PartyProfileScreenState extends State<PartyProfileScreen> {
  late Future<_Profile> _data = _fetch();

  String get _base => '/api/${widget.isCustomer ? 'customers' : 'companies'}/${widget.partyId}';

  Future<_Profile> _fetch() async {
    final res = await Future.wait([widget.session.api.get('$_base/profile'), widget.session.api.get('$_base/history')]);
    return _Profile(res[0] as Map<String, dynamic>, (res[1] as List).cast<Map<String, dynamic>>());
  }

  void _reload() => setState(() {
        _data = _fetch();
      });

  bool _can(String debtType) => widget.session.me!.can(_sectionPermission[debtType]!);

  bool get _canWrite =>
      widget.isCustomer ? _can('CUSTOMER_DEBT') : (_can('COMPANY_RECEIVABLE') || _can('COMPANY_PAYABLE'));

  void _snack(String msg) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));

  Future<void> _account(AccountMode mode) async {
    final msg = await openAccountSheet(context,
        session: widget.session, isCustomer: widget.isCustomer, partyId: widget.partyId, mode: mode);
    if (msg == null || !mounted) return;
    _snack(msg);
    _reload();
  }

  Future<void> _logContact(String channel) async {
    if (!widget.isCustomer) return;
    try {
      await widget.session.api.post('$_base/contact', {'channel': channel});
    } catch (_) {}
  }

  Future<void> _call(Party p) async {
    final phone = (p.phone ?? '').replaceAll(RegExp(r'[^\d+]'), '');
    if (phone.isEmpty) return _snack('Telefon nömrəsi yoxdur');
    final ok = await launchUrl(Uri(scheme: 'tel', path: phone));
    if (!ok) return _snack('Zəng açıla bilmədi');
    await _logContact('CALL');
    _reload();
  }

  Future<void> _whatsapp(_Profile p) async {
    if (waNumber(p.party.phone) == null) return _snack('Telefon nömrəsi yoxdur və ya düzgün deyil');
    final opened = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
        child: _WhatsAppSheet(
          phone: p.party.phone!,
          vars: TemplateVars(
              store: widget.session.me?.storeName ?? '',
              name: p.party.name,
              total: p.totalDebt,
              paid: p.paid,
              remaining: p.remaining,
              lastPayment: p.payments.isEmpty ? null : p.payments.first.amount),
        ),
      ),
    );
    if (opened == true) {
      await _logContact('WHATSAPP');
      if (mounted) _snack('WhatsApp açıldı — mesajı WhatsApp-da özünüz göndərin.');
      _reload();
    }
  }

  Future<void> _pdf(Party p) async {
    try {
      final bytes = await widget.session.api.getBytes('$_base/statement');
      final name = 'cixaris-${p.code ?? p.name}.pdf'.replaceAll(' ', '-');
      await SharePlus.instance.share(ShareParams(
        files: [XFile.fromData(Uint8List.fromList(bytes), mimeType: 'application/pdf', name: name)],
        fileNameOverrides: [name],
        subject: 'Borc çıxarışı — ${p.name}',
      ));
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _edit(Party party) async {
    final saved = await openPartyForm(context, session: widget.session, isCustomer: widget.isCustomer, party: party);
    if (saved != null) _reload();
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<_Profile>(
      future: _data,
      builder: (context, snap) {
        final p = snap.data;
        return DefaultTabController(
          length: widget.isCustomer ? 4 : 3,
          child: Scaffold(
            appBar: AppBar(
              title: Text(p?.party.name ?? (widget.isCustomer ? 'Müştəri' : 'Şirkət')),
              bottom: p == null
                  ? null
                  : TabBar(isScrollable: true, tabs: [
                      const Tab(text: 'Tarixçə'),
                      const Tab(text: 'Borclar'),
                      const Tab(text: 'Ödənişlər'),
                      if (widget.isCustomer) const Tab(text: 'Qeyd və kabinet'),
                    ]),
            ),
            bottomNavigationBar: p != null && _canWrite
                ? SafeArea(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                      child: Row(children: [
                        Expanded(
                          child: FilledButton.icon(
                            style: FilledButton.styleFrom(
                                backgroundColor: const Color(0xFF7C3AED), padding: const EdgeInsets.symmetric(vertical: 14)),
                            onPressed: () => _account(AccountMode.charge),
                            icon: const Icon(Icons.add),
                            label: const FittedBox(child: Text('Borc əlavə et')),
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: FilledButton.icon(
                            style: FilledButton.styleFrom(
                                backgroundColor: const Color(0xFF0D9488), padding: const EdgeInsets.symmetric(vertical: 14)),
                            onPressed: () => _account(AccountMode.pay),
                            icon: const Icon(Icons.payments_outlined),
                            label: const FittedBox(child: Text('Ödəniş qəbul et')),
                          ),
                        ),
                      ]),
                    ),
                  )
                : null,
            body: snap.hasError
                ? ListView(children: [
                    Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!))),
                    Center(child: OutlinedButton(onPressed: _reload, child: const Text('Yenidən cəhd et'))),
                  ])
                : p == null
                    ? const Center(child: CircularProgressIndicator())
                    : NestedScrollView(
                        headerSliverBuilder: (_, _) => [SliverToBoxAdapter(child: _header(p))],
                        body: TabBarView(children: [
                          _historyTab(p),
                          _debtsTab(p),
                          _paymentsTab(p),
                          if (widget.isCustomer) _NotesAndPortal(session: widget.session, base: _base, party: p.party),
                        ]),
                      ),
          ),
        );
      },
    );
  }

  Widget _header(_Profile p) {
    final party = p.party;
    final overdue = party.overdueDebts > 0;
    final status = overdue ? 'Gecikmiş' : p.remaining > 0 ? 'Aktiv borc' : 'Borcsuz';
    final statusColor = overdue ? Colors.red.shade300 : p.remaining > 0 ? Colors.amber.shade300 : Colors.green.shade300;
    final initials = party.name.trim().split(RegExp(r'\s+')).where((w) => w.isNotEmpty).take(2).map((w) => w[0].toUpperCase()).join();
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
      child: Column(children: [
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(22),
            gradient: const LinearGradient(colors: [navy, Color(0xFF4C1D95)], begin: Alignment.topLeft, end: Alignment.bottomRight),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              CircleAvatar(
                  radius: 28,
                  backgroundColor: Colors.white24,
                  child: Text(initials.isEmpty ? '?' : initials,
                      style: const TextStyle(color: Colors.white, fontSize: 20, fontWeight: FontWeight.w700))),
              const SizedBox(width: 12),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(party.name, style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w800)),
                  Text([if (widget.isCustomer) party.code, party.phone].where((e) => e != null && e.isNotEmpty).join(' · '),
                      style: const TextStyle(color: Colors.white70, fontSize: 12)),
                  const SizedBox(height: 4),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    decoration: BoxDecoration(color: statusColor.withValues(alpha: 0.25), borderRadius: BorderRadius.circular(99)),
                    child: Text(status, style: TextStyle(color: statusColor, fontSize: 11, fontWeight: FontWeight.w700)),
                  ),
                ]),
              ),
            ]),
            const SizedBox(height: 14),
            Row(children: [
              for (final e in [['Ümumi borc', p.totalDebt], ['Ödənilib', p.paid], ['Qalıq', p.remaining]])
                Expanded(
                  child: Container(
                    margin: const EdgeInsets.symmetric(horizontal: 3),
                    padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 6),
                    decoration: BoxDecoration(color: Colors.white12, borderRadius: BorderRadius.circular(12)),
                    child: Column(children: [
                      Text(e[0] as String, style: const TextStyle(color: Colors.white60, fontSize: 10)),
                      FittedBox(
                          child: Text(formatMoney(e[1] as double),
                              style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w800, fontSize: 15))),
                    ]),
                  ),
                ),
            ]),
            const SizedBox(height: 10),
            Text(
              [
                if (party.createdAt != null) 'Qeydiyyat: ${formatDate(party.createdAt!.toLocal())}',
                'Son əməliyyat: ${party.lastActivityAt == null ? '—' : formatDateTime(party.lastActivityAt!)}',
                if (widget.isCustomer) 'Son əlaqə: ${party.lastContactAt == null ? '—' : formatDateTime(party.lastContactAt!)}',
              ].join('\n'),
              style: const TextStyle(color: Colors.white54, fontSize: 11),
            ),
            if (overdue)
              Container(
                margin: const EdgeInsets.only(top: 10),
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(color: Colors.red.withValues(alpha: 0.25), borderRadius: BorderRadius.circular(10)),
                child: Text('Son ödəmə tarixi keçmiş ${party.overdueDebts} borc var',
                    style: const TextStyle(color: Colors.white, fontSize: 12)),
              ),
          ]),
        ),
        const SizedBox(height: 10),
        Row(children: [
          _Quick(icon: Icons.chat, label: 'WhatsApp', color: Colors.green.shade700, onTap: () => _whatsapp(p)),
          _Quick(icon: Icons.call_outlined, label: 'Zəng et', color: Colors.blue.shade700, onTap: () => _call(party)),
          _Quick(icon: Icons.picture_as_pdf_outlined, label: 'PDF', color: Colors.red.shade700, onTap: () => _pdf(party)),
          _Quick(icon: Icons.edit_outlined, label: 'Redaktə', color: navy, onTap: () => _edit(party)),
        ]),
      ]),
    );
  }

  Widget _historyTab(_Profile p) {
    if (p.history.isEmpty) return const EmptyState(title: 'Əməliyyat yoxdur');
    final list = p.history.reversed.toList();
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 24),
      itemCount: list.length,
      itemBuilder: (_, i) {
        final e = list[i];
        final pay = (e['type'] as String).startsWith('PAYMENT');
        final amount = ((e['amount'] as num).toDouble()).abs();
        return ListTile(
          contentPadding: EdgeInsets.zero,
          leading: CircleAvatar(
            backgroundColor: pay ? Colors.green.shade50 : Colors.red.shade50,
            child: Icon(pay ? Icons.south_west : Icons.north_east, color: pay ? Colors.green.shade700 : Colors.red.shade700, size: 18),
          ),
          title: Text(_entryLabels[e['type']] ?? '${e['type']}', style: const TextStyle(fontWeight: FontWeight.w600)),
          subtitle: Text([
            if (e['entryDate'] != null) formatDate(DateTime.parse(e['entryDate'] as String)),
            if (e['receiptNumber'] != null) 'Çek: ${e['receiptNumber']}',
            if (e['note'] != null) '${e['note']}',
            '${e['actorName']}',
          ].join(' · ')),
          trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
            Text('${pay ? '−' : '+'}${formatMoney(amount)}',
                style: TextStyle(fontWeight: FontWeight.w800, color: pay ? Colors.green.shade700 : Colors.red.shade700)),
            Text('qalıq ${formatMoney((e['balanceAfter'] as num).toDouble())}', style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
          ]),
        );
      },
    );
  }

  Widget _debtsTab(_Profile p) {
    if (p.debts.isEmpty) return const EmptyState(title: 'Borc yoxdur');
    final today = DateTime.now();
    return ListView(padding: const EdgeInsets.fromLTRB(12, 8, 12, 24), children: [
      for (final d in p.debts)
        Card(
          child: ListTile(
            onTap: () async {
              await Navigator.of(context).push(MaterialPageRoute(builder: (_) => DebtDetailScreen(session: widget.session, debt: d)));
              _reload();
            },
            title: Text(formatDate(d.debtDate), style: const TextStyle(fontWeight: FontWeight.w600)),
            subtitle: Text([
              if (!widget.isCustomer) directionLabels[d.debtType]!,
              'Məbləğ ${formatMoney(d.amount)} · ödənilib ${formatMoney(d.paidAmount)}',
              if (d.dueDate != null)
                'Son tarix: ${formatDate(d.dueDate)}${!d.isPaid && d.dueDate!.isBefore(DateTime(today.year, today.month, today.day)) ? ' · GECİKİR' : ''}',
            ].join('\n')),
            trailing: Text(formatMoney(d.remainingAmount),
                style: TextStyle(fontWeight: FontWeight.w800, color: d.isPaid ? Colors.green.shade700 : Colors.red.shade700)),
          ),
        ),
    ]);
  }

  Widget _paymentsTab(_Profile p) {
    if (p.payments.isEmpty) return const EmptyState(title: 'Ödəniş yoxdur');
    final owner = widget.session.me!.isOwner;
    return ListView(padding: const EdgeInsets.fromLTRB(12, 8, 12, 24), children: [
      for (final pay in p.payments)
        ListTile(
          leading: Icon(pay.isVoided ? Icons.block : Icons.south_west, color: pay.isVoided ? Colors.red.shade400 : Colors.green),
          title: Text(methodLabels[pay.paymentMethod] ?? pay.paymentMethod,
              style: pay.isVoided ? TextStyle(color: Colors.grey.shade600, decoration: TextDecoration.lineThrough) : null),
          subtitle: Text([
            formatDateTime(pay.paidAt),
            if (pay.isVoided) 'Ləğv edildi${pay.voidReason == null ? '' : ': ${pay.voidReason}'}',
            if (pay.description != null) pay.description!,
          ].join(' · ')),
          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
            Text(formatMoney(pay.amount),
                style: TextStyle(
                    fontWeight: FontWeight.w700,
                    color: pay.isVoided ? Colors.grey.shade500 : Colors.green.shade700,
                    decoration: pay.isVoided ? TextDecoration.lineThrough : null)),
            if (owner && !pay.isVoided)
              IconButton(tooltip: 'Düzəlt / ləğv et', icon: const Icon(Icons.more_vert), onPressed: () => _paymentActions(pay)),
          ]),
          onTap: owner && !pay.isVoided ? () => _paymentActions(pay) : null,
        ),
    ]);
  }

  /// Owner only: edit or cancel one of this customer's / company's payments; the card and its debts reload.
  Future<void> _paymentActions(Payment pay) async {
    final changed = await showPaymentActions(context, widget.session, pay);
    if (changed == null || !mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(changed.isVoided ? 'Ödəniş ləğv edildi, borc geri qaytarıldı' : 'Ödəniş düzəldildi')));
    _reload();
  }
}

class _Quick extends StatelessWidget {
  const _Quick({required this.icon, required this.label, required this.color, required this.onTap});

  final IconData icon;
  final String label;
  final Color color;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Expanded(
        child: Card(
          margin: const EdgeInsets.symmetric(horizontal: 3),
          child: InkWell(
            borderRadius: BorderRadius.circular(12),
            onTap: onTap,
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 10),
              child: Column(children: [
                Icon(icon, color: color),
                const SizedBox(height: 4),
                FittedBox(child: Text(label, style: TextStyle(fontSize: 11, color: color, fontWeight: FontWeight.w600))),
              ]),
            ),
          ),
        ),
      );
}

/// Template picker + editable text. Returns true when WhatsApp was opened (not "sent").
class _WhatsAppSheet extends StatefulWidget {
  const _WhatsAppSheet({required this.phone, required this.vars});

  final String phone;
  final TemplateVars vars;

  @override
  State<_WhatsAppSheet> createState() => _WhatsAppSheetState();
}

class _WhatsAppSheetState extends State<_WhatsAppSheet> {
  late String _key = widget.vars.remaining > 0 ? 'reminder' : 'thanks';
  late final _text = TextEditingController(text: templateText(_key, widget.vars));

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<void> _open({required bool web}) async {
    final n = waNumber(widget.phone)!;
    final uri = web
        ? Uri.https('web.whatsapp.com', '/send', {'phone': n, 'text': _text.text})
        : Uri.https('wa.me', '/$n', {'text': _text.text});
    final ok = await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (!mounted) return;
    if (ok) {
      Navigator.pop(context, true);
    } else {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('WhatsApp açıla bilmədi')));
    }
  }

  @override
  Widget build(BuildContext context) => ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
        const Text('WhatsApp mesajı', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: navy)),
        const SizedBox(height: 12),
        Wrap(spacing: 6, runSpacing: 6, children: [
          for (final e in templateLabels.entries)
            ChoiceChip(
              label: Text(e.value),
              selected: _key == e.key,
              onSelected: (_) => setState(() {
                _key = e.key;
                _text.text = templateText(e.key, widget.vars);
              }),
            ),
        ]),
        const SizedBox(height: 12),
        TextField(controller: _text, maxLines: 8, decoration: const InputDecoration(labelText: 'Mesaj (dəyişə bilərsiniz)')),
        const SizedBox(height: 12),
        FilledButton.icon(
          style: FilledButton.styleFrom(backgroundColor: Colors.green.shade700),
          onPressed: () => _open(web: false),
          icon: const Icon(Icons.chat),
          label: const Text('WhatsApp-da aç'),
        ),
        const SizedBox(height: 6),
        OutlinedButton(onPressed: () => _open(web: true), child: const Text('WhatsApp yoxdursa: WhatsApp Web')),
        const SizedBox(height: 8),
        Text('WhatsApp yalnız hazır mətnlə açılır. Mesaj göndərilmiş sayılmır — orada “Göndər”-i özünüz basın.',
            style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
        TextButton(
          onPressed: () {
            Clipboard.setData(ClipboardData(text: _text.text));
            ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Mətn kopyalandı')));
          },
          child: const Text('Mətni kopyala'),
        ),
      ]);
}

/// Internal notes (staff only) and the customer portal link.
class _NotesAndPortal extends StatefulWidget {
  const _NotesAndPortal({required this.session, required this.base, required this.party});

  final Session session;
  final String base;
  final Party party;

  @override
  State<_NotesAndPortal> createState() => _NotesAndPortalState();
}

class _NotesAndPortalState extends State<_NotesAndPortal> {
  final _note = TextEditingController();
  late Future<List<Map<String, dynamic>>> _notes = _fetchNotes();
  late Future<List<Map<String, dynamic>>> _links = _fetchLinks();
  int _days = 30;
  String? _created;
  bool _busy = false;

  Future<List<Map<String, dynamic>>> _fetchNotes() =>
      widget.session.api.get('${widget.base}/notes').then((j) => (j as List).cast<Map<String, dynamic>>());

  Future<List<Map<String, dynamic>>> _fetchLinks() =>
      widget.session.api.get('${widget.base}/portal-links').then((j) => (j as List).cast<Map<String, dynamic>>());

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  Future<void> _addNote() async {
    final t = _note.text.trim();
    if (t.isEmpty || _busy) return;
    setState(() => _busy = true);
    try {
      await widget.session.api.post('${widget.base}/notes', {'body': t});
      _note.clear();
      setState(() => _notes = _fetchNotes());
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _createLink() async {
    try {
      final res = await widget.session.api.post('${widget.base}/portal-links', {'days': _days}) as Map<String, dynamic>;
      setState(() {
        _created = res['url'] as String;
        _links = _fetchLinks();
      });
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _revoke(String id) async {
    try {
      await widget.session.api.delete('${widget.base}/portal-links/$id');
      setState(() => _links = _fetchLinks());
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) => ListView(padding: const EdgeInsets.fromLTRB(12, 8, 12, 24), children: [
        const Text('Daxili qeydlər', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: navy)),
        Text('Yalnız mağaza işçiləri görür.', style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
        const SizedBox(height: 8),
        Row(children: [
          Expanded(child: TextField(controller: _note, maxLength: 1000, decoration: const InputDecoration(hintText: 'Məs: Ayın sonunda ödəyəcək'))),
          IconButton.filled(onPressed: _busy ? null : _addNote, icon: const Icon(Icons.send)),
        ]),
        FutureBuilder<List<Map<String, dynamic>>>(
          future: _notes,
          builder: (_, snap) {
            if (snap.hasError) return ErrorBanner(errorMessage(snap.error!));
            if (!snap.hasData) return const LinearProgressIndicator();
            if (snap.data!.isEmpty) return Text('Qeyd yoxdur.', style: TextStyle(color: Colors.grey.shade600));
            return Column(children: [
              for (final n in snap.data!)
                Card(
                  child: ListTile(
                    title: Text('${n['body']}'),
                    subtitle: Text('${n['authorName']} · ${formatDateTime(DateTime.parse(n['createdAt'] as String))}'),
                  ),
                ),
            ]);
          },
        ),
        const Divider(height: 32),
        const Text('Müştəri kabineti', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: navy)),
        Text('Müştəri girişsiz yalnız öz borcunu, ödənişlərini və PDF çıxarışını görür.',
            style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
        const SizedBox(height: 8),
        Row(children: [
          DropdownButton<int>(
            value: _days,
            items: const [
              DropdownMenuItem(value: 7, child: Text('7 gün')),
              DropdownMenuItem(value: 30, child: Text('30 gün')),
              DropdownMenuItem(value: 90, child: Text('90 gün')),
              DropdownMenuItem(value: 365, child: Text('1 il')),
            ],
            onChanged: (v) => setState(() => _days = v ?? 30),
          ),
          const SizedBox(width: 12),
          FilledButton(onPressed: _createLink, child: const Text('Link yarat')),
        ]),
        if (_created != null)
          Card(
            color: Colors.green.shade50,
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('Link hazırdır (yalnız indi göstərilir):', style: TextStyle(fontWeight: FontWeight.w600)),
                SelectableText(_created!, style: const TextStyle(fontSize: 12)),
                Row(children: [
                  TextButton(
                      onPressed: () {
                        Clipboard.setData(ClipboardData(text: _created!));
                        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Kopyalandı')));
                      },
                      child: const Text('Kopyala')),
                  TextButton(
                    onPressed: () async {
                      final uri = waUri(widget.party.phone,
                          'Salam, ${widget.party.name}. "${widget.session.me?.storeName ?? ''}" mağazasında borc məlumatınıza bu linkdən baxa bilərsiniz:\n$_created');
                      if (uri != null) await launchUrl(uri, mode: LaunchMode.externalApplication);
                    },
                    child: const Text('WhatsApp ilə göndər'),
                  ),
                ]),
              ]),
            ),
          ),
        FutureBuilder<List<Map<String, dynamic>>>(
          future: _links,
          builder: (_, snap) {
            if (!snap.hasData) return const SizedBox.shrink();
            return Column(children: [
              for (final l in snap.data!)
                ListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(Icons.link, color: l['active'] == true ? Colors.green : Colors.grey),
                  title: Text(l['active'] == true ? 'Aktiv' : (l['revokedAt'] != null ? 'Ləğv edilib' : 'Vaxtı bitib')),
                  subtitle: Text('Yaradılıb ${formatDateTime(DateTime.parse(l['createdAt'] as String))}'
                      '${l['expiresAt'] != null ? ' · bitir ${formatDate(DateTime.parse(l['expiresAt'] as String).toLocal())}' : ''}'),
                  trailing: l['active'] == true
                      ? TextButton(onPressed: () => _revoke(l['id'] as String), child: const Text('Ləğv et'))
                      : null,
                ),
            ]);
          },
        ),
      ]);
}
