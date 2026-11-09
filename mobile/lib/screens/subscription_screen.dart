import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';
import 'more_screen.dart';

/// Readable text colour on top of [c].
Color _onColor(Color c) => c.computeLuminance() > 0.6 ? const Color(0xFF0F172A) : Colors.white;

/// Active subscription with live countdown, the admin-designed packages and "Ödəniş sorğularım".
class SubscriptionScreen extends StatefulWidget {
  const SubscriptionScreen({super.key, required this.session});

  final Session session;

  @override
  State<SubscriptionScreen> createState() => _SubscriptionScreenState();
}

class _SubscriptionScreenState extends State<SubscriptionScreen> {
  late Future<(List<Plan>, List<PaymentRequest>)> _data = _load();
  final Map<String, String> _period = {};

  Future<(List<Plan>, List<PaymentRequest>)> _load() async {
    final api = widget.session.api;
    final plans = (await api.get('/api/public/plans') as List)
        .map((e) => Plan.fromJson(e as Map<String, dynamic>))
        .toList();
    final history = (await api.get('/api/subscriptions/history') as List)
        .map((e) => PaymentRequest.fromJson(e as Map<String, dynamic>))
        .toList();
    return (plans, history);
  }

  Future<void> _refresh() async {
    try {
      await widget.session.refresh();
      if (mounted) setState(() => _data = _load());
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _choose(Plan plan) async {
    final option = plan.optionFor(_period[plan.code]);
    final sent = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => PaymentScreen(session: widget.session, plan: plan, option: option)),
    );
    if (sent == true) await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final me = widget.session.me;
    final sub = me?.subscription;
    final isOwner = me?.isOwner ?? false;
    final warn = Countdown.of(sub?.expiresAt, DateTime.now())?.warning ?? !(sub?.active ?? false);
    return Scaffold(
      appBar: AppBar(title: const Text('Abunəlik')),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(padding: const EdgeInsets.all(16), children: [
          const Text('Aktiv abunəliyim', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: navy)),
          const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: warn ? Colors.red.shade50 : Colors.white,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: warn ? Colors.red.shade300 : Colors.grey.shade200),
            ),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(sub?.active == true ? 'Aktiv · ${sub!.planName ?? ''}' : 'Aktiv abunəlik yoxdur',
                  style: const TextStyle(fontWeight: FontWeight.w700, color: navy, fontSize: 16)),
              if (sub?.endDate != null)
                Text('Bitmə tarixi: ${formatDate(sub!.endDate)}', style: TextStyle(color: Colors.grey.shade600)),
              const SizedBox(height: 8),
              CountdownText(expiresAt: sub?.expiresAt, fontSize: 20, emptyText: 'Paket seçin və abunə olun'),
            ]),
          ),
          const SizedBox(height: 20),
          const Text('Abunəlik paketləri', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: navy)),
          const SizedBox(height: 8),
          FutureBuilder<(List<Plan>, List<PaymentRequest>)>(
            future: _data,
            builder: (context, snap) {
              if (snap.hasError) return ErrorBanner(errorMessage(snap.error!));
              if (!snap.hasData) return const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()));
              final (plans, history) = snap.data!;
              final pending = history.where((h) => h.status == 'PENDING').firstOrNull;
              return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                if (pending != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Text('${pending.planName} (${pending.periodText}) üçün ödəniş sorğunuz yoxlanılır.',
                        style: TextStyle(color: Colors.orange.shade800)),
                  ),
                if (plans.isEmpty) const Text('Hazırda satışda paket yoxdur.'),
                for (final p in plans)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: PlanCard(
                      plan: p,
                      selected: _period[p.code],
                      onPeriod: (v) => setState(() => _period[p.code] = v),
                      actionLabel: pending?.planCode == p.code ? 'Sorğu göndərilib — bax' : 'Paketi seç',
                      onAction: isOwner ? () => _choose(p) : null,
                    ),
                  ),
                if (!isOwner) const Text('Abunəliyi yalnız mağaza sahibi ala bilər.'),
                const SizedBox(height: 12),
                const Text('Ödəniş sorğularım', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: navy)),
                const SizedBox(height: 8),
                if (history.isEmpty) Text('Hələ sorğu yoxdur', style: TextStyle(color: Colors.grey.shade600)),
                for (final h in history)
                  Card(
                    child: ListTile(
                      title: Text('${h.planName} · ${h.periodText}'),
                      subtitle: Text([
                        '${formatDate(h.requestedAt)} · ${formatMoney(h.amount)}${h.paymentMethod == null ? '' : ' · ${h.paymentMethod == 'CARD' ? 'Kart' : 'M10'}'}',
                        if (h.startDate != null) 'Dövr: ${formatDate(h.startDate)} – ${formatDate(h.endDate)}',
                        if (h.rejectReason != null) 'Səbəb: ${h.rejectReason}',
                      ].join('\n')),
                      isThreeLine: h.startDate != null || h.rejectReason != null,
                      trailing: _StatusChip(h.requestStatus, h.statusLabel),
                    ),
                  ),
              ]);
            },
          ),
          const SizedBox(height: 12),
          OutlinedButton(onPressed: _refresh, child: const Text('Statusu yenilə')),
        ]),
      ),
    );
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip(this.status, this.label);

  final String status;
  final String label;

  @override
  Widget build(BuildContext context) {
    final color = switch (status) {
      'APPROVED' => Colors.green.shade700,
      'REJECTED' => Colors.red.shade700,
      _ => Colors.orange.shade800,
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(20)),
      child: Text(label, style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w600)),
    );
  }
}

/// A package card in the admin's colours with period chips.
class PlanCard extends StatelessWidget {
  const PlanCard({super.key, required this.plan, required this.selected, required this.onPeriod, required this.actionLabel, this.onAction});

  final Plan plan;
  final String? selected;
  final ValueChanged<String> onPeriod;
  final String actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final primary = hexColor(plan.primaryColor, navy);
    final secondary = hexColor(plan.secondaryColor, gold);
    final bg = hexColor(plan.backgroundColor, Colors.white);
    final fg = hexColor(plan.textColor, navy);
    final button = hexColor(plan.buttonColor, navy);
    final option = plan.optionFor(selected);
    return Container(
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: plan.recommended ? primary : fg.withValues(alpha: 0.15), width: 2),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Container(height: 5, decoration: BoxDecoration(gradient: LinearGradient(colors: [primary, secondary]))),
        Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text(plan.name, style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: fg))),
              if (plan.recommended)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(color: secondary, borderRadius: BorderRadius.circular(20)),
                  child: Text('TÖVSİYƏ', style: TextStyle(fontSize: 10, fontWeight: FontWeight.w800, color: _onColor(secondary))),
                ),
            ]),
            if (plan.description != null) Text(plan.description!, style: TextStyle(color: fg.withValues(alpha: 0.7))),
            if (plan.periods.length > 1) ...[
              const SizedBox(height: 10),
              Wrap(spacing: 6, runSpacing: 6, children: [
                for (final o in plan.periods)
                  ChoiceChip(
                    label: Text(o.label),
                    selected: o.period == option.period,
                    onSelected: (_) => onPeriod(o.period),
                    selectedColor: primary,
                    labelStyle: TextStyle(color: o.period == option.period ? _onColor(primary) : fg, fontSize: 12),
                    backgroundColor: bg,
                    side: BorderSide(color: fg.withValues(alpha: 0.2)),
                    showCheckmark: false,
                  ),
              ]),
            ],
            const SizedBox(height: 10),
            Text(formatMoney(option.price), style: TextStyle(fontSize: 28, fontWeight: FontWeight.w700, color: fg)),
            Text(option.durationText, style: TextStyle(color: fg.withValues(alpha: 0.7), fontSize: 12)),
            if (plan.features.isNotEmpty || plan.maxEmployees != null || plan.maxCustomers != null) const SizedBox(height: 8),
            for (final f in plan.features)
              Padding(
                padding: const EdgeInsets.only(top: 3),
                child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('✓  ', style: TextStyle(color: secondary, fontWeight: FontWeight.w800)),
                  Expanded(child: Text(f, style: TextStyle(color: fg))),
                ]),
              ),
            if (plan.maxEmployees != null) Text('•  ${plan.maxEmployees} işçiyə qədər', style: TextStyle(color: fg.withValues(alpha: 0.8))),
            if (plan.maxCustomers != null) Text('•  ${plan.maxCustomers} müştəriyə qədər', style: TextStyle(color: fg.withValues(alpha: 0.8))),
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                style: FilledButton.styleFrom(backgroundColor: button, foregroundColor: _onColor(button)),
                onPressed: onAction,
                child: Text(actionLabel),
              ),
            ),
          ]),
        ),
      ]),
    );
  }
}

/// Payment page: where to pay (M10 / card), WhatsApp contact and the "Ödəniş etdim" request.
class PaymentScreen extends StatefulWidget {
  const PaymentScreen({super.key, required this.session, required this.plan, required this.option});

  final Session session;
  final Plan plan;
  final PeriodOption option;

  @override
  State<PaymentScreen> createState() => _PaymentScreenState();
}

class _PaymentScreenState extends State<PaymentScreen> {
  late final Future<Contact> _contact = widget.session.api
      .get('/api/public/contact')
      .then((j) => Contact.fromJson(j as Map<String, dynamic>));
  String _method = 'M10';
  bool _methodChosen = false;
  final _payer = TextEditingController();
  late final _amount = TextEditingController(text: widget.option.price.toStringAsFixed(2));
  final _reference = TextEditingController();
  final _note = TextEditingController();
  DateTime _date = DateTime.now();
  XFile? _receipt;
  bool _sending = false;
  bool _sent = false;

  @override
  void initState() {
    super.initState();
    _payer.text = widget.session.me?.fullName ?? '';
  }

  @override
  void dispose() {
    _payer.dispose();
    _amount.dispose();
    _reference.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _open(Uri uri) async {
    final ok = await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (!ok && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Tətbiq açıla bilmədi')));
    }
  }

  void _copy(String text) {
    Clipboard.setData(ClipboardData(text: text));
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Kopyalandı'), duration: Duration(seconds: 1)));
  }

  Future<void> _pickReceipt() async {
    final picked = await ImagePicker().pickImage(source: ImageSource.gallery, maxWidth: 2000, maxHeight: 2000, imageQuality: 85);
    if (picked != null) setState(() => _receipt = picked);
  }

  String _iso(DateTime d) => '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  Future<void> _send() async {
    final period = widget.option.period == 'LEGACY' ? null : widget.option.period;
    final fields = <String, String>{
      'planCode': widget.plan.code,
      'period': ?period,
      'paymentMethod': _method,
      if (_payer.text.trim().isNotEmpty) 'payerName': _payer.text.trim(),
      if (_amount.text.trim().isNotEmpty) 'paidAmount': _amount.text.trim().replaceAll(',', '.'),
      'paymentDate': _iso(_date),
      if (_reference.text.trim().isNotEmpty) 'payerReference': _reference.text.trim(),
      if (_note.text.trim().isNotEmpty) 'note': _note.text.trim(),
    };
    setState(() => _sending = true);
    try {
      final api = widget.session.api;
      if (_receipt != null) {
        final bytes = await _receipt!.readAsBytes();
        final name = _receipt!.name.toLowerCase();
        final type = name.endsWith('.png') ? 'image/png' : 'image/jpeg';
        await api.upload('/api/subscriptions', 'receipt', bytes, _receipt!.name, type, fields: fields);
      } else {
        await api.post('/api/subscriptions/request', {
          ...fields,
          if (fields['paidAmount'] != null) 'paidAmount': double.tryParse(fields['paidAmount']!),
        });
      }
      await widget.session.refresh();
      if (mounted) setState(() => _sent = true);
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final plan = widget.plan;
    final option = widget.option;
    final primary = hexColor(plan.primaryColor, navy);
    final bg = hexColor(plan.backgroundColor, Colors.white);
    final fg = hexColor(plan.textColor, navy);
    final isOwner = widget.session.me?.isOwner ?? false;
    return Scaffold(
        appBar: AppBar(
          title: const Text('Ödəniş'),
          leading: BackButton(onPressed: () => Navigator.of(context).pop(_sent)),
        ),
        body: FutureBuilder<Contact>(
          future: _contact,
          builder: (context, snap) {
            if (snap.hasError) return Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!)));
            if (!snap.hasData) return const Center(child: CircularProgressIndicator());
            final c = snap.data!;
            final hasM10 = c.m10Account != null;
            final hasCard = c.cardNumber != null;
            if (!_methodChosen && !hasM10 && hasCard) _method = 'CARD';
            final wa = c.whatsapp == null
                ? null
                : Uri.https('wa.me', '/${c.whatsapp}', {
                    'text': fillWhatsappTemplate(c.whatsappTemplate,
                        store: widget.session.me?.storeName ?? '',
                        plan: plan.name,
                        period: option.durationText,
                        amount: formatMoney(option.price)),
                  });
            return ListView(padding: const EdgeInsets.all(16), children: [
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(16), border: Border.all(color: primary, width: 2)),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('SEÇİLMİŞ PAKET', style: TextStyle(fontSize: 11, letterSpacing: 1.2, color: fg.withValues(alpha: 0.7))),
                  Text(plan.name, style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: fg)),
                  Text('Müddət: ${option.label} — ${option.durationText}', style: TextStyle(color: fg.withValues(alpha: 0.8))),
                  const SizedBox(height: 6),
                  Text(formatMoney(option.price), style: TextStyle(fontSize: 28, fontWeight: FontWeight.w700, color: fg)),
                ]),
              ),
              if (c.paymentInstructions != null) ...[
                const SizedBox(height: 12),
                Text(c.paymentInstructions!, style: TextStyle(color: Colors.blueGrey.shade700)),
              ],
              const SizedBox(height: 16),
              const Text('1. Ödəniş üsulu', style: TextStyle(fontWeight: FontWeight.w700, color: navy)),
              const SizedBox(height: 8),
              if (!hasM10 && !hasCard)
                const ErrorBanner('Ödəniş məlumatları hələ əlavə edilməyib. WhatsApp ilə administratorla əlaqə saxlayın.')
              else ...[
                SegmentedButton<String>(
                  segments: [
                    ButtonSegment(value: 'M10', label: const Text('M10'), enabled: hasM10),
                    ButtonSegment(value: 'CARD', label: const Text('Bank kartı'), enabled: hasCard),
                  ],
                  selected: {_method},
                  onSelectionChanged: (s) => setState(() {
                    _method = s.first;
                    _methodChosen = true;
                  }),
                ),
                const SizedBox(height: 8),
                _DetailsBox(rows: _method == 'M10'
                    ? [
                        ('M10 nömrəsi', c.m10Account, c.m10Account),
                        ('Hesab sahibi', c.m10Recipient, null),
                        ('Məbləğ', formatMoney(option.price), option.price.toStringAsFixed(2)),
                      ]
                    : [
                        ('Kart nömrəsi', _formatCard(c.cardNumber), c.cardNumber?.replaceAll(RegExp(r'\D'), '')),
                        ('Kart sahibi', c.cardHolder, null),
                        ('Bank', c.cardBank, null),
                        ('Məbləğ', formatMoney(option.price), option.price.toStringAsFixed(2)),
                      ], note: _method == 'M10' ? c.paymentNote : c.cardNote, onCopy: _copy),
              ],
              const SizedBox(height: 12),
              if (wa != null)
                FilledButton.icon(
                  style: FilledButton.styleFrom(backgroundColor: Colors.green.shade600, minimumSize: const Size.fromHeight(48)),
                  onPressed: () => _open(wa),
                  icon: const Icon(Icons.chat),
                  label: const Text('WhatsApp ilə əlaqə saxla'),
                ),
              if (c.extraPhone != null)
                TextButton.icon(
                  onPressed: () => _open(Uri(scheme: 'tel', path: c.extraPhone!.replaceAll(RegExp(r'[^+\d]'), ''))),
                  icon: const Icon(Icons.call),
                  label: Text('Əlavə nömrə: ${c.extraPhone}'),
                ),
              if (c.extraContact != null) Text(c.extraContact!, textAlign: TextAlign.center, style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
              const SizedBox(height: 16),
              const Text('2. “Ödəniş etdim”', style: TextStyle(fontWeight: FontWeight.w700, color: navy)),
              const SizedBox(height: 8),
              if (!isOwner)
                const Text('Ödəniş sorğusunu yalnız mağaza sahibi göndərə bilər.')
              else if (_sent)
                Text(c.afterPaymentText ?? 'Sorğunuz göndərildi. Administrator ödənişi təsdiqləyəndən sonra abunəliyiniz avtomatik aktivləşəcək.',
                    style: TextStyle(color: Colors.green.shade800))
              else ...[
                TextField(controller: _payer, maxLength: 120, decoration: const InputDecoration(labelText: 'Ödəniş edən şəxsin adı')),
                TextField(
                  controller: _amount,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  decoration: InputDecoration(labelText: 'Ödəniş məbləği (₼)', helperText: 'Paketin qiyməti: ${formatMoney(option.price)}'),
                ),
                const SizedBox(height: 8),
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.event),
                  title: Text('Ödəniş tarixi: ${formatDate(_date)}'),
                  onTap: () async {
                    final d = await showDatePicker(
                      context: context,
                      initialDate: _date,
                      firstDate: DateTime.now().subtract(const Duration(days: 365)),
                      lastDate: DateTime.now(),
                    );
                    if (d != null) setState(() => _date = d);
                  },
                ),
                TextField(controller: _reference, maxLength: 120, decoration: const InputDecoration(labelText: 'Əməliyyat nömrəsi (istəyə bağlı)')),
                TextField(controller: _note, maxLength: 500, maxLines: 2, decoration: const InputDecoration(labelText: 'Əlavə qeyd')),
                OutlinedButton.icon(
                  onPressed: _pickReceipt,
                  icon: const Icon(Icons.receipt_long),
                  label: Text(_receipt == null ? 'Çek şəkli əlavə et' : 'Çek: ${_receipt!.name}'),
                ),
                const SizedBox(height: 8),
                FilledButton(
                  style: FilledButton.styleFrom(backgroundColor: gold, foregroundColor: navy, minimumSize: const Size.fromHeight(48)),
                  onPressed: _sending ? null : _send,
                  child: _sending
                      ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('Ödəniş etdim — təsdiq sorğusu göndər'),
                ),
              ],
              const SizedBox(height: 8),
              Text('Ödəniş administrator tərəfindən təsdiqlənməyənə qədər abunəlik aktivləşmir.',
                  style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
            ]);
          },
        ),
    );
  }
}

String? _formatCard(String? n) {
  if (n == null) return null;
  final d = n.replaceAll(RegExp(r'\D'), '');
  final parts = <String>[];
  for (var i = 0; i < d.length; i += 4) {
    parts.add(d.substring(i, i + 4 > d.length ? d.length : i + 4));
  }
  return parts.join(' ');
}

class _DetailsBox extends StatelessWidget {
  const _DetailsBox({required this.rows, required this.note, required this.onCopy});

  /// (label, value, copy value or null)
  final List<(String, String?, String?)> rows;
  final String? note;
  final ValueChanged<String> onCopy;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: gold.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: gold.withValues(alpha: 0.5)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        for (final (label, value, copy) in rows)
          if (value != null)
            Row(children: [
              Expanded(child: Text(label, style: const TextStyle(fontSize: 12, color: Colors.black54))),
              Flexible(
                flex: 2,
                child: SelectableText(value, textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w700, color: navy)),
              ),
              if (copy != null)
                IconButton(tooltip: 'Kopyala', icon: const Icon(Icons.copy, size: 18), onPressed: () => onCopy(copy))
              else
                const SizedBox(width: 48),
            ]),
        if (note != null) Text(note!, style: TextStyle(color: Colors.grey.shade700, fontSize: 12)),
      ]),
    );
  }
}
