import 'dart:async';

import 'package:flutter/material.dart';

import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';
import 'employees_screen.dart';
import 'notifications_screen.dart';
import 'parties_screen.dart';
import 'profile_screen.dart';
import 'subscription_screen.dart';

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
        if (me.isOwner)
          Card(
            child: ListTile(
              leading: const Icon(Icons.badge_outlined, color: navy),
              title: const Text('İşçilər'),
              subtitle: const Text('Əlavə et, icazələr, şifrə sıfırlama'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => EmployeesScreen(session: session))),
            ),
          ),
        if (me.can('CUSTOMER_DEBTS'))
          Card(
            child: ListTile(
              leading: const Icon(Icons.people_alt_outlined, color: navy),
              title: const Text('Müştərilər'),
              subtitle: const Text('Əlavə et, redaktə et, axtar'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context)
                  .push(MaterialPageRoute(builder: (_) => PartiesScreen(session: session, isCustomer: true))),
            ),
          ),
        if (me.can('COMPANY_RECEIVABLES') || me.can('COMPANY_PAYABLES'))
          Card(
            child: ListTile(
              leading: const Icon(Icons.business_outlined, color: navy),
              title: const Text('Şirkətlər'),
              subtitle: const Text('Əlavə et, redaktə et, axtar'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context)
                  .push(MaterialPageRoute(builder: (_) => PartiesScreen(session: session, isCustomer: false))),
            ),
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
