import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import '../whatsapp.dart';
import 'common.dart';
import 'account_sheet.dart';
import 'debts_screen.dart';
import 'parties_screen.dart';

const _sectionPermission = {
  'CUSTOMER_DEBT': 'CUSTOMER_DEBTS',
  'COMPANY_RECEIVABLE': 'COMPANY_RECEIVABLES',
  'COMPANY_PAYABLE': 'COMPANY_PAYABLES',
};

class _Profile {
  final Party party;
  final double totalDebt;
  final double paid;
  final double remaining;
  final int debtCount;
  final int openDebtCount;
  final List<Debt> debts;
  final List<Payment> payments;

  _Profile.fromJson(Map<String, dynamic> j)
      : party = Party.fromJson(j['party'] as Map<String, dynamic>),
        totalDebt = ((j['totals'] as Map)['totalDebt'] as num).toDouble(),
        paid = ((j['totals'] as Map)['paid'] as num).toDouble(),
        remaining = ((j['totals'] as Map)['remaining'] as num).toDouble(),
        debtCount = ((j['totals'] as Map)['debtCount'] as num).toInt(),
        openDebtCount = ((j['totals'] as Map)['openDebtCount'] as num).toInt(),
        debts = (j['debts'] as List).map((e) => Debt.fromJson(e as Map<String, dynamic>)).toList(),
        payments = (j['payments'] as List).map((e) => Payment.fromJson(e as Map<String, dynamic>)).toList();
}

/// Customer / company profile: total debt, paid, remaining, every debt and every payment.
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

  Future<_Profile> _fetch() => widget.session.api
      .get('/api/${widget.isCustomer ? 'customers' : 'companies'}/${widget.partyId}/profile')
      .then((j) => _Profile.fromJson(j as Map<String, dynamic>));

  void _reload() => setState(() => _data = _fetch());

  bool _can(String debtType) => widget.session.me!.can(_sectionPermission[debtType]!);

  bool get _canWrite => widget.isCustomer
      ? _can('CUSTOMER_DEBT')
      : (_can('COMPANY_RECEIVABLE') || _can('COMPANY_PAYABLE'));

  Future<void> _account(AccountMode mode) async {
    final msg = await openAccountSheet(context,
        session: widget.session, isCustomer: widget.isCustomer, partyId: widget.partyId, mode: mode);
    if (msg == null || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
    _reload();
  }

  Future<void> _openDebt(Debt d) async {
    await Navigator.of(context)
        .push(MaterialPageRoute(builder: (_) => DebtDetailScreen(session: widget.session, debt: d)));
    _reload();
  }

  Future<void> _edit(Party party) async {
    final saved = await openPartyForm(context, session: widget.session, isCustomer: widget.isCustomer, party: party);
    if (saved != null) _reload();
  }

  Future<void> _remind(_Profile p) async {
    final uri = waUri(p.party.phone,
        debtReminderText(widget.session.me?.storeName ?? '', p.party.name, p.remaining, null));
    if (uri == null) return;
    final opened = await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(opened
            ? 'WhatsApp hazır mətnlə açıldı — mesajı WhatsApp-da özünüz göndərin.'
            : 'WhatsApp açıla bilmədi')));
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<_Profile>(
      future: _data,
      builder: (context, snap) {
        final p = snap.data;
        return Scaffold(
          appBar: AppBar(
            title: Text(p?.party.name ?? (widget.isCustomer ? 'Müştəri' : 'Şirkət')),
            actions: [
              if (p != null && widget.isCustomer && p.remaining > 0 && waNumber(p.party.phone) != null)
                IconButton(
                  tooltip: 'WhatsApp ilə xatırlatma',
                  icon: const Icon(Icons.chat_outlined, color: Colors.green),
                  onPressed: () => _remind(p),
                ),
              if (p != null)
                IconButton(tooltip: 'Redaktə et', icon: const Icon(Icons.edit_outlined), onPressed: () => _edit(p.party)),
            ],
          ),
          bottomNavigationBar: p != null && _canWrite
              ? SafeArea(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                    child: Row(children: [
                      Expanded(
                        child: FilledButton.icon(
                          style: FilledButton.styleFrom(
                              backgroundColor: gold, foregroundColor: navy, padding: const EdgeInsets.symmetric(vertical: 14)),
                          onPressed: () => _account(AccountMode.charge),
                          icon: const Icon(Icons.add),
                          label: const FittedBox(child: Text('Borc əlavə et')),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: FilledButton.icon(
                          style: FilledButton.styleFrom(padding: const EdgeInsets.symmetric(vertical: 14)),
                          onPressed: () => _account(AccountMode.pay),
                          icon: const Icon(Icons.payments_outlined),
                          label: const FittedBox(child: Text('Borc ödə')),
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
                  : RefreshIndicator(
                      onRefresh: () async {
                        _reload();
                        await _data.catchError((_) => p);
                      },
                      child: _body(p),
                    ),
        );
      },
    );
  }

  Widget _body(_Profile p) {
    return ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 96), children: [
      if ((p.party.phone ?? '').isNotEmpty || (p.party.note ?? '').isNotEmpty)
        Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: Text([p.party.phone, p.party.note].where((e) => e != null && e.isNotEmpty).join(' · '),
              style: TextStyle(color: Colors.grey.shade700)),
        ),
      StatTile(label: 'Qalıq borc', value: formatMoney(p.remaining)),
      const SizedBox(height: 10),
      Row(children: [
        Expanded(child: _mini('Ümumi borc', p.totalDebt, navy, null)),
        const SizedBox(width: 10),
        Expanded(child: _mini('Ödənilib', p.paid, Colors.green.shade700, null)),
      ]),
      if (!widget.isCustomer) ...[
        const SizedBox(height: 10),
        Row(children: [
          Expanded(child: _mini(directionLabels['COMPANY_RECEIVABLE']!, _byType(p, 'COMPANY_RECEIVABLE'), Colors.green.shade700, null)),
          const SizedBox(width: 10),
          Expanded(child: _mini(directionLabels['COMPANY_PAYABLE']!, _byType(p, 'COMPANY_PAYABLE'), Colors.red.shade700, null)),
        ]),
      ],
      const SizedBox(height: 20),
      const Text('Borc hesabı', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: navy)),
      const SizedBox(height: 8),
      if (p.debts.isEmpty) const EmptyState(title: 'Borc yoxdur', text: '“Borc əlavə et” düyməsi ilə əlavə edin.'),
      for (final d in p.debts)
        Card(
          child: Column(children: [
            ListTile(
              onTap: () => _openDebt(d),
              title: Text(d.description?.isNotEmpty == true ? d.description! : formatDate(d.debtDate),
                  style: const TextStyle(fontWeight: FontWeight.w600)),
              subtitle: Text([
                formatDate(d.debtDate),
                if (!widget.isCustomer) directionLabels[d.debtType]!,
                'Məbləğ ${formatMoney(d.amount)}',
              ].join(' · ')),
              trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                Text(formatMoney(d.remainingAmount),
                    style: TextStyle(fontWeight: FontWeight.w700, color: d.isPaid ? Colors.green.shade700 : Colors.red.shade700)),
                const SizedBox(height: 4),
                StatusChip(d.status),
              ]),
            ),
          ]),
        ),
      const SizedBox(height: 20),
      const Text('Ödəniş tarixçəsi', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: navy)),
      const SizedBox(height: 8),
      if (p.payments.isEmpty) const EmptyState(title: 'Ödəniş yoxdur'),
      for (final pay in p.payments)
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.south_west, color: Colors.green),
          title: Text(methodLabels[pay.paymentMethod] ?? pay.paymentMethod),
          subtitle: Text([formatDateTime(pay.paidAt), if (pay.description != null) pay.description!].join(' · ')),
          trailing: Text(formatMoney(pay.amount), style: TextStyle(fontWeight: FontWeight.w700, color: Colors.green.shade700)),
        ),
    ]);
  }

  double _byType(_Profile p, String type) =>
      (p.debts.where((d) => d.debtType == type).fold<double>(0, (s, d) => s + d.remainingAmount) * 100).roundToDouble() / 100;

  Widget _mini(String label, double value, Color color, String? hint) => Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
            const SizedBox(height: 4),
            FittedBox(
              fit: BoxFit.scaleDown,
              alignment: Alignment.centerLeft,
              child: Text(formatMoney(value), style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: color)),
            ),
            if (hint != null) Text(hint, style: TextStyle(color: Colors.grey.shade500, fontSize: 11)),
          ]),
        ),
      );
}
