import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';
import 'notifications_screen.dart';
import 'profile_screen.dart';

/// Profile, subscription information, notifications and sign-out.
class MoreScreen extends StatelessWidget {
  const MoreScreen({super.key, required this.session});

  final Session session;

  @override
  Widget build(BuildContext context) {
    final me = session.me!;
    final sub = me.subscription;
    return Scaffold(
      appBar: AppBar(title: const Text('Daha')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Card(
          child: ListTile(
            leading: UserAvatar(session: session, name: me.fullName, version: me.hasAvatar ? me.avatarVersion : null),
            title: Text(me.fullName, style: const TextStyle(fontWeight: FontWeight.w700)),
            subtitle: Text('${me.email}\n${me.isOwner ? 'Mağaza sahibi' : me.isSuperAdmin ? 'Platforma administratoru' : 'İşçi'}'),
            isThreeLine: true,
            trailing: const Icon(Icons.settings_outlined),
            onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ProfileScreen(session: session))),
          ),
        ),
        const SizedBox(height: 12),
        Card(
          child: ListTile(
            leading: const Icon(Icons.workspace_premium_outlined, color: navy),
            title: const Text('Abunəlik'),
            subtitle: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(sub == null
                  ? 'Məlumat yoxdur'
                  : sub.active
                      ? '${sub.planName ?? ''} · bitmə: ${formatDate(sub.endDate)}'
                      : 'Aktiv deyil'),
              if (sub?.expiresAt != null) CountdownText(expiresAt: sub!.expiresAt),
            ]),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => SubscriptionScreen(session: session))),
          ),
        ),
        if (sub != null && sub.renewalReminder)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: ErrorBanner('Abunəliyinizin müddəti ${sub.daysRemaining} gün sonra bitir. Abunəlik bölməsindən yeniləyin.'),
          ),
        Card(
          child: ListTile(
            leading: const Icon(Icons.notifications_none, color: navy),
            title: const Text('Bildirişlər'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => NotificationsScreen(session: session))),
          ),
        ),
        const SizedBox(height: 24),
        OutlinedButton.icon(
          onPressed: session.logout,
          icon: const Icon(Icons.logout),
          label: const Text('Çıxış'),
        ),
      ]),
    );
  }
}

/// Live subscription counter: green normally, red when expired or fewer than 7 days remain.
class CountdownText extends StatefulWidget {
  const CountdownText({super.key, required this.expiresAt, this.fontSize = 13, this.emptyText = '—'});

  final DateTime? expiresAt;
  final double fontSize;
  final String emptyText;

  @override
  State<CountdownText> createState() => _CountdownTextState();
}

class _CountdownTextState extends State<CountdownText> {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = Countdown.of(widget.expiresAt, DateTime.now());
    if (c == null) {
      return Text(widget.emptyText, style: TextStyle(color: Colors.grey.shade600, fontSize: widget.fontSize));
    }
    final color = c.warning ? Colors.red.shade700 : Colors.green.shade700;
    return Row(mainAxisSize: MainAxisSize.min, children: [
      Icon(c.warning ? Icons.warning_amber_rounded : Icons.timer_outlined, size: widget.fontSize + 3, color: color),
      const SizedBox(width: 4),
      Text(c.text,
          style: TextStyle(
            color: color,
            fontSize: widget.fontSize,
            fontWeight: c.warning ? FontWeight.w700 : FontWeight.w500,
            fontFeatures: const [FontFeature.tabularFigures()],
          )),
    ]);
  }
}

/// Subscription status with live countdown, packages and the "Abunə ol" flow (contact the admin, send the receipt).
class SubscriptionScreen extends StatefulWidget {
  const SubscriptionScreen({super.key, required this.session});

  final Session session;

  @override
  State<SubscriptionScreen> createState() => _SubscriptionScreenState();
}

class _SubscriptionScreenState extends State<SubscriptionScreen> {
  late Future<List<Plan>> _plans = _loadPlans();
  String? _busyCode;

  Future<List<Plan>> _loadPlans() => widget.session.api
      .get('/api/public/plans')
      .then((j) => (j as List).map((e) => Plan.fromJson(e as Map<String, dynamic>)).toList());

  Future<void> _subscribe(Plan plan) async {
    setState(() => _busyCode = plan.code);
    try {
      final contact = Contact.fromJson(await widget.session.api.get('/api/public/contact') as Map<String, dynamic>);
      if (mounted) await _showContact(plan, contact);
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _busyCode = null);
    }
  }

  Future<bool> _sendConfirmation(Plan plan, String reference) async {
    try {
      await widget.session.api.post('/api/subscriptions/request', {
        'planCode': plan.code,
        if (reference.trim().isNotEmpty) 'payerReference': reference.trim(),
      });
      await widget.session.refresh();
      if (mounted) setState(() {});
      return true;
    } catch (e) {
      if (mounted) showError(context, e);
      return false;
    }
  }

  Future<void> _open(Uri uri) async {
    final ok = await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (!ok && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Tətbiq açıla bilmədi')));
    }
  }

  Future<void> _showContact(Plan plan, Contact contact) {
    final store = widget.session.me?.storeName ?? '';
    final message =
        'Salam! "$store" mağazası üçün ${plan.name} (${formatMoney(plan.price)}) abunəliyinin ödənişini etdim. Çeki göndərirəm.';
    final phone = contact.phone;
    final reference = TextEditingController();
    var sending = false;
    var sent = false;
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheet) => SafeArea(
          child: SingleChildScrollView(
            padding: EdgeInsets.fromLTRB(20, 0, 20, MediaQuery.viewInsetsOf(ctx).bottom + 20),
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Text('Abunə ol — ${plan.name}', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: navy)),
              const SizedBox(height: 12),
              StatTile(label: 'Ödəniləcək məbləğ', value: formatMoney(plan.price), hint: '${plan.durationMonths} ay'),
              const SizedBox(height: 16),
              const Text('1. Ödənişi edin', style: TextStyle(fontWeight: FontWeight.w700, color: navy)),
              const SizedBox(height: 8),
              if (contact.m10Account != null)
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: gold.withValues(alpha: 0.08),
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: gold.withValues(alpha: 0.5)),
                  ),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    const Text('M10 ilə ödəniş', style: TextStyle(fontSize: 12, color: Colors.black54)),
                    Row(children: [
                      Expanded(
                        child: SelectableText(contact.m10Account!,
                            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: navy)),
                      ),
                      IconButton(
                        tooltip: 'Kopyala',
                        icon: const Icon(Icons.copy),
                        onPressed: () => Clipboard.setData(ClipboardData(text: contact.m10Account!)),
                      ),
                    ]),
                    if (contact.m10Recipient != null) Text('Alan: ${contact.m10Recipient}'),
                    if (contact.paymentNote != null)
                      Text(contact.paymentNote!, style: TextStyle(color: Colors.grey.shade700, fontSize: 12)),
                  ]),
                )
              else
                const ErrorBanner('M10 ödəniş məlumatları hələ əlavə edilməyib. Ödəniş üçün administratorla əlaqə saxlayın.'),
              if (phone != null) ...[
                const SizedBox(height: 8),
                Row(children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: () => _open(Uri(scheme: 'tel', path: phone.replaceAll(RegExp(r'[^+\d]'), ''))),
                      icon: const Icon(Icons.call),
                      label: const Text('Zəng et'),
                    ),
                  ),
                  if (contact.whatsapp != null) ...[
                    const SizedBox(width: 8),
                    Expanded(
                      child: FilledButton.icon(
                        style: FilledButton.styleFrom(backgroundColor: Colors.green.shade600),
                        onPressed: () => _open(Uri.https('wa.me', '/${contact.whatsapp}', {'text': message})),
                        icon: const Icon(Icons.chat),
                        label: const Text('Çeki göndər'),
                      ),
                    ),
                  ],
                ]),
              ],
              const SizedBox(height: 16),
              const Text('2. Təsdiq sorğusu göndərin', style: TextStyle(fontWeight: FontWeight.w700, color: navy)),
              const SizedBox(height: 8),
              if (sent)
                Text('Sorğunuz göndərildi. Administrator ödənişi təsdiqləyəndən sonra mağazanız avtomatik aktiv olacaq.',
                    style: TextStyle(color: Colors.green.shade800))
              else ...[
                TextField(
                  controller: reference,
                  maxLength: 120,
                  decoration: const InputDecoration(labelText: 'M10 / bank əməliyyat nömrəsi (istəyə bağlı)'),
                ),
                FilledButton(
                  style: FilledButton.styleFrom(backgroundColor: gold, foregroundColor: navy),
                  onPressed: sending
                      ? null
                      : () async {
                          setSheet(() => sending = true);
                          final ok = await _sendConfirmation(plan, reference.text);
                          setSheet(() {
                            sending = false;
                            sent = ok;
                          });
                        },
                  child: sending
                      ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('Ödəniş etdim — təsdiq sorğusu göndər'),
                ),
              ],
              const SizedBox(height: 8),
              Text('Ödəniş administrator tərəfindən təsdiqlənməyənə qədər mağaza aktivləşdirilmir.',
                  style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
            ]),
          ),
        ),
      ),
    ).whenComplete(reference.dispose);
  }

  Future<void> _refresh() async {
    try {
      await widget.session.refresh();
      if (mounted) setState(() => _plans = _loadPlans());
    } catch (e) {
      if (mounted) showError(context, e);
    }
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
              if (sub?.status == 'PENDING') ...[
                const SizedBox(height: 8),
                Text('Sorğunuz administratora göndərilib — ödəniş çekini göndərin.',
                    style: TextStyle(color: Colors.orange.shade800)),
              ],
            ]),
          ),
          const SizedBox(height: 16),
          const Text('Paketlər', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: navy)),
          const SizedBox(height: 8),
          FutureBuilder<List<Plan>>(
            future: _plans,
            builder: (context, snap) {
              if (snap.hasError) return ErrorBanner(errorMessage(snap.error!));
              if (!snap.hasData) return const Center(child: CircularProgressIndicator());
              return Column(children: [
                for (final p in snap.data!)
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Row(children: [
                        Expanded(
                          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            Text(p.periodLabel.toUpperCase(),
                                style: TextStyle(fontSize: 11, letterSpacing: 1.2, color: Colors.grey.shade600)),
                            Text(p.name, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16, color: navy)),
                            Text(formatMoney(p.price),
                                style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: navy)),
                            Text('ayda ${formatMoney(p.price / p.durationMonths)}',
                                style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
                          ]),
                        ),
                        FilledButton(
                          onPressed: !isOwner || _busyCode != null ? null : () => _subscribe(p),
                          child: _busyCode == p.code
                              ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                              : const Text('Abunə ol'),
                        ),
                      ]),
                    ),
                  ),
                if (!isOwner)
                  const Padding(
                    padding: EdgeInsets.only(top: 8),
                    child: Text('Abunəliyi yalnız mağaza sahibi ala bilər.'),
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
