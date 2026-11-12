import 'package:flutter/material.dart';

import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import 'cash_screen.dart';
import 'common.dart';
import 'expenses_screen.dart';
import 'finance_cards.dart';
import 'inventory_screen.dart';
import 'notifications_screen.dart';
import 'reports_screen.dart';
import 'sales_screen.dart';

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key, required this.session});

  final Session session;

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  late Future<Map<String, dynamic>> _data;
  final _cards = GlobalKey<FinanceCardsState>();

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _data = widget.session.api.get('/api/dashboard').then((j) => j as Map<String, dynamic>);
  }

  Future<void> _refresh() async {
    _cards.currentState?.reload();
    setState(_load);
    await _data;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.session.me?.storeName ?? 'Panel'),
        actions: [
          IconButton(
            tooltip: 'Bildirişlər',
            icon: const Icon(Icons.notifications_none),
            onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => NotificationsScreen(session: widget.session))),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<Map<String, dynamic>>(
          future: _data,
          builder: (context, snap) {
            if (snap.hasError) {
              return ListView(children: [Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!)))]);
            }
            if (!snap.hasData) return const Center(child: CircularProgressIndicator());
            final s = DashboardSummary.fromJson(snap.data!['summary'] as Map<String, dynamic>);
            final recent = (snap.data!['recentTransactions'] as List).map((e) => Payment.fromJson(e as Map<String, dynamic>)).toList();
            return ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _QuickActions(session: widget.session),
                FinanceCards(key: _cards, session: widget.session),
                const SizedBox(height: 22),
                const Text('Alınan ödənişlər', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                Row(children: [
                  Expanded(child: StatTile(label: 'Bu gün', value: formatMoney(s.todayTotal))),
                  const SizedBox(width: 12),
                  Expanded(child: StatTile(label: 'Bu ay', value: formatMoney(s.monthTotal))),
                ]),
                const SizedBox(height: 12),
                Row(children: [
                  Expanded(child: _MiniCard(label: 'Nağd (cəmi)', value: formatMoney(s.cashReceived))),
                  const SizedBox(width: 12),
                  Expanded(child: _MiniCard(label: 'Kart (cəmi)', value: formatMoney(s.cardReceived))),
                  const SizedBox(width: 12),
                  Expanded(child: _MiniCard(label: 'Köçürmə/digər', value: formatMoney(s.otherReceived))),
                ]),
                const SizedBox(height: 24),
                const Text('Son əməliyyatlar', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                if (recent.isEmpty)
                  const EmptyState(title: 'Hələ ödəniş yoxdur')
                else
                  Column(
                    children: [
                      for (final p in recent)
                        Card(
                          child: ListTile(
                            title: Text(p.partyName),
                            subtitle: Text('${formatDateTime(p.paidAt)} · ${methodLabels[p.paymentMethod]}'),
                            trailing: Text(formatMoney(p.amount), style: const TextStyle(fontWeight: FontWeight.w700)),
                          ),
                        ),
                    ],
                  ),
              ],
            );
          },
        ),
      ),
    );
  }
}

/// Shortcuts to the ERP sections (also listed under "Daha"), filtered by the user's permissions.
class _QuickActions extends StatelessWidget {
  const _QuickActions({required this.session});

  final Session session;

  @override
  Widget build(BuildContext context) {
    final me = session.me!;
    final items = <({String label, IconData icon, Widget Function() page})>[
      if (me.can('REPORTS')) (label: 'Hesabat', icon: Icons.insights_outlined, page: () => ReportsScreen(session: session)),
      if (me.can('SALES')) (label: 'Satış', icon: Icons.point_of_sale, page: () => SalesScreen(session: session)),
      if (me.can('INVENTORY') || me.can('SALES'))
        (label: 'Anbar', icon: Icons.inventory_2_outlined, page: () => InventoryScreen(session: session)),
      if (me.can('CASH')) (label: 'Kassa', icon: Icons.account_balance_wallet_outlined, page: () => CashScreen(session: session)),
      if (me.can('EXPENSES')) (label: 'Xərclər', icon: Icons.receipt_long_outlined, page: () => ExpensesScreen(session: session)),
    ];
    if (items.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Row(children: [
        for (final (i, item) in items.indexed) ...[
          if (i > 0) const SizedBox(width: 8),
          Expanded(
            child: Card(
              margin: EdgeInsets.zero,
              child: InkWell(
                borderRadius: BorderRadius.circular(12),
                onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => item.page())),
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  child: Column(children: [
                    Icon(item.icon, color: navy, size: 28),
                    const SizedBox(height: 6),
                    FittedBox(
                      fit: BoxFit.scaleDown,
                      child: Text(item.label, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
                    ),
                  ]),
                ),
              ),
            ),
          ),
        ],
      ]),
    );
  }
}

class _MiniCard extends StatelessWidget {
  const _MiniCard({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label, style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
              const SizedBox(height: 4),
              FittedBox(fit: BoxFit.scaleDown, alignment: Alignment.centerLeft, child: Text(value, style: const TextStyle(fontWeight: FontWeight.w700))),
            ],
          ),
        ),
      );
}
