import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import '../whatsapp.dart';
import 'admin_notes.dart';
import 'common.dart';

double _num(Object? v) => (v as num?)?.toDouble() ?? 0;
int _int(Object? v) => (v as num?)?.toInt() ?? 0;
DateTime? _date(Object? v) => v == null ? null : DateTime.tryParse('$v');

/// Parts of a store's data the admin can wipe one by one (the server pulls in what they depend on).
const _purgeParts = [
  ('STOCK', 'Anbar qalığı və hərəkətləri', 'məhsullar qalır, qalıq 0 olur'),
  ('PRODUCTS', 'Məhsullar və kateqoriyalar', 'anbar və satışlar da silinir'),
  ('SALES', 'Satışlar və qaytarmalar', 'mal anbara qayıdır, satış borcları çıxılır'),
  ('DEBTS', 'Borclar və ödənişlər', ''),
  ('CASH', 'Kassa əməliyyatları', 'hesablar qalır, balans 0 olur'),
  ('EXPENSES', 'Xərclər və büdcə', ''),
  ('CUSTOMERS', 'Müştərilər və şirkətlər', 'borclar və satışlar da silinir'),
  ('POCKET', 'Cib pulu', ''),
];

const _opTypes = {'SALE': 'Satış', 'DEBT': 'Borc', 'PAYMENT': 'Ödəniş'};

const _months = ['Yan', 'Fev', 'Mar', 'Apr', 'May', 'İyn', 'İyl', 'Avq', 'Sen', 'Okt', 'Noy', 'Dek'];

// ============================================================ overview

/// Platform dashboard on the phone: store counts, revenue, sign-ups and package mix.
class AdminOverviewScreen extends StatefulWidget {
  const AdminOverviewScreen({super.key, required this.session, required this.onOpenTab});

  final Session session;

  /// 1 = stores, 2 = payment requests, 3 = expiring.
  final ValueChanged<int> onOpenTab;

  @override
  State<AdminOverviewScreen> createState() => _AdminOverviewScreenState();
}

class _AdminOverviewScreenState extends State<AdminOverviewScreen> {
  late Future<Map<String, dynamic>> _data = _fetch();

  Future<Map<String, dynamic>> _fetch() async => await widget.session.api.get('/api/admin/overview') as Map<String, dynamic>;

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('İcmal')),
        body: FutureBuilder<Map<String, dynamic>>(
          future: _data,
          builder: (context, snap) {
            if (snap.hasError) return ListView(children: [Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!)))]);
            if (!snap.hasData) return const Center(child: CircularProgressIndicator());
            final d = snap.data!;
            final s = d['stores'] as Map<String, dynamic>;
            final total = _int(s['total']);
            final signups = ((d['signups'] as List?) ?? const []).cast<Map<String, dynamic>>();
            final revenue = ((d['revenueByMonth'] as List?) ?? const []).cast<Map<String, dynamic>>();
            final mix = ((d['planMix'] as List?) ?? const []).cast<Map<String, dynamic>>();
            final mixTotal = mix.fold<int>(0, (a, p) => a + _int(p['stores']));
            return RefreshIndicator(
              onRefresh: () async {
                setState(() => _data = _fetch());
                await _data;
              },
              child: ListView(padding: const EdgeInsets.fromLTRB(12, 12, 12, 24), children: [
                GridView.count(
                  crossAxisCount: 2,
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  mainAxisSpacing: 10,
                  crossAxisSpacing: 10,
                  childAspectRatio: 1.45,
                  children: [
                    _Kpi(label: 'Mağazalar', value: '$total', hint: '${s['new7']} yeni (7 gün)', colors: const [navy, Color(0xFF1E2F52)], onTap: () => widget.onOpenTab(1)),
                    _Kpi(
                        label: 'Aktiv abunəlik',
                        value: '${s['active']}',
                        hint: total == 0 ? '' : '${(_int(s['active']) * 100 / total).round()}% ödəyir',
                        colors: [Colors.green.shade700, Colors.green.shade500],
                        onTap: () => widget.onOpenTab(1)),
                    _Kpi(
                        label: 'Bu ayın gəliri',
                        value: formatMoney(_num(d['monthRevenue'])),
                        hint: 'aylıq ~${formatMoney(_num(d['monthlyRecurring']))}',
                        colors: const [Color(0xFFB8903F), gold],
                        dark: true),
                    _Kpi(
                        label: 'Gözləyən ödəniş',
                        value: '${d['pendingRequests']}',
                        hint: _int(d['pendingRequests']) > 0 ? 'Baxın' : 'Hamısı baxılıb',
                        colors: _int(d['pendingRequests']) > 0 ? [Colors.orange.shade700, Colors.orange.shade500] : [Colors.blue.shade700, Colors.blue.shade500],
                        onTap: () => widget.onOpenTab(2)),
                  ],
                ),
                const SizedBox(height: 10),
                AdminFollowUpsCard(session: widget.session),
                Row(children: [
                  for (final k in [
                    ('Az qalıb', s['expiring'], Colors.orange.shade800, 3),
                    ('Bitib', s['expired'], Colors.red.shade700, 3),
                    ('Ödəməyib', s['neverPaid'], Colors.blueGrey.shade700, 1),
                    ('Dayanıb', s['suspended'], Colors.blueGrey.shade700, 1),
                  ])
                    Expanded(
                      child: Card(
                        margin: const EdgeInsets.symmetric(horizontal: 3),
                        child: InkWell(
                          borderRadius: BorderRadius.circular(16),
                          onTap: () => widget.onOpenTab(k.$4),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 6),
                            child: Column(children: [
                              Text('${k.$2}', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: k.$3)),
                              Text(k.$1, style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
                            ]),
                          ),
                        ),
                      ),
                    ),
                ]),
                const SizedBox(height: 10),
                _ChartCard(
                  title: 'Yeni qeydiyyatlar',
                  sub: 'son 30 gün · ${signups.fold<int>(0, (a, x) => a + _int(x['count']))}',
                  values: [for (final x in signups) _num(x['count'])],
                  labels: [for (final (i, x) in signups.indexed) i % 7 == 6 ? '${x['date']}'.substring(8, 10) : ''],
                  color: navy,
                  format: (v) => '${v.round()}',
                ),
                _ChartCard(
                  title: 'Gəlir',
                  sub: 'son 6 ay · cəmi ${formatMoney(_num(d['totalRevenue']))}',
                  values: [for (final x in revenue) _num(x['amount'])],
                  labels: [for (final x in revenue) _months[int.parse('${x['month']}'.substring(5, 7)) - 1]],
                  color: gold,
                  format: formatMoney,
                ),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      const Text('Aktiv mağazalar paketlər üzrə', style: TextStyle(fontWeight: FontWeight.w700, color: navy)),
                      const SizedBox(height: 10),
                      if (mix.isEmpty)
                        Text('Hələ aktiv abunəlik yoxdur', style: TextStyle(color: Colors.grey.shade600))
                      else
                        for (final (i, p) in mix.indexed)
                          Padding(
                            padding: const EdgeInsets.only(bottom: 8),
                            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                              Row(children: [
                                Expanded(child: Text('${p['name']}')),
                                Text('${p['stores']} · ${(100 * _int(p['stores']) / (mixTotal == 0 ? 1 : mixTotal)).round()}%',
                                    style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
                              ]),
                              const SizedBox(height: 4),
                              ClipRRect(
                                borderRadius: BorderRadius.circular(6),
                                child: LinearProgressIndicator(
                                  value: _int(p['stores']) / (mixTotal == 0 ? 1 : mixTotal),
                                  minHeight: 8,
                                  backgroundColor: Colors.grey.shade200,
                                  color: [navy, gold, Colors.green, Colors.blue, Colors.red][i % 5],
                                ),
                              ),
                            ]),
                          ),
                    ]),
                  ),
                ),
              ]),
            );
          },
        ),
      );
}

class _Kpi extends StatelessWidget {
  const _Kpi({required this.label, required this.value, required this.hint, required this.colors, this.onTap, this.dark = false});

  final String label, value, hint;
  final List<Color> colors;
  final VoidCallback? onTap;
  final bool dark;

  @override
  Widget build(BuildContext context) {
    final fg = dark ? navy : Colors.white;
    return Material(
      borderRadius: BorderRadius.circular(18),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Ink(
          decoration: BoxDecoration(gradient: LinearGradient(colors: colors, begin: Alignment.topLeft, end: Alignment.bottomRight)),
          padding: const EdgeInsets.all(14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
            Text(label.toUpperCase(), style: TextStyle(color: fg.withValues(alpha: 0.8), fontSize: 11, fontWeight: FontWeight.w600)),
            FittedBox(child: Text(value, style: TextStyle(color: fg, fontSize: 26, fontWeight: FontWeight.w800))),
            Text(hint, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: fg.withValues(alpha: 0.8), fontSize: 11)),
          ]),
        ),
      ),
    );
  }
}

class _ChartCard extends StatelessWidget {
  const _ChartCard({required this.title, required this.sub, required this.values, required this.labels, required this.color, required this.format});

  final String title, sub;
  final List<double> values;
  final List<String> labels;
  final Color color;
  final String Function(double) format;

  @override
  Widget build(BuildContext context) {
    final max = values.fold<double>(0, (a, b) => b > a ? b : a);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(child: Text(title, style: const TextStyle(fontWeight: FontWeight.w700, color: navy))),
            Text(sub, style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
          ]),
          const SizedBox(height: 12),
          SizedBox(
            height: 120,
            child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
              for (final (i, v) in values.indexed)
                Expanded(
                  child: Tooltip(
                    message: format(v),
                    child: Column(mainAxisAlignment: MainAxisAlignment.end, children: [
                      Container(
                        margin: const EdgeInsets.symmetric(horizontal: 1.5),
                        height: max == 0 ? 2 : (v / max * 100).clamp(v > 0 ? 4 : 2, 100),
                        decoration: BoxDecoration(
                          color: v > 0 ? color : color.withValues(alpha: 0.2),
                          borderRadius: const BorderRadius.vertical(top: Radius.circular(4)),
                        ),
                      ),
                      const SizedBox(height: 4),
                      SizedBox(height: 12, child: Text(labels[i], style: TextStyle(fontSize: 9, color: Colors.grey.shade600))),
                    ]),
                  ),
                ),
            ]),
          ),
        ]),
      ),
    );
  }
}

// ============================================================ store list

/// All stores with search and filters; a tap opens the store card.
class AdminStoresScreen extends StatefulWidget {
  const AdminStoresScreen({super.key, required this.session});

  final Session session;

  @override
  State<AdminStoresScreen> createState() => _AdminStoresScreenState();
}

class _AdminStoresScreenState extends State<AdminStoresScreen> {
  late Future<List<Map<String, dynamic>>> _items = _fetch();
  final _q = TextEditingController();
  String _filter = 'all';

  Future<List<Map<String, dynamic>>> _fetch() async =>
      ((await widget.session.api.get('/api/admin/stores')) as List).cast<Map<String, dynamic>>();

  @override
  void dispose() {
    _q.dispose();
    super.dispose();
  }

  bool _matches(Map<String, dynamic> s) {
    final q = _q.text.trim().toLowerCase();
    if (q.isNotEmpty &&
        !'${s['name']} ${s['ownerEmail'] ?? ''} ${s['ownerName'] ?? ''} ${s['ownerPhone'] ?? ''}'.toLowerCase().contains(q)) {
      return false;
    }
    return switch (_filter) {
      'active' => s['subscriptionState'] == 'ACTIVE',
      'expired' => s['subscriptionState'] == 'EXPIRED',
      'none' => s['subscriptionState'] == 'NONE',
      'pending' => s['pendingPlanCode'] != null,
      'suspended' => s['status'] != 'ACTIVE',
      'idle' => s['idle'] == true,
      _ => true,
    };
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Mağazalar')),
        body: Column(children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
            child: TextField(
              controller: _q,
              onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(prefixIcon: Icon(Icons.search), hintText: 'Mağaza, sahib, e-poçt, telefon'),
            ),
          ),
          SizedBox(
            height: 44,
            child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 12), children: [
              for (final f in const [
                ('all', 'Hamısı'),
                ('active', 'Aktiv'),
                ('expired', 'Bitib'),
                ('pending', 'Sorğu var'),
                ('none', 'Abunəsiz'),
                ('suspended', 'Dayandırılıb'),
                ('idle', 'Ödəyir, istifadə etmir'),
              ])
                Padding(
                  padding: const EdgeInsets.only(right: 6),
                  child: ChoiceChip(label: Text(f.$2), selected: _filter == f.$1, onSelected: (_) => setState(() => _filter = f.$1)),
                ),
            ]),
          ),
          Expanded(
            child: FutureBuilder<List<Map<String, dynamic>>>(
              future: _items,
              builder: (context, snap) {
                if (snap.hasError) return ListView(children: [Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!)))]);
                if (!snap.hasData) return const Center(child: CircularProgressIndicator());
                final list = snap.data!.where(_matches).toList();
                return RefreshIndicator(
                  onRefresh: () async {
                    setState(() => _items = _fetch());
                    await _items;
                  },
                  child: list.isEmpty
                      ? ListView(children: const [EmptyState(title: 'Mağaza tapılmadı')])
                      : ListView.builder(
                          padding: const EdgeInsets.fromLTRB(12, 4, 12, 24),
                          itemCount: list.length,
                          itemBuilder: (_, i) {
                            final s = list[i];
                            final state = s['subscriptionState'] as String?;
                            final days = (s['daysRemaining'] as num?)?.toInt();
                            final (badge, color) = s['status'] != 'ACTIVE'
                                ? ('Dayandırılıb', Colors.red)
                                : state == 'ACTIVE'
                                    ? ('${s['activePlan']} · $days gün', (days ?? 99) <= 3 ? Colors.orange : Colors.green)
                                    : state == 'EXPIRED'
                                        ? ('Bitib', Colors.red)
                                        : ('Abunəsiz', Colors.blueGrey);
                            return Card(
                              child: ListTile(
                                onTap: () async {
                                  await Navigator.of(context).push(MaterialPageRoute(
                                      builder: (_) => AdminStoreScreen(session: widget.session, store: s)));
                                  if (mounted) setState(() => _items = _fetch());
                                },
                                leading: CircleAvatar(
                                  backgroundColor: navy,
                                  foregroundColor: gold,
                                  child: Text('${s['name']}'.isEmpty ? '?' : '${s['name']}'.characters.first.toUpperCase()),
                                ),
                                title: Text('${s['name']}', style: const TextStyle(fontWeight: FontWeight.w700)),
                                subtitle: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                                  Text([s['ownerName'], s['ownerPhone'] ?? s['phone'] ?? s['ownerEmail']].whereType<String>().join(' · '),
                                      maxLines: 2, overflow: TextOverflow.ellipsis),
                                  Text(
                                    'Son giriş: ${s['lastLoginAt'] == null ? '—' : formatDateTime(DateTime.parse('${s['lastLoginAt']}').toLocal())}'
                                    ' · bu ay ${s['salesThisMonth'] ?? 0} satış',
                                    style: TextStyle(fontSize: 11, color: Colors.grey.shade600),
                                  ),
                                  if (s['idle'] == true)
                                    Text('Ödəyir, amma istifadə etmir', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: Colors.red.shade700)),
                                  if ((s['tags'] as List?)?.isNotEmpty ?? false)
                                    Text((s['tags'] as List).join(' · '), style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: Colors.amber.shade900)),
                                  if (s['onTrial'] == true)
                                    Text('Pulsuz sınaqda', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: Colors.blue.shade700)),
                                ]),
                                trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                                  Container(
                                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                                    decoration: BoxDecoration(color: color.shade50, borderRadius: BorderRadius.circular(20)),
                                    child: Text(badge, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: color.shade800)),
                                  ),
                                  if (s['pendingPlanCode'] != null)
                                    Padding(
                                      padding: const EdgeInsets.only(top: 3),
                                      child: Text('Sorğu: ${s['pendingPlanName']}', style: TextStyle(fontSize: 10, color: Colors.orange.shade800)),
                                    ),
                                ]),
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

// ============================================================ store card

/// One store: contact and usage, subscriptions (activate / renew, extend, stop), activity and the danger zone.
class AdminStoreScreen extends StatefulWidget {
  const AdminStoreScreen({super.key, required this.session, required this.store});

  final Session session;
  final Map<String, dynamic> store;

  @override
  State<AdminStoreScreen> createState() => _AdminStoreScreenState();
}

class _AdminStoreScreenState extends State<AdminStoreScreen> {
  Map<String, dynamic>? _card;
  List<Map<String, dynamic>>? _subs;
  List<Map<String, dynamic>>? _log;
  late Map<String, dynamic> _store = widget.store;
  bool _busy = false;
  final Set<String> _parts = {};
  final _opQuery = TextEditingController();
  List<Map<String, dynamic>>? _ops;

  @override
  void dispose() {
    _opQuery.dispose();
    super.dispose();
  }

  Future<void> _searchOps() async {
    setState(() => _ops = null);
    try {
      final q = _opQuery.text.trim();
      final list = (await widget.session.api.get('/api/admin/stores/$_id/operations', query: q.isEmpty ? null : {'q': q}) as List)
          .cast<Map<String, dynamic>>();
      if (mounted) setState(() => _ops = list);
    } catch (e) {
      if (mounted) {
        setState(() => _ops = []);
        showError(context, e);
      }
    }
  }

  Future<void> _deleteOp(Map<String, dynamic> o) async {
    final type = '${o['type']}';
    final what = '${_opTypes[type]}${o['number'] != null ? ' ${o['number']}' : ''} · ${o['party'] ?? '—'} · ${formatMoney(_num(o['amount']))}';
    final note = type == 'SALE'
        ? 'Mal anbara qayıdır, satışın pulu kassadan, nisyə hissəsi müştərinin borcundan çıxılır.'
        : type == 'DEBT'
            ? 'Borc bütün ödənişləri və tarixçəsi ilə silinir.'
            : 'Ödəniş və onun kassa yazılışı silinir, borc yenidən hesablanır.';
    if (!await _confirm('Əməliyyatı sil', '$what\n\n$note\n\nGeri qaytarılmır.', 'Sil')) return;
    await _run(() => widget.session.api.delete('/api/admin/stores/$_id/operations/$type/${o['id']}'), 'Əməliyyat silindi');
    _searchOps();
  }

  /// Asks for the store name, then wipes [parts] (or everything when null).
  Future<void> _wipe(List<String>? parts) async {
    final name = '${_store['name']}';
    final typed = TextEditingController();
    final what = parts == null
        ? 'bütün məlumatları (müştərilər, borclar, ödənişlər, satışlar, məhsullar, anbar, kassa, xərclər, cib pulu)'
        : _purgeParts.where((p) => parts.contains(p.$1)).map((p) => p.$2).join(', ');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(builder: (ctx, set) {
        final match = typed.text.trim().toLowerCase() == name.trim().toLowerCase();
        return AlertDialog(
          title: Text(parts == null ? 'Hamısını sil' : 'Seçilənləri sil', style: TextStyle(color: Colors.red.shade800)),
          content: SingleChildScrollView(
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('“$name” mağazasında silinəcək: $what.'),
              const SizedBox(height: 8),
              Text('Geri qaytarılmır.', style: TextStyle(color: Colors.red.shade800, fontWeight: FontWeight.w700)),
              const SizedBox(height: 12),
              Text('Təsdiq üçün mağazanın adını yazın: $name', style: const TextStyle(fontSize: 13)),
              const SizedBox(height: 6),
              TextField(controller: typed, onChanged: (_) => set(() {}), decoration: InputDecoration(hintText: name)),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Ləğv et')),
            FilledButton(
              style: FilledButton.styleFrom(backgroundColor: Colors.red.shade700),
              onPressed: match ? () => Navigator.pop(ctx, true) : null,
              child: const Text('Sil'),
            ),
          ],
        );
      }),
    );
    final confirmName = typed.text;
    typed.dispose();
    if (ok != true) return;
    if (parts == null) {
      await _run(() => widget.session.api.post('/api/admin/stores/$_id/reset-data', {'confirmName': confirmName}),
          '“$name” mağazasının məlumatları sıfırlandı');
    } else {
      await _run(() => widget.session.api.post('/api/admin/stores/$_id/purge', {'parts': parts, 'confirmName': confirmName}),
          'Seçilən məlumatlar silindi');
      if (mounted) setState(_parts.clear);
    }
  }

  String get _id => '${widget.store['id']}';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final r = await Future.wait([
        widget.session.api.get('/api/admin/stores/$_id'),
        widget.session.api.get('/api/admin/subscriptions', query: {'storeId': _id}),
        widget.session.api.get('/api/admin/audit-logs', query: {'storeId': _id, 'limit': '200'}),
        widget.session.api.get('/api/admin/stores'),
      ]);
      if (!mounted) return;
      setState(() {
        _card = r[0] as Map<String, dynamic>;
        _subs = (r[1] as List).cast<Map<String, dynamic>>();
        _log = (r[2] as List).cast<Map<String, dynamic>>();
        _store = (r[3] as List).cast<Map<String, dynamic>>().firstWhere((s) => '${s['id']}' == _id, orElse: () => _store);
      });
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _run(Future<void> Function() action, String done) async {
    setState(() => _busy = true);
    try {
      await action();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(done)));
      await _load();
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<bool> _confirm(String title, String text, String ok) async =>
      await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: Text(title),
          content: Text(text),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Xeyr')),
            FilledButton(
                style: FilledButton.styleFrom(backgroundColor: Colors.red.shade700),
                onPressed: () => Navigator.pop(ctx, true),
                child: Text(ok)),
          ],
        ),
      ) ==
      true;

  Future<void> _activate() async {
    final plans = ((await widget.session.api.get('/api/public/plans')) as List).cast<Map<String, dynamic>>();
    if (!mounted || plans.isEmpty) return;
    String code = '${_store['pendingPlanCode'] ?? plans.first['code']}';
    String? period;
    final ok = await showModalBottomSheet<bool>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (ctx) => StatefulBuilder(builder: (ctx, set) {
        final plan = plans.firstWhere((p) => p['code'] == code, orElse: () => plans.first);
        final options = ((plan['options'] as List?) ?? const []).cast<Map<String, dynamic>>();
        period ??= options.isEmpty ? null : '${options.first['period']}';
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(_store['activePlan'] != null ? 'Abunəliyi yenilə' : 'Abunəliyi aktiv et',
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: navy)),
              const SizedBox(height: 10),
              Wrap(spacing: 8, runSpacing: 8, children: [
                for (final p in plans)
                  ChoiceChip(
                    label: Text('${p['name']}'),
                    selected: p['code'] == code,
                    onSelected: (_) => set(() {
                      code = '${p['code']}';
                      period = null;
                    }),
                  ),
              ]),
              const SizedBox(height: 10),
              Wrap(spacing: 8, runSpacing: 8, children: [
                for (final o in options)
                  ChoiceChip(
                    label: Text('${_periodLabel(o)} · ${formatMoney(_num(o['price']))}'),
                    selected: '${o['period']}' == period,
                    onSelected: (_) => set(() => period = '${o['period']}'),
                  ),
              ]),
              const SizedBox(height: 8),
              Text(
                  _store['activePlan'] != null
                      ? 'Yeni dövr cari bitmə tarixindən (${formatDate(_date(_store['subscriptionEnd']))}) sonra başlayır.'
                      : 'Abunəlik bu gündən başlayır.',
                  style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
              const SizedBox(height: 14),
              SizedBox(
                width: double.infinity,
                height: 50,
                child: FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Ödəniş yoxlanıldı — aktiv et')),
              ),
            ]),
          ),
        );
      }),
    );
    if (ok != true) return;
    await _run(() => widget.session.api.post('/api/admin/stores/$_id/subscription', {'planCode': code, 'period': period}),
        'Abunəlik aktiv edildi');
  }

  static String _periodLabel(Map<String, dynamic> o) {
    if (o['days'] != null) return '${o['days']} gün';
    final m = _int(o['months']);
    return m == 12 ? 'İllik' : '$m aylıq';
  }

  Future<void> _extend() async {
    final days = await showModalBottomSheet<int>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          for (final d in const [3, 7, 14, 30])
            ListTile(leading: const Icon(Icons.more_time), title: Text('$d gün uzat'), onTap: () => Navigator.pop(ctx, d)),
        ]),
      ),
    );
    if (days == null) return;
    await _run(() => widget.session.api.post('/api/admin/stores/$_id/extend', {'days': days}), 'Abunəlik $days gün uzadıldı');
  }

  Future<void> _stopSubscription() async {
    if (!await _confirm('Abunəliyi dayandır', '“${_store['name']}” mağazasının abunəliyi bu gün dayandırılsın? Mağaza dərhal bağlanacaq, məlumatları qalacaq.', 'Dayandır')) {
      return;
    }
    await _run(() => widget.session.api.post('/api/admin/stores/$_id/cancel-subscription', <String, dynamic>{}), 'Abunəlik dayandırıldı');
  }

  Future<void> _toggleStore() async {
    final suspend = (_card?['status'] ?? _store['status']) == 'ACTIVE';
    if (suspend &&
        !await _confirm('Mağazanı dayandır', '“${_store['name']}” dayandırılsın? Bütün istifadəçilərin girişi bağlanacaq.', 'Dayandır')) {
      return;
    }
    await _run(() => widget.session.api.post('/api/admin/stores/$_id/${suspend ? 'suspend' : 'activate'}'),
        suspend ? 'Mağaza dayandırıldı' : 'Mağaza bərpa edildi');
  }

  Future<void> _open(Uri uri) async {
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication) && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Tətbiq açıla bilmədi')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final card = _card;
    final status = card?['status'] ?? _store['status'];
    final active = _store['activePlan'] != null;
    // a köməkçi admin sees the store, its notes and subscriptions — no operations, log or danger zone
    final helper = widget.session.me?.isSupportAdmin ?? false;
    return DefaultTabController(
      length: helper ? 3 : 6,
      child: Scaffold(
        appBar: AppBar(
          title: Text('${_store['name']}'),
          bottom: TabBar(isScrollable: true, tabs: [
            const Tab(text: 'Ümumi'),
            const Tab(text: 'Qeydlər'),
            const Tab(text: 'Abunəlik'),
            if (!helper) ...const [Tab(text: 'Əməliyyatlar'), Tab(text: 'Fəaliyyət'), Tab(text: 'Təhlükəli zona')],
          ]),
        ),
        body: Column(children: [
          // header
          Container(
            width: double.infinity,
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
            decoration: const BoxDecoration(gradient: LinearGradient(colors: [navy, Color(0xFF1E2F52)])),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Wrap(spacing: 6, runSpacing: 6, children: [
                _Pill(status == 'ACTIVE' ? 'Aktiv' : 'Dayandırılıb', status == 'ACTIVE' ? Colors.green : Colors.red),
                _Pill(active ? '${_store['activePlan']} · ${_store['daysRemaining']} gün' : (_store['subscriptionState'] == 'EXPIRED' ? 'Abunə bitib' : 'Abunə yoxdur'),
                    active ? Colors.amber : Colors.blueGrey),
                if (_store['pendingPlanName'] != null) _Pill('Sorğu: ${_store['pendingPlanName']}', Colors.orange),
              ]),
              const SizedBox(height: 10),
              Row(children: [
                Expanded(
                  child: FilledButton.icon(
                    style: FilledButton.styleFrom(backgroundColor: gold, foregroundColor: navy),
                    onPressed: _busy ? null : _activate,
                    icon: const Icon(Icons.workspace_premium_outlined, size: 18),
                    label: Text(active ? 'Yenilə' : 'Aktiv et'),
                  ),
                ),
                if (active) ...[
                  const SizedBox(width: 8),
                  Expanded(
                    child: OutlinedButton.icon(
                      style: OutlinedButton.styleFrom(foregroundColor: Colors.white, side: const BorderSide(color: Colors.white54)),
                      onPressed: _busy ? null : _extend,
                      icon: const Icon(Icons.more_time, size: 18),
                      label: const Text('Uzat'),
                    ),
                  ),
                ],
              ]),
            ]),
          ),
          Expanded(
            child: card == null
                ? const Center(child: CircularProgressIndicator())
                : TabBarView(children: [
                    _infoTab(card),
                    AdminNotesTab(session: widget.session, storeId: _id,
                        tags: ((_store['tags'] as List?) ?? const []).cast<String>()),
                    _subsTab(active),
                    if (!helper) ...[_opsTab(), _logTab(), _dangerTab(status == 'ACTIVE')],
                  ]),
          ),
        ]),
      ),
    );
  }

  Widget _infoTab(Map<String, dynamic> c) {
    final u = c['usage'] as Map<String, dynamic>;
    final phone = (c['ownerPhone'] ?? c['phone']) as String?;
    final employees = ((c['employees'] as List?) ?? const []).cast<Map<String, dynamic>>();
    final wa = waUri(phone, 'Salam${c['ownerName'] != null ? ', ${c['ownerName']}' : ''}! HesabPro dəstək xidmətindən yazırıq.');
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(padding: const EdgeInsets.all(12), children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('SAHİB', style: TextStyle(fontSize: 11, color: Colors.grey.shade600, fontWeight: FontWeight.w700)),
              const SizedBox(height: 4),
              Text('${c['ownerName'] ?? '—'}', style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: navy)),
              if (c['ownerEmail'] != null) Text('${c['ownerEmail']}'),
              if (phone != null) Text(phone),
              Text('Qeydiyyat: ${formatDate(_date(c['createdAt']))}${c['lastLogin'] != null ? ' · son giriş ${formatDateTime(_date(c['lastLogin'])!)}' : ''}',
                  style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
              const SizedBox(height: 10),
              Wrap(spacing: 8, children: [
                if (wa != null)
                  ActionChip(avatar: const Icon(Icons.chat, size: 18, color: Colors.green), label: const Text('WhatsApp'), onPressed: () => _open(wa)),
                if (phone != null)
                  ActionChip(
                      avatar: const Icon(Icons.call, size: 18, color: Colors.blue),
                      label: const Text('Zəng et'),
                      onPressed: () => _open(Uri(scheme: 'tel', path: phone.replaceAll(RegExp(r'[^+\d]'), '')))),
                if (c['ownerEmail'] != null)
                  ActionChip(
                      avatar: const Icon(Icons.mail_outline, size: 18),
                      label: const Text('E-poçt'),
                      onPressed: () => _open(Uri(scheme: 'mailto', path: '${c['ownerEmail']}'))),
              ]),
            ]),
          ),
        ),
        GridView.count(
          crossAxisCount: 3,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          childAspectRatio: 1.25,
          mainAxisSpacing: 6,
          crossAxisSpacing: 6,
          children: [
            _Stat('Müştəri', '${u['customers']}'),
            _Stat('Şirkət', '${u['companies']}'),
            _Stat('İşçi', '${u['employees']}'),
            _Stat('Məhsul', '${u['products']}'),
            _Stat('Açıq borc', '${u['openDebts']}', sub: formatMoney(_num(u['debtRemaining']))),
            _Stat('Ödəniş', '${u['payments']}'),
            _Stat('Satış', '${u['sales']}', sub: formatMoney(_num(u['salesTotal']))),
            _Stat('Xərc', '${u['expenses']}'),
            _Stat('Son fəaliyyət', c['lastActivity'] == null ? '—' : formatDate(_date(c['lastActivity']))),
          ],
        ),
        const SizedBox(height: 8),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('İŞÇİLƏR (${employees.length})', style: TextStyle(fontSize: 11, color: Colors.grey.shade600, fontWeight: FontWeight.w700)),
              const SizedBox(height: 6),
              if (employees.isEmpty) Text('İşçi yoxdur', style: TextStyle(color: Colors.grey.shade600)),
              for (final e in employees)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 3),
                  child: Row(children: [
                    Expanded(
                      child: Text('${e['fullName']}',
                          style: TextStyle(decoration: e['active'] == true ? null : TextDecoration.lineThrough, color: e['active'] == true ? null : Colors.grey)),
                    ),
                    Text(jobTitleLabels[e['jobTitle']] ?? '${e['email']}', style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
                  ]),
                ),
            ]),
          ),
        ),
      ]),
    );
  }

  Widget _subsTab(bool active) {
    final subs = _subs ?? const [];
    const statusText = {'ACTIVE': 'Aktiv', 'EXPIRED': 'Bitib', 'PENDING': 'Gözləyir', 'REJECTED': 'Rədd edilib'};
    final statusColor = {'ACTIVE': Colors.green, 'EXPIRED': Colors.blueGrey, 'PENDING': Colors.orange, 'REJECTED': Colors.red};
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(padding: const EdgeInsets.all(12), children: [
        if (active)
          OutlinedButton.icon(
            style: OutlinedButton.styleFrom(foregroundColor: Colors.red.shade700, side: BorderSide(color: Colors.red.shade200)),
            onPressed: _busy ? null : _stopSubscription,
            icon: const Icon(Icons.stop_circle_outlined),
            label: const Text('Abunəliyi bu gün dayandır'),
          ),
        if (subs.isEmpty) const EmptyState(title: 'Abunəlik tarixçəsi yoxdur'),
        for (final s in subs)
          Card(
            child: ListTile(
              leading: Icon(Icons.circle, size: 14, color: statusColor[s['status']] ?? Colors.grey),
              title: Text('${s['planName']}', style: const TextStyle(fontWeight: FontWeight.w700)),
              subtitle: Text([
                statusText[s['status']] ?? '${s['status']}',
                if (s['startDate'] != null) '${formatDate(_date(s['startDate']))} — ${formatDate(_date(s['endDate']))}',
                if (s['startDate'] == null && s['requestedAt'] != null) 'Sorğu: ${formatDateTime(_date(s['requestedAt'])!)}',
                if (s['paymentMethod'] != null) s['paymentMethod'] == 'M10' ? 'M10' : 'Kart',
                if (s['rejectReason'] != null) 'Səbəb: ${s['rejectReason']}',
              ].join(' · ')),
              trailing: Text(formatMoney(_num(s['amount'])), style: const TextStyle(fontWeight: FontWeight.w800)),
            ),
          ),
      ]),
    );
  }

  Widget _opsTab() {
    if (_ops == null && !_busy) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _ops == null) _searchOps();
      });
    }
    final ops = _ops;
    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
        child: TextField(
          controller: _opQuery,
          textInputAction: TextInputAction.search,
          onSubmitted: (_) => _searchOps(),
          decoration: InputDecoration(
            hintText: 'Satış nömrəsi, çek nömrəsi, müştəri, məbləğ',
            prefixIcon: const Icon(Icons.search),
            suffixIcon: IconButton(icon: const Icon(Icons.arrow_forward), onPressed: _searchOps),
          ),
        ),
      ),
      Expanded(
        child: ops == null
            ? const Center(child: CircularProgressIndicator())
            : ops.isEmpty
                ? ListView(children: const [EmptyState(title: 'Əməliyyat tapılmadı')])
                : ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 4, 12, 24),
                    itemCount: ops.length,
                    itemBuilder: (_, i) {
                      final o = ops[i];
                      final type = '${o['type']}';
                      final color = type == 'SALE' ? navy : type == 'DEBT' ? Colors.red.shade700 : Colors.green.shade700;
                      return Card(
                        child: ListTile(
                          leading: CircleAvatar(
                            backgroundColor: color.withValues(alpha: 0.12),
                            child: Icon(type == 'SALE' ? Icons.point_of_sale : type == 'DEBT' ? Icons.receipt_long : Icons.payments, color: color, size: 20),
                          ),
                          title: Text('${o['number'] ?? _opTypes[type]}${o['party'] != null ? ' · ${o['party']}' : ''}',
                              maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600)),
                          subtitle: Text('${o['label']} · ${formatDateTime(_date(o['at'])!)}'),
                          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                            Text(formatMoney(_num(o['amount'])), style: const TextStyle(fontWeight: FontWeight.w800)),
                            IconButton(
                              tooltip: 'Sil',
                              icon: Icon(Icons.delete_outline, color: Colors.red.shade400),
                              onPressed: _busy ? null : () => _deleteOp(o),
                            ),
                          ]),
                        ),
                      );
                    },
                  ),
      ),
    ]);
  }

  Widget _logTab() {
    final log = _log ?? const [];
    const labels = {
      'STORE_REGISTERED': 'Mağaza qeydiyyatı',
      'LOGIN': 'Sistemə giriş',
      'SUBSCRIPTION_APPROVED': 'Abunəlik təsdiqləndi',
      'SUBSCRIPTION_ACTIVATED': 'Abunəlik aktiv edildi',
      'SUBSCRIPTION_EXTENDED': 'Abunəlik uzadıldı',
      'SUBSCRIPTION_CANCELLED': 'Abunəlik dayandırıldı',
      'SUBSCRIPTION_REMINDED': 'Abunəlik xatırladıldı',
      'SUBSCRIPTION_REQUESTED': 'Abunəlik sorğusu',
      'SUBSCRIPTION_REJECTED': 'Abunəlik rədd edildi',
      'STORE_SUSPENDED': 'Mağaza dayandırıldı',
      'STORE_ACTIVATED': 'Mağaza bərpa edildi',
      'STORE_DATA_RESET': 'Məlumatlar sıfırlandı',
      'CUSTOMER_CREATED': 'Müştəri əlavə edildi',
      'DEBT_CREATED': 'Borc əlavə edildi',
      'PAYMENT_RECORDED': 'Ödəniş qəbul edildi',
      'EMPLOYEE_CREATED': 'İşçi əlavə edildi',
    };
    return RefreshIndicator(
      onRefresh: _load,
      child: log.isEmpty
          ? ListView(children: const [EmptyState(title: 'Qeyd yoxdur')])
          : ListView.separated(
              padding: const EdgeInsets.all(12),
              itemCount: log.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (_, i) {
                final a = log[i];
                return ListTile(
                  dense: true,
                  tileColor: a['action'] == 'STORE_DATA_RESET' ? Colors.red.shade50 : null,
                  title: Text(labels[a['action']] ?? '${a['action']}', style: const TextStyle(fontWeight: FontWeight.w600)),
                  subtitle: Text([a['actorName'], a['details']].whereType<String>().join(' · '), maxLines: 3),
                  trailing: Text(formatDateTime(_date(a['createdAt'])!), style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
                );
              },
            ),
    );
  }

  Widget _dangerTab(bool storeActive) => ListView(padding: const EdgeInsets.all(12), children: [
        Card(
          child: ListTile(
            title: Text(storeActive ? 'Mağazanı dayandır' : 'Mağazanı bərpa et', style: const TextStyle(fontWeight: FontWeight.w700)),
            subtitle: Text(storeActive
                ? 'Bütün istifadəçilərin girişi bağlanır; məlumatlar qalır, istənilən vaxt bərpa olunur.'
                : 'Mağaza yenidən açılır, istifadəçilər daxil ola bilir.'),
            trailing: FilledButton(
              style: FilledButton.styleFrom(backgroundColor: storeActive ? Colors.red.shade700 : Colors.green.shade700),
              onPressed: _busy ? null : _toggleStore,
              child: Text(storeActive ? 'Dayandır' : 'Bərpa et'),
            ),
          ),
        ),
        const SizedBox(height: 8),
        Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: Colors.red.shade50,
            border: Border.all(color: Colors.red.shade200, width: 2),
            borderRadius: BorderRadius.circular(16),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Məlumatları sil', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800, color: Colors.red.shade800)),
            const SizedBox(height: 6),
            const Text('Yalnız seçilənlər:', style: TextStyle(fontWeight: FontWeight.w700)),
            for (final p in _purgeParts)
              CheckboxListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                controlAffinity: ListTileControlAffinity.leading,
                activeColor: Colors.red.shade700,
                value: _parts.contains(p.$1),
                onChanged: (v) => setState(() => v == true ? _parts.add(p.$1) : _parts.remove(p.$1)),
                title: Text(p.$2),
                subtitle: p.$3.isEmpty ? null : Text(p.$3, style: const TextStyle(fontSize: 11)),
              ),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                style: OutlinedButton.styleFrom(foregroundColor: Colors.red.shade800, side: BorderSide(color: Colors.red.shade300)),
                onPressed: _busy || _parts.isEmpty ? null : () => _wipe(_parts.toList()),
                icon: const Icon(Icons.delete_sweep_outlined),
                label: Text('Seçilənləri sil${_parts.isEmpty ? '' : ' (${_parts.length})'}'),
              ),
            ),
            const Divider(height: 24),
            const SizedBox(height: 6),
            const Text('Müştərilər, şirkətlər, borclar, ödənişlər, satışlar, məhsullar, anbar, kassa əməliyyatları, xərclər və cib pulu '
                'tamamilə silinir. Hesab, işçilər, abunəlik və audit jurnalı qalır — mağaza təmiz başlayır. Geri qaytarılmır.'),
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                style: FilledButton.styleFrom(backgroundColor: Colors.red.shade700),
                onPressed: _busy ? null : () => _wipe(null),
                icon: const Icon(Icons.delete_forever_outlined),
                label: const Text('Bütün məlumatları sil'),
              ),
            ),
          ]),
        ),
      ]);
}

class _Pill extends StatelessWidget {
  const _Pill(this.text, this.color);

  final String text;
  final MaterialColor color;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(color: color.withValues(alpha: 0.2), borderRadius: BorderRadius.circular(20)),
        child: Text(text, style: TextStyle(color: color.shade100, fontSize: 12, fontWeight: FontWeight.w700)),
      );
}

class _Stat extends StatelessWidget {
  const _Stat(this.label, this.value, {this.sub});

  final String label, value;
  final String? sub;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(12), border: Border.all(color: Colors.grey.shade200)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisAlignment: MainAxisAlignment.center, children: [
          Text(label.toUpperCase(), maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 9, color: Colors.grey.shade600, fontWeight: FontWeight.w700)),
          FittedBox(child: Text(value, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: navy))),
          if (sub != null) Text(sub!, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 10, color: Colors.grey.shade600)),
        ]),
      );
}
