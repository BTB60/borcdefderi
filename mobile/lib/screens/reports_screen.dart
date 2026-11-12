import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../api/api_client.dart';
import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

double _n(Object? v) => v == null ? 0 : (v as num).toDouble();

/// Profit and loss for a period (server: /api/finance/pnl), the last six months and best-selling products.
/// Requires the REPORTS permission.
class ReportsScreen extends StatefulWidget {
  const ReportsScreen({super.key, required this.session});

  final Session session;

  @override
  State<ReportsScreen> createState() => _ReportsScreenState();
}

enum _Period { today, week, month, lastMonth, year, custom }

const _periodLabels = {
  _Period.today: 'Bu gün',
  _Period.week: 'Bu həftə',
  _Period.month: 'Bu ay',
  _Period.lastMonth: 'Keçən ay',
  _Period.year: 'Bu il',
  _Period.custom: 'Tarix seç',
};

class _ReportsScreenState extends State<ReportsScreen> {
  _Period _period = _Period.month;
  late DateTime _from;
  late DateTime _to;
  late Future<_ReportData> _data;

  @override
  void initState() {
    super.initState();
    _setPeriod(_Period.month, fetch: false);
    _data = _load();
  }

  static String _iso(DateTime d) => DateFormat('yyyy-MM-dd').format(d);

  void _setPeriod(_Period p, {bool fetch = true}) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    _period = p;
    switch (p) {
      case _Period.today:
        _from = today;
        _to = today;
      case _Period.week:
        _from = today.subtract(Duration(days: today.weekday - 1));
        _to = today;
      case _Period.month:
        _from = DateTime(today.year, today.month, 1);
        _to = today;
      case _Period.lastMonth:
        _from = DateTime(today.year, today.month - 1, 1);
        _to = DateTime(today.year, today.month, 0);
      case _Period.year:
        _from = DateTime(today.year, 1, 1);
        _to = today;
      case _Period.custom:
        break;
    }
    if (fetch) setState(() => _data = _load());
  }

  Future<void> _pickRange() async {
    final r = await showDateRangePicker(
      context: context,
      firstDate: DateTime(2020),
      lastDate: DateTime.now(),
      initialDateRange: DateTimeRange(start: _from, end: _to),
      locale: const Locale('az'),
    );
    if (r == null) return;
    _from = r.start;
    _to = r.end;
    _setPeriod(_Period.custom);
  }

  Future<_ReportData> _load() async {
    final api = widget.session.api;
    final range = {'from': _iso(_from), 'to': _iso(_to)};
    final results = await Future.wait([
      api.get('/api/finance/pnl', query: range),
      api.get('/api/finance/monthly', query: {'months': '6'}),
      // Older servers have no top-products endpoint yet: the section is simply hidden then.
      api.get('/api/finance/top-products', query: {...range, 'limit': '10'}).catchError((Object e) {
        if (e is ApiException && e.status == 404) return null;
        throw e;
      }),
    ]);
    return _ReportData(
      pnl: _Pnl.fromJson(results[0] as Map<String, dynamic>),
      months: (results[1] as List).map((e) => _MonthPoint.fromJson(e as Map<String, dynamic>)).toList(),
      top: (results[2] as List?)?.map((e) => _TopProduct.fromJson(e as Map<String, dynamic>)).toList(),
    );
  }

  Future<void> _refresh() async {
    setState(() => _data = _load());
    await _data.catchError((_) => _emptyData);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Hesabat')),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Wrap(spacing: 8, runSpacing: 8, children: [
              for (final p in _Period.values)
                ChoiceChip(
                  label: Text(_periodLabels[p]!),
                  selected: _period == p,
                  onSelected: (_) => p == _Period.custom ? _pickRange() : _setPeriod(p),
                ),
            ]),
            const SizedBox(height: 8),
            Text(
              _from == _to ? formatDate(_from) : '${formatDate(_from)} — ${formatDate(_to)}',
              style: TextStyle(color: Colors.grey.shade600, fontSize: 13),
            ),
            const SizedBox(height: 12),
            FutureBuilder<_ReportData>(
              future: _data,
              builder: (context, snap) {
                if (snap.connectionState != ConnectionState.done) {
                  return const Padding(padding: EdgeInsets.all(48), child: Center(child: CircularProgressIndicator()));
                }
                if (snap.hasError) return ErrorBanner(errorMessage(snap.error!));
                return _ReportBody(data: snap.data!);
              },
            ),
          ],
        ),
      ),
    );
  }
}

final _emptyData = _ReportData(pnl: _Pnl.fromJson(const {}), months: const [], top: null);

class _ReportBody extends StatelessWidget {
  const _ReportBody({required this.data});

  final _ReportData data;

  @override
  Widget build(BuildContext context) {
    final p = data.pnl;
    final avgReceipt = p.saleCount == 0 ? 0.0 : p.netSales / p.saleCount;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      GridView.count(
        crossAxisCount: 2,
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
        childAspectRatio: 1.45,
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        children: [
          StatTile(label: 'Xalis satış', value: formatMoney(p.netSales), hint: _change(p.netSales, p.prevNetSales)),
          StatTile(label: 'Xalis mənfəət', value: formatMoney(p.netResult), hint: _change(p.netResult, p.prevNetResult)),
          StatTile(label: 'Ümumi mənfəət', value: formatMoney(p.grossProfit), hint: _margin(p.grossProfit, p.netSales)),
          StatTile(label: 'Xərclər', value: formatMoney(p.expenses), hint: _change(p.expenses, p.prevExpenses)),
        ],
      ),
      const SizedBox(height: 16),
      _Section(title: 'Mənfəət hesablanması', rows: [
        _Row('Satış', p.sales),
        _Row('Qaytarma', -p.returns),
        _Row('Xalis satış', p.netSales, bold: true),
        _Row('Malın maya dəyəri', -p.costOfGoods),
        _Row('Ümumi mənfəət', p.grossProfit, bold: true),
        _Row('Xərclər', -p.expenses),
        _Row('Xalis mənfəət', p.netResult, bold: true, colored: true),
      ]),
      const SizedBox(height: 12),
      _Section(title: 'Satış və pul axını', rows: [
        _Row.text('Satış sayı', '${p.saleCount}'),
        _Row('Orta çek', avgReceipt),
        _Row('Dərhal ödənilən (nağd + kart)', p.salesPaidNow),
        _Row('Nisyə satış', p.salesOnCredit),
        _Row('Borc yığımı', p.debtCollections),
        _Row('Tədarükçüyə ödəniş', p.supplierPayments),
      ]),
      const SizedBox(height: 4),
      Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4),
        child: Text('Borc yığımı və tədarükçü ödənişləri mənfəətə daxil edilmir.',
            style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
      ),
      if (data.months.isNotEmpty) ...[
        const SizedBox(height: 20),
        const Text('Son 6 ay', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        Card(
          margin: EdgeInsets.zero,
          child: Padding(padding: const EdgeInsets.fromLTRB(12, 16, 12, 12), child: _MonthlyChart(months: data.months)),
        ),
      ],
      if (data.top != null) ...[
        const SizedBox(height: 20),
        const Text('Ən çox satılan məhsullar', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        if (data.top!.isEmpty)
          const EmptyState(title: 'Bu dövrdə satış yoxdur')
        else
          Card(
            margin: EdgeInsets.zero,
            child: Column(children: [
              for (final (i, t) in data.top!.indexed) ...[
                if (i > 0) const Divider(height: 1),
                ListTile(
                  dense: true,
                  leading: CircleAvatar(
                    radius: 14,
                    backgroundColor: i < 3 ? gold : Colors.grey.shade200,
                    child: Text('${i + 1}', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: navy)),
                  ),
                  title: Text(t.name, maxLines: 1, overflow: TextOverflow.ellipsis),
                  subtitle: Text('${_qty(t.quantity)} ədəd · mənfəət ${formatMoney(t.profit)}'),
                  trailing: Text(formatMoney(t.revenue), style: const TextStyle(fontWeight: FontWeight.w700)),
                ),
              ],
            ]),
          ),
      ],
      const SizedBox(height: 24),
    ]);
  }

  static String _qty(double q) => q == q.roundToDouble() ? q.toStringAsFixed(0) : q.toStringAsFixed(2);

  static String? _change(double now, double before) {
    if (before == 0) return now == 0 ? null : 'Əvvəlki dövrdə 0';
    final pct = (now - before) / before.abs() * 100;
    final sign = pct >= 0 ? '+' : '';
    return '$sign${pct.toStringAsFixed(0)}% əvvəlki dövrə görə';
  }

  static String? _margin(double profit, double sales) =>
      sales == 0 ? null : 'Marja: ${(profit / sales * 100).toStringAsFixed(0)}%';
}

class _Row {
  const _Row(this.label, double this.value, {this.bold = false, this.colored = false}) : text = null;
  const _Row.text(this.label, String this.text)
      : value = null,
        bold = false,
        colored = false;

  final String label;
  final double? value;
  final String? text;
  final bool bold;
  final bool colored;
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.rows});

  final String title;
  final List<_Row> rows;

  @override
  Widget build(BuildContext context) => Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 14, 16, 10),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: const TextStyle(fontWeight: FontWeight.w700, color: navy)),
            const SizedBox(height: 6),
            for (final r in rows)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 5),
                child: Row(children: [
                  Expanded(
                    child: Text(r.label,
                        style: TextStyle(fontWeight: r.bold ? FontWeight.w700 : FontWeight.w400, color: Colors.grey.shade800)),
                  ),
                  Text(
                    r.text ?? formatMoney(r.value!),
                    style: TextStyle(
                      fontWeight: r.bold ? FontWeight.w700 : FontWeight.w500,
                      color: r.colored && r.value != null
                          ? (r.value! < 0 ? Colors.red.shade700 : Colors.green.shade700)
                          : navy,
                      fontFeatures: const [FontFeature.tabularFigures()],
                    ),
                  ),
                ]),
              ),
          ]),
        ),
      );
}

/// Bars: net sales per month (navy), with net result as a gold bar beside it.
class _MonthlyChart extends StatelessWidget {
  const _MonthlyChart({required this.months});

  final List<_MonthPoint> months;

  static const _monthNames = ['Yan', 'Fev', 'Mar', 'Apr', 'May', 'İyn', 'İyl', 'Avq', 'Sen', 'Okt', 'Noy', 'Dek'];

  @override
  Widget build(BuildContext context) {
    var maxV = 0.0;
    for (final m in months) {
      maxV = [maxV, m.netSales.abs(), m.netResult.abs()].reduce((a, b) => a > b ? a : b);
    }
    if (maxV == 0) maxV = 1;
    const barH = 140.0;
    return Column(children: [
      SizedBox(
        height: barH + 34,
        child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
          for (final m in months)
            Expanded(
              child: Column(mainAxisAlignment: MainAxisAlignment.end, children: [
                Text(_short(m.netSales), style: TextStyle(fontSize: 10, color: Colors.grey.shade700)),
                const SizedBox(height: 4),
                Row(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                  _bar(m.netSales / maxV * barH, navy),
                  const SizedBox(width: 3),
                  _bar(m.netResult.abs() / maxV * barH, m.netResult < 0 ? Colors.red.shade400 : gold),
                ]),
                const SizedBox(height: 6),
                Text(_label(m.month), style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600)),
              ]),
            ),
        ]),
      ),
      const SizedBox(height: 10),
      Row(mainAxisAlignment: MainAxisAlignment.center, children: [
        _legend(navy, 'Xalis satış'),
        const SizedBox(width: 16),
        _legend(gold, 'Xalis mənfəət'),
      ]),
    ]);
  }

  static Widget _bar(double h, Color c) => Container(
        width: 12,
        height: h < 2 ? 2 : h,
        decoration: BoxDecoration(color: c, borderRadius: const BorderRadius.vertical(top: Radius.circular(4))),
      );

  static Widget _legend(Color c, String text) => Row(mainAxisSize: MainAxisSize.min, children: [
        Container(width: 10, height: 10, decoration: BoxDecoration(color: c, borderRadius: BorderRadius.circular(2))),
        const SizedBox(width: 6),
        Text(text, style: TextStyle(fontSize: 12, color: Colors.grey.shade700)),
      ]);

  static String _label(String ym) {
    final parts = ym.split('-');
    final m = parts.length == 2 ? int.tryParse(parts[1]) : null;
    return m == null || m < 1 || m > 12 ? ym : _monthNames[m - 1];
  }

  static String _short(double v) {
    final a = v.abs();
    if (a >= 1000000) return '${(v / 1000000).toStringAsFixed(1)}M';
    if (a >= 1000) return '${(v / 1000).toStringAsFixed(1)}K';
    return v.toStringAsFixed(0);
  }
}

class _ReportData {
  const _ReportData({required this.pnl, required this.months, required this.top});

  final _Pnl pnl;
  final List<_MonthPoint> months;
  final List<_TopProduct>? top;
}

class _Pnl {
  const _Pnl({
    required this.sales,
    required this.returns,
    required this.netSales,
    required this.costOfGoods,
    required this.grossProfit,
    required this.expenses,
    required this.netResult,
    required this.saleCount,
    required this.salesPaidNow,
    required this.salesOnCredit,
    required this.debtCollections,
    required this.supplierPayments,
    required this.prevNetSales,
    required this.prevExpenses,
    required this.prevNetResult,
  });

  final double sales, returns, netSales, costOfGoods, grossProfit, expenses, netResult;
  final int saleCount;
  final double salesPaidNow, salesOnCredit, debtCollections, supplierPayments;
  final double prevNetSales, prevExpenses, prevNetResult;

  factory _Pnl.fromJson(Map<String, dynamic> j) {
    final prev = (j['previous'] as Map<String, dynamic>?) ?? const {};
    return _Pnl(
      sales: _n(j['sales']),
      returns: _n(j['returns']),
      netSales: _n(j['netSales']),
      costOfGoods: _n(j['costOfGoods']),
      grossProfit: _n(j['grossProfit']),
      expenses: _n(j['expenses']),
      netResult: _n(j['netResult']),
      saleCount: _n(j['saleCount']).toInt(),
      salesPaidNow: _n(j['salesPaidNow']),
      salesOnCredit: _n(j['salesOnCredit']),
      debtCollections: _n(j['debtCollections']),
      supplierPayments: _n(j['supplierPayments']),
      prevNetSales: _n(prev['netSales']),
      prevExpenses: _n(prev['expenses']),
      prevNetResult: _n(prev['netResult']),
    );
  }
}

class _MonthPoint {
  const _MonthPoint(this.month, this.netSales, this.netResult);

  final String month;
  final double netSales, netResult;

  factory _MonthPoint.fromJson(Map<String, dynamic> j) =>
      _MonthPoint(j['month'] as String? ?? '', _n(j['netSales']), _n(j['netResult']));
}

class _TopProduct {
  const _TopProduct(this.name, this.quantity, this.revenue, this.profit);

  final String name;
  final double quantity, revenue, profit;

  factory _TopProduct.fromJson(Map<String, dynamic> j) =>
      _TopProduct(j['name'] as String? ?? '', _n(j['quantity']), _n(j['revenue']), _n(j['profit']));
}
