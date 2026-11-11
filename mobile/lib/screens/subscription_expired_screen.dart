import 'dart:async';

import 'package:flutter/material.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';
import 'contact_screen.dart';
import 'subscription_screen.dart';

/// Shown instead of the app while the store's subscription has ended. The server blocks every business section
/// in that state; data is kept. It re-checks the status every minute and when the app comes back to the
/// foreground, so the app unlocks by itself once the administrator approves a payment.
class SubscriptionExpiredScreen extends StatefulWidget {
  const SubscriptionExpiredScreen({super.key, required this.session});

  final Session session;

  @override
  State<SubscriptionExpiredScreen> createState() => _SubscriptionExpiredScreenState();
}

class _SubscriptionExpiredScreenState extends State<SubscriptionExpiredScreen> with WidgetsBindingObserver {
  Timer? _timer;
  bool _checking = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _timer = Timer.periodic(const Duration(minutes: 1), (_) => _check(silent: true));
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _check(silent: true);
  }

  Future<void> _check({bool silent = false}) async {
    if (_checking) return;
    setState(() => _checking = true);
    try {
      await widget.session.refresh(); // when active again, the app root swaps this screen for the home screen
      if (!silent && mounted && widget.session.subscriptionBlocked) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Abunəlik hələ aktiv deyil')));
      }
    } catch (e) {
      if (!silent && mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _checking = false);
    }
  }

  Future<void> _renew() async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => SubscriptionScreen(session: widget.session)));
    if (mounted) await _check(silent: true);
  }

  @override
  Widget build(BuildContext context) {
    final me = widget.session.me!;
    final sub = me.subscription;
    return Scaffold(
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _check,
          child: ListView(padding: const EdgeInsets.fromLTRB(24, 48, 24, 24), children: [
            Center(
              child: Container(
                padding: const EdgeInsets.all(18),
                decoration: BoxDecoration(color: Colors.red.shade50, shape: BoxShape.circle),
                child: Icon(Icons.lock_clock_outlined, size: 48, color: Colors.red.shade700),
              ),
            ),
            const SizedBox(height: 20),
            const Text('Abunəliyin müddəti bitib',
                textAlign: TextAlign.center, style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: navy)),
            const SizedBox(height: 8),
            Text(
              [
                if (me.storeName != null && me.storeName!.isNotEmpty) '"${me.storeName}" mağazası',
                if (sub?.endDate != null) 'Son aktiv gün: ${formatDate(sub!.endDate)}',
              ].join('\n'),
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.grey.shade700),
            ),
            const SizedBox(height: 16),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Text(
                  me.isOwner
                      ? 'Borclar, satış, anbar, kassa və digər bölmələr abunəlik yenilənənə qədər bağlıdır. '
                          'Məlumatlarınız silinmir — ödəniş təsdiqlənən kimi hər şey əvvəlki kimi açılacaq.'
                      : 'Mağazanın abunəliyi bitib. Bölmələr mağaza sahibi abunəliyi yeniləyənə qədər bağlıdır. '
                          'Məlumatlar silinmir.',
                  style: const TextStyle(height: 1.4),
                ),
              ),
            ),
            const SizedBox(height: 16),
            if (me.isOwner)
              FilledButton.icon(
                style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(50)),
                onPressed: _renew,
                icon: const Icon(Icons.workspace_premium_outlined),
                label: const Text('Abunəliyi yenilə'),
              ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(46)),
              onPressed: _checking ? null : () => _check(),
              icon: _checking
                  ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.refresh),
              label: const Text('Statusu yoxla'),
            ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(46)),
              onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ContactScreen(session: widget.session))),
              icon: const Icon(Icons.support_agent),
              label: const Text('Əlaqə / Dəstək'),
            ),
            const SizedBox(height: 16),
            TextButton.icon(onPressed: widget.session.logout, icon: const Icon(Icons.logout), label: const Text('Çıxış')),
          ]),
        ),
      ),
    );
  }
}
