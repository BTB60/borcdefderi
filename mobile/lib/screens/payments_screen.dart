import 'package:flutter/material.dart';

import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import 'common.dart';
import 'swipe_actions.dart';
import 'payment_edit_sheet.dart';

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
    final query = <String, String>{'limit': '200', 'includeVoided': 'true'};
    if (_method.isNotEmpty) query['method'] = _method;
    return widget.session.api.get('/api/payments', query: query).then((j) => (j as List).map((e) => Payment.fromJson(e as Map<String, dynamic>)).toList());
  }

  void _reload() => setState(() => _items = _fetch());


  /// Owner only: edit or cancel one payment, then reload the list.
  Future<void> _actions(Payment p, [String? action]) async {
    final changed = await showPaymentActions(context, widget.session, p, action: action);
    if (changed == null || !mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(changed.isVoided ? 'Ödəniş ləğv edildi' : 'Ödəniş düzəldildi')));
    _reload();
  }
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
              final total = visible.where((p) => !p.isVoided).fold<double>(0, (s, p) => s + p.amount);
              final me = widget.session.me!;
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
                    final voided = p.isVoided;
                    final extra = voided
                        ? 'Ləğv edildi${p.voidReason == null ? '' : ': ${p.voidReason}'}'
                        : [if (p.description != null) p.description!, if (p.editedAt != null) 'düzəldilib'].join(' · ');
                    return Card(
                      color: voided ? voidedCard : null,
                      clipBehavior: Clip.antiAlias,
                      child: SwipeActions(
                        actions: [
                          if (me.mayChange(p.recordedBy) && !voided) ...[
                            SwipeAction.edit(() => _actions(p, 'edit')),
                            SwipeAction.delete(() => _actions(p, 'cancel')),
                          ],
                        ],
                        child: ListTile(
                        leading: CircleAvatar(
                          backgroundColor: voided ? voidedBg : _methodColor(p.paymentMethod).shade50,
                          child: Icon(voided ? voidedIcon : _methodIcon(p.paymentMethod),
                              color: voided ? voidedColor : _methodColor(p.paymentMethod)),
                        ),
                        title: Text(p.partyName,
                            style: voided ? TextStyle(color: Colors.grey.shade600, decoration: TextDecoration.lineThrough) : null),
                        subtitle: Text('${formatDateTime(p.paidAt)} · ${methodLabels[p.paymentMethod]}${extra.isEmpty ? '' : '\n$extra'}'),
                        isThreeLine: extra.isNotEmpty,
                        trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                          Text(formatMoney(p.amount),
                              style: TextStyle(
                                  fontWeight: FontWeight.w700,
                                  color: voided ? voidedColor : Colors.green.shade700,
                                  decoration: voided ? TextDecoration.lineThrough : null)),
                          if (me.mayChange(p.recordedBy) && !voided)
                            IconButton(
                              tooltip: 'Düzəlt / ləğv et',
                              icon: const Icon(Icons.more_vert),
                              onPressed: () => _actions(p),
                            ),
                        ]),
                        onTap: me.mayChange(p.recordedBy) && !voided ? () => _actions(p) : null,
                      ),
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
