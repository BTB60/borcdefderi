import 'package:flutter/material.dart';

import '../state/offline_sales.dart';
import '../state/session.dart';
import 'cash_screen.dart';
import 'dashboard_screen.dart';
import 'debts_screen.dart';
import 'deliveries_screen.dart';
import 'expenses_screen.dart';
import 'inventory_screen.dart';
import 'more_screen.dart';
import 'payments_screen.dart';
import 'sales_screen.dart';
import 'update_banner.dart';

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
  void initState() {
    super.initState();
    // sales made without internet are sent in the background as soon as the server can be reached
    if (widget.session.me?.can('SALES') ?? false) OfflineSales.of(widget.session);
  }

  @override
  Widget build(BuildContext context) {
    final me = widget.session.me!;
    final s = widget.session;
    final tabs = <({String label, IconData icon, Widget page})>[
      if (me.isOwner)
        // the owner's usual tabs, minus what the package leaves out (filled up with what it includes)
        ...[
          if (me.can('REPORTS')) (label: 'Panel', icon: Icons.dashboard_outlined, page: DashboardScreen(session: s)),
          if (me.canAnyDebt) (label: 'Borclar', icon: Icons.account_balance_wallet_outlined, page: DebtSectionsScreen(session: s)),
          if (me.canAnyDebt) (label: 'Ödənişlər', icon: Icons.payments_outlined, page: PaymentsScreen(session: s)),
          if (me.can('SALES')) (label: 'Satış', icon: Icons.point_of_sale_outlined, page: SalesScreen(session: s)),
          if (me.can('INVENTORY')) (label: 'Anbar', icon: Icons.inventory_2_outlined, page: InventoryScreen(session: s)),
          if (me.can('CASH')) (label: 'Kassa', icon: Icons.account_balance_outlined, page: CashScreen(session: s)),
        ].take(3)
      else
        // an employee sees only the sections their job / permissions open (at most four, the rest under "Daha")
        ...[
          if (me.can('REPORTS')) (label: 'Panel', icon: Icons.dashboard_outlined, page: DashboardScreen(session: s)),
          if (me.can('SALES')) (label: 'Satış', icon: Icons.point_of_sale_outlined, page: SalesScreen(session: s)),
          if (me.can('INVENTORY')) (label: 'Anbar', icon: Icons.inventory_2_outlined, page: InventoryScreen(session: s)),
          if (me.canAnyDebt) (label: 'Borclar', icon: Icons.account_balance_wallet_outlined, page: DebtSectionsScreen(session: s)),
          if (me.canAnyDebt) (label: 'Ödənişlər', icon: Icons.payments_outlined, page: PaymentsScreen(session: s)),
          if (me.can('CASH')) (label: 'Kassa', icon: Icons.account_balance_outlined, page: CashScreen(session: s)),
          if (me.can('EXPENSES')) (label: 'Xərclər', icon: Icons.receipt_long_outlined, page: ExpensesScreen(session: s)),
          // a courier opens the app on their deliveries
          if (me.jobTitle == 'COURIER' && !me.can('SALES'))
            (label: 'Çatdırılma', icon: Icons.local_shipping_outlined, page: DeliveriesScreen(session: s)),
        ].take(4),
      (label: 'Daha', icon: Icons.more_horiz, page: MoreScreen(session: s)),
    ];
    final safeIndex = _index.clamp(0, tabs.length - 1);
    return Scaffold(
      body: IndexedStack(index: safeIndex, children: [for (final t in tabs) t.page]),
      // "Yeni versiya var — Yenilə" sits right above the tabs when a newer APK is out
      bottomNavigationBar: Column(mainAxisSize: MainAxisSize.min, children: [
        const UpdateBanner(),
        NavigationBar(
          selectedIndex: safeIndex,
          onDestinationSelected: (i) => setState(() => _index = i),
          destinations: [
            for (final t in tabs) NavigationDestination(icon: Icon(t.icon), label: t.label),
          ],
        ),
      ]),
    );
  }
}
