import 'package:flutter/material.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

double _n(Object? v) => (v as num?)?.toDouble() ?? 0;

const _roles = {'OWNER': 'Sahib', 'CASHIER': 'Kassir', 'STOREKEEPER': 'Anbardar', 'ACCOUNTANT': 'Mühasib', 'SELLER': 'Satıcı', 'OTHER': 'Digər'};

/// Kassir hesabatı: per worker sales, discounts, returns, cancelled sales and shift shortages for a period.
class CashierReportScreen extends StatelessWidget {
  const CashierReportScreen({super.key, required this.session, required this.from, required this.to});

  final Session session;
  final DateTime from;
  final DateTime to;

  String _iso(DateTime d) => d.toIso8601String().substring(0, 10);

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Kassir hesabatı')),
        body: FutureBuilder<dynamic>(
          future: session.api.get('/api/reports/cashiers', query: {'from': _iso(from), 'to': _iso(to)}),
          builder: (context, snap) {
            if (snap.hasError) return ListView(children: [ErrorBanner(errorMessage(snap.error!))]);
            if (!snap.hasData) return const Center(child: CircularProgressIndicator());
            final rows = ((snap.data as Map)['rows'] as List).cast<Map<String, dynamic>>();
            if (rows.isEmpty) return ListView(children: const [EmptyState(title: 'Bu dövrdə məlumat yoxdur')]);
            return ListView(padding: const EdgeInsets.all(12), children: [
              Text(from == to ? formatDate(from) : '${formatDate(from)} — ${formatDate(to)}', style: TextStyle(color: Colors.grey.shade600)),
              const SizedBox(height: 8),
              for (final r in rows)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Row(children: [
                        Expanded(
                          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            Text('${r['name']}', style: const TextStyle(fontWeight: FontWeight.w800, color: navy, fontSize: 16)),
                            Text(_roles[r['jobTitle']] ?? 'İşçi', style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
                          ]),
                        ),
                        Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
                          Text(formatMoney(_n(r['salesTotal'])), style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
                          Text('${r['salesCount']} çek · orta ${formatMoney(_n(r['averageSale']))}', style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
                        ]),
                      ]),
                      const Divider(),
                      _line('Nağd / Kart / Nisyə', '${formatMoney(_n(r['paidCash']))} / ${formatMoney(_n(r['paidCard']))} / ${formatMoney(_n(r['credit']))}'),
                      if (_n(r['discount']) > 0) _line('Verilən endirim', formatMoney(_n(r['discount']))),
                      if (_n(r['returnsCount']) > 0) _line('Qaytarma (${r['returnsCount']})', formatMoney(_n(r['returnsTotal']))),
                      if (_n(r['cancelledCount']) > 0)
                        _line('Ləğv edilən satış (${r['cancelledCount']})', formatMoney(_n(r['cancelledTotal'])), color: Colors.red.shade700),
                      if (_n(r['shiftShortage']) > 0) _line('Növbə əskiyi', '−${formatMoney(_n(r['shiftShortage']))}', color: Colors.red.shade700),
                      if (_n(r['shiftSurplus']) > 0) _line('Növbə artığı', '+${formatMoney(_n(r['shiftSurplus']))}', color: Colors.orange.shade800),
                    ]),
                  ),
                ),
            ]);
          },
        ),
      );

  Widget _line(String label, String value, {Color? color}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Row(children: [
          Expanded(child: Text(label, style: TextStyle(color: color ?? Colors.grey.shade700))),
          Text(value, style: TextStyle(fontWeight: FontWeight.w600, color: color)),
        ]),
      );
}
