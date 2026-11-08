import 'package:flutter/material.dart';

import '../state/session.dart';
import 'dashboard_screen.dart';
import 'debts_screen.dart';
import 'more_screen.dart';
import 'payments_screen.dart';

/// Bottom navigation. Tabs follow the signed-in user's permissions; the server enforces them anyway.
class HomeShell extends StatefulWidget {
  const HomeShell({super.key, required this.session});

  final Session session;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    final me = widget.session.me!;
    final showDashboard = me.isOwner || me.can('REPORTS');
    final tabs = <({String label, IconData icon, Widget page})>[
      if (showDashboard) (label: 'Panel', icon: Icons.dashboard_outlined, page: DashboardScreen(session: widget.session)),
      (label: 'Borclar', icon: Icons.account_balance_wallet_outlined, page: DebtSectionsScreen(session: widget.session)),
      (label: 'Ödənişlər', icon: Icons.payments_outlined, page: PaymentsScreen(session: widget.session)),
      (label: 'Daha', icon: Icons.more_horiz, page: MoreScreen(session: widget.session)),
    ];
    final safeIndex = _index.clamp(0, tabs.length - 1);
    return Scaffold(
      body: IndexedStack(index: safeIndex, children: [for (final t in tabs) t.page]),
      bottomNavigationBar: NavigationBar(
        selectedIndex: safeIndex,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: [
          for (final t in tabs) NavigationDestination(icon: Icon(t.icon), label: t.label),
        ],
      ),
    );
  }
}
