import 'package:flutter/material.dart';

import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import 'common.dart';
import 'finance_cards.dart';
import 'notifications_screen.dart';

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
                FinanceCards(key: _cards, session: widget.session),
                const SizedBox(height: 22),
                const Text('Ödəniş üsulları', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                GridView.count(
                  crossAxisCount: 2,
                  mainAxisSpacing: 12,
                  crossAxisSpacing: 12,
                  childAspectRatio: 1.5,
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  children: [
                    StatTile(label: 'Müştəri borcları', value: formatMoney(s.customerDebts)),
                    StatTile(label: 'Şirkət alacaqları', value: formatMoney(s.companyReceivables)),
                    StatTile(label: 'Şirkət borcları', value: formatMoney(s.companyPayables)),
                    StatTile(label: 'Bu gün', value: formatMoney(s.todayTotal), hint: 'Bu ay: ${formatMoney(s.monthTotal)}'),
                  ],
                ),
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
