import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import '../whatsapp.dart';
import 'common.dart';

double _n(Object? v) => (v as num?)?.toDouble() ?? 0;

/// The day in a WhatsApp message (same text as on the web).
String daySummaryText(Map<String, dynamic> s) {
  final l = <String>[
    '📊 ${s['storeName']} — gün sonu (${formatDate(DateTime.tryParse('${s['date']}'))})',
    '',
    '🛒 Satış: ${formatMoney(_n(s['salesTotal']))} (${s['salesCount']} çek)',
    '   Nağd ${formatMoney(_n(s['paidCash']))} · Kart ${formatMoney(_n(s['paidCard']))} · Nisyə ${formatMoney(_n(s['credit']))}',
    '💰 Mənfəət (təxmini): ${formatMoney(_n(s['grossProfit']))}',
  ];
  if (_n(s['returnsCount']) > 0) l.add('↩️ Qaytarma: ${formatMoney(_n(s['returnsTotal']))} (${s['returnsCount']})');
  if (_n(s['expensesTotal']) > 0) l.add('🧾 Xərc: ${formatMoney(_n(s['expensesTotal']))}');
  if (_n(s['debtsCollected']) > 0) l.add('✅ Yığılan borc: ${formatMoney(_n(s['debtsCollected']))}');
  if (_n(s['supplierPaid']) > 0) l.add('🚚 Tədarükçüyə ödənilib: ${formatMoney(_n(s['supplierPaid']))}');
  final top = (s['topProducts'] as List? ?? const []).cast<Map<String, dynamic>>();
  if (top.isNotEmpty) {
    l.addAll(['', '⭐ Ən çox satılan:']);
    for (var i = 0; i < top.length; i++) {
      l.add('   ${i + 1}. ${top[i]['name']} — ${top[i]['quantity']} əd. · ${formatMoney(_n(top[i]['total']))}');
    }
  }
  final acc = (s['accounts'] as List? ?? const []).cast<Map<String, dynamic>>();
  if (acc.isNotEmpty) {
    l.addAll(['', '🏦 Hazırda:']);
    for (final a in acc) {
      l.add('   ${a['name']}: ${formatMoney(_n(a['balance']))}');
    }
  }
  l.addAll(['', 'HesabPro']);
  return l.join('\n');
}

/// Gün sonu hesabatı: the day's numbers, sent to the owner's own WhatsApp (or copied).
Future<void> showDaySummary(BuildContext context, Session session) => showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (ctx) => _DaySummarySheet(session: session),
    );

class _DaySummarySheet extends StatefulWidget {
  const _DaySummarySheet({required this.session});

  final Session session;

  @override
  State<_DaySummarySheet> createState() => _DaySummarySheetState();
}

class _DaySummarySheetState extends State<_DaySummarySheet> {
  DateTime _day = DateTime.now();
  late Future<dynamic> _data = _load();

  Future<dynamic> _load() =>
      widget.session.api.get('/api/reports/day-summary', query: {'date': _day.toIso8601String().substring(0, 10)});

  @override
  Widget build(BuildContext context) => FutureBuilder<dynamic>(
        future: _data,
        builder: (context, snap) {
          final text = snap.hasData ? daySummaryText(snap.data as Map<String, dynamic>) : '';
          return ListView(shrinkWrap: true, padding: EdgeInsets.fromLTRB(20, 0, 20, 20 + sheetBottomInset(context)), children: [
            Row(children: [
              const Expanded(child: Text('Gün sonu hesabatı', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: navy))),
              TextButton.icon(
                icon: const Icon(Icons.event_outlined, size: 18),
                label: Text(formatDate(_day)),
                onPressed: () async {
                  final d = await showDatePicker(context: context, initialDate: _day, firstDate: DateTime(2024), lastDate: DateTime.now());
                  if (d != null) setState(() { _day = d; _data = _load(); });
                },
              ),
            ]),
            const SizedBox(height: 8),
            if (snap.hasError) ErrorBanner(errorMessage(snap.error!))
            else if (!snap.hasData) const Padding(padding: EdgeInsets.all(32), child: Center(child: CircularProgressIndicator()))
            else ...[
              Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(color: Colors.grey.shade100, borderRadius: BorderRadius.circular(12)),
                child: Text(text, style: const TextStyle(height: 1.4)),
              ),
              const SizedBox(height: 12),
              Row(children: [
                Expanded(
                  child: OutlinedButton.icon(
                    icon: const Icon(Icons.copy, size: 18),
                    label: const Text('Kopyala'),
                    onPressed: () {
                      Clipboard.setData(ClipboardData(text: text));
                      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Kopyalandı')));
                    },
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: FilledButton.icon(
                    style: FilledButton.styleFrom(backgroundColor: const Color(0xFF25D366)),
                    icon: const Icon(Icons.chat, size: 18),
                    label: const Text('WhatsApp'),
                    onPressed: () => launchUrl(
                      waUri(widget.session.me?.phone, text) ?? Uri.parse('https://wa.me/?text=${Uri.encodeComponent(text)}'),
                      mode: LaunchMode.externalApplication,
                    ),
                  ),
                ),
              ]),
            ],
          ]);
        },
      );
}
