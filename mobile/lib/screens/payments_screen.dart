import 'package:flutter/material.dart';

import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import 'common.dart';

/// Payment history across all sections the user may see, with method filter and text search.
class PaymentsScreen extends StatefulWidget {
  const PaymentsScreen({super.key, required this.session});

  final Session session;

  @override
  State<PaymentsScreen> createState() => _PaymentsScreenState();
}

class _PaymentsScreenState extends State<PaymentsScreen> {
  String _method = '';
  String _query = '';
  late Future<List<Payment>> _items = _fetch();

  Future<List<Payment>> _fetch() {
    final query = <String, String>{'limit': '200'};
    if (_method.isNotEmpty) query['method'] = _method;
    return widget.session.api.get('/api/payments', query: query).then((j) => (j as List).map((e) => Payment.fromJson(e as Map<String, dynamic>)).toList());
  }

  void _reload() => setState(() => _items = _fetch());

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Ödənişlər')),
      body: Column(children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
          child: TextField(
            decoration: const InputDecoration(hintText: 'Ad üzrə axtar', prefixIcon: Icon(Icons.search)),
            onChanged: (v) => setState(() => _query = v.trim().toLowerCase()),
          ),
        ),
        SizedBox(
          height: 44,
          child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 16), children: [
            for (final m in const [('', 'Hamısı'), ('CASH', 'Nağd'), ('CARD', 'Kart'), ('TRANSFER', 'Köçürmə'), ('OTHER', 'Digər')])
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: ChoiceChip(
                  label: Text(m.$2),
                  selected: _method == m.$1,
                  onSelected: (_) {
                    _method = m.$1;
                    _reload();
                  },
                ),
              ),
          ]),
        ),
        Expanded(
          child: FutureBuilder<List<Payment>>(
            future: _items,
            builder: (context, snap) {
              if (snap.hasError) return Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!)));
              if (!snap.hasData) return const Center(child: CircularProgressIndicator());
              final visible = snap.data!.where((p) => _query.isEmpty || p.partyName.toLowerCase().contains(_query)).toList();
              if (visible.isEmpty) return const EmptyState(title: 'Ödəniş tapılmadı');
              final total = visible.fold<double>(0, (s, p) => s + p.amount);
              return RefreshIndicator(
                onRefresh: () async => _reload(),
                child: ListView.builder(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
                  itemCount: visible.length + 1,
                  itemBuilder: (context, i) {
                    if (i == 0) {
                      return Padding(
                        padding: const EdgeInsets.only(bottom: 10),
                        child: Text('Cəmi: ${formatMoney(total)}', style: const TextStyle(fontWeight: FontWeight.w700)),
                      );
                    }
                    final p = visible[i - 1];
                    return Card(
                      child: ListTile(
                        leading: CircleAvatar(
                          backgroundColor: _methodColor(p.paymentMethod).shade50,
                          child: Icon(_methodIcon(p.paymentMethod), color: _methodColor(p.paymentMethod)),
                        ),
                        title: Text(p.partyName),
                        subtitle: Text('${formatDateTime(p.paidAt)} · ${methodLabels[p.paymentMethod]}${p.description == null ? '' : '\n${p.description}'}'),
                        isThreeLine: p.description != null,
                        trailing: Text(formatMoney(p.amount), style: TextStyle(fontWeight: FontWeight.w700, color: Colors.green.shade700)),
                      ),
                    );
                  },
                ),
              );
            },
          ),
        ),
      ]),
    );
  }
}

MaterialColor _methodColor(String method) => switch (method) {
      'CASH' => Colors.green,
      'CARD' => Colors.blue,
      'TRANSFER' => Colors.amber,
      _ => Colors.grey,
    };

IconData _methodIcon(String method) => switch (method) {
      'CASH' => Icons.money,
      'CARD' => Icons.credit_card,
      'TRANSFER' => Icons.swap_horiz,
      _ => Icons.more_horiz,
    };
