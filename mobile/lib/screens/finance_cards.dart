import 'package:flutter/material.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'account_sheet.dart';
import 'common.dart';
import 'party_profile_screen.dart';

class _Fig {
  final int count;
  final double sum;
  const _Fig(this.count, this.sum);
  factory _Fig.of(Object? j) {
    final m = j as Map<String, dynamic>;
    return _Fig((m['count'] as num).toInt(), (m['sum'] as num).toDouble());
  }
}

class _ListMeta {
  final String title;
  final String sumLabel;
  final String shape; // party | debt | entry | payment
  final bool partyFilter;
  const _ListMeta(this.title, this.sumLabel, this.shape, {this.partyFilter = false});
}

const _meta = {
  'customer-debts': _ListMeta('Müştəri borcu', 'Qalıq borc', 'party'),
  'active-customers': _ListMeta('Aktiv müştərilər', 'Qalıq borc', 'party'),
  'company-receivables': _ListMeta('Şirkətlər bizə borcludur', 'Qalıq borc', 'party'),
  'company-payables': _ListMeta('Şirkətlərə borcum', 'Qalıq borcumuz', 'party'),
  'all-open-debts': _ListMeta('Toplam borc', 'Qalıq', 'debt', partyFilter: true),
  'today-debts': _ListMeta('Yazılan borclar', 'Yazılan məbləğ', 'entry', partyFilter: true),
  'active-debts': _ListMeta('Aktiv borclar', 'Qalıq', 'debt', partyFilter: true),
  'overdue-debts': _ListMeta('Gecikmiş borclar', 'Qalıq', 'debt', partyFilter: true),
  'closed-debts': _ListMeta('Bitmiş borclar', 'Bağlanmış məbləğ', 'debt', partyFilter: true),
  'payments-out': _ListMeta('Ödədiyim', 'Ödənilib', 'payment'),
  'payments-in': _ListMeta('Alınan ödənişlər', 'Qəbul edilib', 'payment', partyFilter: true),
};

/// HesabPro 2.0 coloured cards. Each figure equals the total of the list that opens on tap.
class FinanceCards extends StatefulWidget {
  const FinanceCards({super.key, required this.session});

  final Session session;

  @override
  State<FinanceCards> createState() => FinanceCardsState();
}

class FinanceCardsState extends State<FinanceCards> {
  late Future<Map<String, dynamic>> _data = _fetch();

  Future<Map<String, dynamic>> _fetch() =>
      widget.session.api.get('/api/dashboard/overview').then((j) => j as Map<String, dynamic>);

  void reload() => setState(() => _data = _fetch());

  void _open(String kind) async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => DashboardListScreen(session: widget.session, kind: kind)));
    reload();
  }

  Future<void> _addPayable() async {
    final id = await pickParty(context, session: widget.session, isCustomer: false);
    if (id == null || !mounted) return;
    final msg = await openAccountSheet(context,
        session: widget.session, isCustomer: false, partyId: id, mode: AccountMode.charge, initialType: 'COMPANY_PAYABLE');
    if (msg == null || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
    reload();
  }

  @override
  Widget build(BuildContext context) {
    final me = widget.session.me!;
    return FutureBuilder<Map<String, dynamic>>(
      future: _data,
      builder: (context, snap) {
        if (snap.hasError) return ErrorBanner(errorMessage(snap.error!));
        if (!snap.hasData) return const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()));
        final o = snap.data!;
        final payables = _Fig.of(o['companyPayables']);
        final out = _Fig.of(o['paymentsOut']);
        final active = _Fig.of(o['activeDebts']);
        final closed = _Fig.of(o['closedDebts']);
        return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          _BigCard(
            colors: const [Color(0xFF7C3AED), Color(0xFF5B21B6)],
            icon: Icons.account_balance_wallet_outlined,
            label: 'Toplam borc',
            value: formatMoney(active.sum),
            hint: '${active.count} açıq borc · müştəri ${formatMoney(_Fig.of(o['customerDebts']).sum)}'
                ' · şirkət ${formatMoney(_Fig.of(o['companyReceivables']).sum)} · borcum ${formatMoney(payables.sum)}',
            onTap: () => _open('all-open-debts'),
          ),
          const SizedBox(height: 12),
          Row(children: [
            Expanded(
              child: _BigCard(
                  colors: const [Color(0xFF14B8A6), Color(0xFF0E7490)],
                  icon: Icons.payments_outlined,
                  label: 'Ödədiyim',
                  value: formatMoney(out.sum),
                  hint: '${out.count} ödəniş',
                  onTap: () => _open('payments-out'),
                  compact: true),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _BigCard(
                  colors: const [Color(0xFFFB923C), Color(0xFFEA580C)],
                  icon: Icons.timelapse,
                  label: 'Aktiv borclar',
                  value: '${active.count}',
                  hint: formatMoney(active.sum),
                  onTap: () => _open('active-debts'),
                  compact: true),
            ),
          ]),
          const SizedBox(height: 12),
          _BigCard(
              colors: const [Color(0xFF10B981), Color(0xFF15803D)],
              icon: Icons.task_alt,
              label: 'Bitmiş borc',
              value: '${closed.count}',
              hint: 'Cəmi bağlanmış: ${formatMoney(closed.sum)}',
              onTap: () => _open('closed-debts'),
              compact: true),
          const SizedBox(height: 12),
          GridView.count(
            crossAxisCount: 2,
            mainAxisSpacing: 10,
            crossAxisSpacing: 10,
            childAspectRatio: 1.7,
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            children: [
              _SmallCard('Müştəri borcu', formatMoney(_Fig.of(o['customerDebts']).sum), '${_Fig.of(o['customerDebts']).count} müştəri',
                  Icons.people_alt_outlined, navy, () => _open('customer-debts')),
              _SmallCard('Şirkətlər bizə borcludur', formatMoney(_Fig.of(o['companyReceivables']).sum),
                  '${_Fig.of(o['companyReceivables']).count} şirkət', Icons.business_outlined, Colors.blue.shade700, () => _open('company-receivables')),
              _SmallCard('Bu gün yazılan', formatMoney(_Fig.of(o['todayDebts']).sum), '${_Fig.of(o['todayDebts']).count} əməliyyat',
                  Icons.today_outlined, Colors.deepPurple, () => _open('today-debts')),
              _SmallCard('Gecikmiş borclar', '${_Fig.of(o['overdueDebts']).count}', formatMoney(_Fig.of(o['overdueDebts']).sum),
                  Icons.warning_amber_rounded, Colors.red.shade700, () => _open('overdue-debts')),
              _SmallCard('Aktiv müştərilər', '${_Fig.of(o['activeCustomers']).count}', 'borcu olanlar', Icons.person_pin_outlined,
                  Colors.amber.shade800, () => _open('active-customers')),
              _SmallCard('Alınan ödənişlər', formatMoney(_Fig.of(o['paymentsIn']).sum), '${_Fig.of(o['paymentsIn']).count} ödəniş',
                  Icons.south_west, Colors.green.shade700, () => _open('payments-in')),
              _SmallCard('Şirkətlərə borcum', formatMoney(payables.sum), '${payables.count} şirkətə açıq öhdəlik',
                  Icons.north_east, Colors.deepPurple.shade400, () => _open('company-payables')),
              if (me.can('COMPANY_PAYABLES'))
                _SmallCard('Şirkət borcu yaz', '+ Əlavə et', 'təchizatçıya borc', Icons.add_business_outlined,
                    Colors.deepPurple.shade400, _addPayable),
            ],
          ),
        ]);
      },
    );
  }
}

class _BigCard extends StatelessWidget {
  const _BigCard({required this.colors, required this.icon, required this.label, required this.value, required this.hint,
      required this.onTap, this.compact = false});

  final List<Color> colors;
  final IconData icon;
  final String label;
  final String value;
  final String hint;
  final VoidCallback onTap;
  final bool compact;

  @override
  Widget build(BuildContext context) => Material(
        borderRadius: BorderRadius.circular(22),
        clipBehavior: Clip.antiAlias,
        child: Ink(
          decoration: BoxDecoration(gradient: LinearGradient(colors: colors, begin: Alignment.topLeft, end: Alignment.bottomRight)),
          child: InkWell(
            onTap: onTap,
            child: Padding(
              padding: EdgeInsets.all(compact ? 14 : 18),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(color: Colors.white24, borderRadius: BorderRadius.circular(12)),
                    child: Icon(icon, color: Colors.white, size: compact ? 20 : 24),
                  ),
                ]),
                SizedBox(height: compact ? 10 : 14),
                Text(label, style: const TextStyle(color: Colors.white70, fontSize: 13)),
                FittedBox(
                  fit: BoxFit.scaleDown,
                  alignment: Alignment.centerLeft,
                  child: Text(value,
                      style: TextStyle(color: Colors.white, fontSize: compact ? 22 : 28, fontWeight: FontWeight.w800)),
                ),
                Text(hint, style: const TextStyle(color: Colors.white70, fontSize: 11), maxLines: 2, overflow: TextOverflow.ellipsis),
              ]),
            ),
          ),
        ),
      );
}

class _SmallCard extends StatelessWidget {
  const _SmallCard(this.label, this.value, this.hint, this.icon, this.color, this.onTap);

  final String label;
  final String value;
  final String hint;
  final IconData icon;
  final Color color;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Card(
        margin: EdgeInsets.zero,
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisAlignment: MainAxisAlignment.center, children: [
              Row(children: [
                Icon(icon, size: 18, color: color),
                const SizedBox(width: 6),
                Expanded(child: Text(label, style: TextStyle(fontSize: 11, color: Colors.grey.shade700), maxLines: 1, overflow: TextOverflow.ellipsis)),
              ]),
              const SizedBox(height: 4),
              FittedBox(
                  fit: BoxFit.scaleDown,
                  alignment: Alignment.centerLeft,
                  child: Text(value, style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800, color: color))),
              Text(hint, style: TextStyle(fontSize: 10, color: Colors.grey.shade500), maxLines: 1, overflow: TextOverflow.ellipsis),
            ]),
          ),
        ),
      );
}

/// Picks a customer or company (search by name, phone or code). Returns its id.
Future<String?> pickParty(BuildContext context, {required Session session, required bool isCustomer}) {
  return showModalBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    useSafeArea: true,
    builder: (ctx) => SizedBox(height: MediaQuery.of(ctx).size.height * 0.8, child: _PartyPicker(session: session, isCustomer: isCustomer)),
  );
}

class _PartyPicker extends StatefulWidget {
  const _PartyPicker({required this.session, required this.isCustomer});

  final Session session;
  final bool isCustomer;

  @override
  State<_PartyPicker> createState() => _PartyPickerState();
}

class _PartyPickerState extends State<_PartyPicker> {
  String _q = '';
  late Future<List<Map<String, dynamic>>> _items = _fetch();

  Future<List<Map<String, dynamic>>> _fetch() => widget.session.api
      .get(widget.isCustomer ? '/api/customers' : '/api/companies', query: _q.isEmpty ? null : {'q': _q})
      .then((j) => (j as List).cast<Map<String, dynamic>>());

  @override
  Widget build(BuildContext context) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
        child: Column(children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: TextField(
              autofocus: true,
              decoration: InputDecoration(
                  prefixIcon: const Icon(Icons.search), hintText: widget.isCustomer ? 'Ad, telefon və ya kod' : 'Şirkət adı və ya telefon'),
              onChanged: (v) => setState(() {
                _q = v.trim();
                _items = _fetch();
              }),
            ),
          ),
          Expanded(
            child: FutureBuilder<List<Map<String, dynamic>>>(
              future: _items,
              builder: (context, snap) {
                if (snap.hasError) return ErrorBanner(errorMessage(snap.error!));
                if (!snap.hasData) return const Center(child: CircularProgressIndicator());
                if (snap.data!.isEmpty) return const EmptyState(title: 'Tapılmadı');
                return ListView(children: [
                  for (final p in snap.data!)
                    ListTile(
                      title: Text(p['name'] as String),
                      subtitle: Text([p['code'], p['phone']].where((e) => e != null && '$e'.isNotEmpty).join(' · ')),
                      trailing: Text(formatMoney((p['openBalance'] as num).toDouble())),
                      onTap: () => Navigator.pop(context, p['id'] as String),
                    ),
                ]);
              },
            ),
          ),
        ]),
      );
}

/// The list behind a dashboard card, with search, date presets and (for debts) customer / company filter.
class DashboardListScreen extends StatefulWidget {
  const DashboardListScreen({super.key, required this.session, required this.kind});

  final Session session;
  final String kind;

  @override
  State<DashboardListScreen> createState() => _DashboardListScreenState();
}

class _DashboardListScreenState extends State<DashboardListScreen> {
  String _q = '';
  String? _from;
  String? _to;
  String _party = '';
  String _preset = 'all';
  final List<Map<String, dynamic>> _items = [];
  int _total = 0;
  double _sum = 0;
  int _page = 0;
  bool _loading = false;
  String? _error;

  _ListMeta get _m => _meta[widget.kind]!;

  @override
  void initState() {
    super.initState();
    if (widget.kind == 'today-debts') _applyPreset('today', fetch: false);
    _load(reset: true);
  }

  String _iso(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  void _applyPreset(String key, {bool fetch = true}) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    setState(() {
      _preset = key;
      switch (key) {
        case 'today':
          _from = _iso(today);
          _to = _iso(today);
        case 'week':
          _from = _iso(today.subtract(Duration(days: today.weekday - 1)));
          _to = _iso(today);
        case 'month':
          _from = _iso(DateTime(today.year, today.month, 1));
          _to = _iso(today);
        case 'lastMonth':
          _from = _iso(DateTime(today.year, today.month - 1, 1));
          _to = _iso(DateTime(today.year, today.month, 0));
        default:
          _from = null;
          _to = null;
      }
    });
    if (fetch) _load(reset: true);
  }

  Future<void> _pickRange() async {
    final r = await showDateRangePicker(
        context: context, firstDate: DateTime(2020), lastDate: DateTime.now(), locale: const Locale('az'));
    if (r == null) return;
    setState(() {
      _preset = 'custom';
      _from = _iso(r.start);
      _to = _iso(r.end);
    });
    _load(reset: true);
  }

  Future<void> _load({bool reset = false}) async {
    if (_loading) return;
    setState(() {
      _loading = true;
      _error = null;
      if (reset) {
        _items.clear();
        _page = 0;
      }
    });
    try {
      // "Toplam borc" is the server's active-debts list (every open debt the user may see), under its own title.
      final apiKind = widget.kind == 'all-open-debts' ? 'active-debts' : widget.kind;
      final j = await widget.session.api.get('/api/dashboard/lists/$apiKind', query: {
        'page': '$_page',
        'size': '50',
        if (_q.isNotEmpty) 'q': _q,
        'from': ?_from,
        'to': ?_to,
        if (_party.isNotEmpty) 'partyKind': _party,
      }) as Map<String, dynamic>;
      if (!mounted) return;
      setState(() {
        _items.addAll((j['items'] as List).cast<Map<String, dynamic>>());
        _total = (j['total'] as num).toInt();
        _sum = (j['sum'] as num).toDouble();
        _page++;
      });
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _openParty(Map<String, dynamic> r) async {
    await Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => PartyProfileScreen(
            session: widget.session, isCustomer: r['partyKind'] == 'CUSTOMER', partyId: r['partyId'] as String)));
    _load(reset: true);
  }

  Widget _row(Map<String, dynamic> r) {
    double n(String k) => (r[k] as num?)?.toDouble() ?? 0;
    final sub = [r['code'], r['phone']].where((e) => e != null && '$e'.isNotEmpty).join(' · ');
    switch (_m.shape) {
      case 'party':
        return ListTile(
          onTap: () => _openParty(r),
          title: Text(r['name'] as String, style: const TextStyle(fontWeight: FontWeight.w600)),
          subtitle: Text([
            if (sub.isNotEmpty) sub,
            'Cəmi ${formatMoney(n('totalDebt'))} · ödənilib ${formatMoney(n('paid'))}',
            if ((r['overdueDebts'] as num? ?? 0) > 0) 'Gecikmiş borc var',
          ].join('\n')),
          isThreeLine: true,
          trailing: Text(formatMoney(n('remaining')), style: const TextStyle(fontWeight: FontWeight.w800)),
        );
      case 'debt':
        final late = (r['overdueDays'] as num? ?? 0) > 0;
        return ListTile(
          onTap: () => _openParty(r),
          title: Text(r['name'] as String, style: const TextStyle(fontWeight: FontWeight.w600)),
          subtitle: Text([
            '${formatDate(DateTime.parse(r['debtDate'] as String))} · ilkin ${formatMoney(n('amount'))} · ödənilib ${formatMoney(n('paid'))}',
            if (r['dueDate'] != null) 'Son tarix: ${formatDate(DateTime.parse(r['dueDate'] as String))}${late ? ' · ${r['overdueDays']} gün gecikir' : ''}',
            if (r['receiptNumber'] != null) 'Çek: ${r['receiptNumber']}',
          ].join('\n')),
          isThreeLine: true,
          trailing: Text(formatMoney(widget.kind == 'closed-debts' ? n('amount') : n('remaining')),
              style: TextStyle(fontWeight: FontWeight.w800, color: late ? Colors.red.shade700 : null)),
        );
      case 'entry':
        return ListTile(
          onTap: () => _openParty(r),
          title: Text(r['name'] as String, style: const TextStyle(fontWeight: FontWeight.w600)),
          subtitle: Text([
            '${formatDateTime(DateTime.parse(r['createdAt'] as String))} · ${r['actorName']}',
            if (r['receiptNumber'] != null) 'Çek: ${r['receiptNumber']}',
            'Ödənilib ${formatMoney(n('debtPaid'))} · qalıq ${formatMoney(n('debtRemaining'))}',
          ].join('\n')),
          isThreeLine: true,
          trailing: Text('+${formatMoney(n('amount'))}', style: const TextStyle(fontWeight: FontWeight.w800)),
        );
      default:
        return ListTile(
          onTap: () => _openParty(r),
          title: Text(r['name'] as String, style: const TextStyle(fontWeight: FontWeight.w600)),
          subtitle: Text('${formatDateTime(DateTime.parse(r['paidAt'] as String))} · '
              '${methodLabels[r['paymentMethod']] ?? r['paymentMethod']} · ${r['recordedByName']}'),
          trailing: Text(formatMoney(n('amount')), style: const TextStyle(fontWeight: FontWeight.w800)),
        );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_m.title)),
      body: NotificationListener<ScrollNotification>(
        onNotification: (n) {
          if (n.metrics.pixels > n.metrics.maxScrollExtent - 300 && _items.length < _total && !_loading) _load();
          return false;
        },
        child: RefreshIndicator(
          onRefresh: () => _load(reset: true),
          child: ListView(padding: const EdgeInsets.fromLTRB(12, 8, 12, 24), children: [
            Row(children: [
              Expanded(child: StatTile(label: 'Qeyd sayı', value: '$_total')),
              const SizedBox(width: 10),
              Expanded(child: StatTile(label: _m.sumLabel, value: formatMoney(_sum))),
            ]),
            const SizedBox(height: 10),
            TextField(
              decoration: const InputDecoration(prefixIcon: Icon(Icons.search), hintText: 'Ad, telefon, kod, çek nömrəsi'),
              textInputAction: TextInputAction.search,
              onSubmitted: (v) {
                _q = v.trim();
                _load(reset: true);
              },
            ),
            const SizedBox(height: 8),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(children: [
                for (final p in const [['today', 'Bu gün'], ['week', 'Bu həftə'], ['month', 'Bu ay'], ['lastMonth', 'Keçən ay'], ['all', 'Hamısı']])
                  Padding(
                    padding: const EdgeInsets.only(right: 6),
                    child: ChoiceChip(label: Text(p[1]), selected: _preset == p[0], onSelected: (_) => _applyPreset(p[0])),
                  ),
                ActionChip(avatar: const Icon(Icons.date_range, size: 16), label: Text(_preset == 'custom' ? '$_from – $_to' : 'Aralıq'), onPressed: _pickRange),
              ]),
            ),
            if (_m.partyFilter)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: SegmentedButton<String>(
                  segments: const [
                    ButtonSegment(value: '', label: Text('Hamısı')),
                    ButtonSegment(value: 'CUSTOMER', label: Text('Müştəri')),
                    ButtonSegment(value: 'COMPANY', label: Text('Şirkət')),
                  ],
                  selected: {_party},
                  showSelectedIcon: false,
                  onSelectionChanged: (s) {
                    setState(() => _party = s.first);
                    _load(reset: true);
                  },
                ),
              ),
            const SizedBox(height: 8),
            if (_error != null) ErrorBanner(_error!),
            if (!_loading && _items.isEmpty && _error == null) const EmptyState(title: 'Qeyd tapılmadı', text: 'Axtarışı və ya tarixi dəyişin.'),
            for (final r in _items) Card(child: _row(r)),
            if (_loading) const Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator())),
          ]),
        ),
      ),
    );
  }
}
