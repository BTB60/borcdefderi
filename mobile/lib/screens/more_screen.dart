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
            leading: const CircleAvatar(backgroundColor: navy, child: Icon(Icons.person, color: gold)),
            title: Text(me.fullName, style: const TextStyle(fontWeight: FontWeight.w700)),
            subtitle: Text('${me.email}\n${me.isOwner ? 'Mağaza sahibi' : me.isSuperAdmin ? 'Platforma administratoru' : 'İşçi'}'),
            isThreeLine: true,
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
      await widget.session.api.post('/api/subscriptions/request', {'planCode': plan.code});
      final contact = Contact.fromJson(await widget.session.api.get('/api/public/contact') as Map<String, dynamic>);
      await widget.session.refresh();
      if (mounted) await _showContact(plan, contact);
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _busyCode = null);
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
        'Salam! "$store" mağazası üçün ${plan.name} (${formatMoney(plan.price)}) abunəliyini almaq istəyirəm. Ödəniş çekini göndərirəm.';
    final phone = contact.phone;
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            const Text('Abunə olmaq üçün əlaqə saxlayın',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: navy)),
            const SizedBox(height: 12),
            StatTile(label: 'Seçilmiş paket', value: '${plan.name} · ${formatMoney(plan.price)}'),
            const SizedBox(height: 16),
            if (phone != null) ...[
              Text('Administratorun nömrəsi', textAlign: TextAlign.center, style: TextStyle(color: Colors.grey.shade600)),
              SelectableText(phone,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w700, color: navy)),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: () => _open(Uri(scheme: 'tel', path: phone.replaceAll(RegExp(r'[^+\d]'), ''))),
                icon: const Icon(Icons.call),
                label: const Text('Zəng et'),
              ),
              if (contact.whatsapp != null) ...[
                const SizedBox(height: 8),
                FilledButton.icon(
                  style: FilledButton.styleFrom(backgroundColor: Colors.green.shade600),
                  onPressed: () => _open(Uri.https('wa.me', '/${contact.whatsapp}', {'text': message})),
                  icon: const Icon(Icons.chat),
                  label: const Text('WhatsApp ilə yaz'),
                ),
              ],
              const SizedBox(height: 8),
              OutlinedButton.icon(
                onPressed: () async {
                  await Clipboard.setData(ClipboardData(text: phone));
                  if (ctx.mounted) {
                    ScaffoldMessenger.of(ctx).showSnackBar(const SnackBar(content: Text('Nömrə kopyalandı')));
                  }
                },
                icon: const Icon(Icons.copy),
                label: const Text('Nömrəni kopyala'),
              ),
            ] else
              const ErrorBanner(
                  'Administratorun nömrəsi hələ təyin edilməyib. Sorğunuz qeydə alındı — sizinlə əlaqə saxlanılacaq.'),
            const SizedBox(height: 16),
            Text(
              '1. Administratorla əlaqə saxlayın və ödənişi edin.\n'
              '2. Ödəniş çekini WhatsApp ilə göndərin.\n'
              '3. Administrator çeki yoxlayıb abunəliyinizi aktiv edəcək — sayğac avtomatik başlayacaq.',
              style: TextStyle(color: Colors.grey.shade700, height: 1.5),
            ),
          ]),
        ),
      ),
    );
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
