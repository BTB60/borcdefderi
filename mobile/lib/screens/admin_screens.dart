import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import '../whatsapp.dart';
import 'common.dart';
import 'inventory_screen.dart' show showProductImage;
import 'profile_screen.dart';

/// Platform admin (SUPER_ADMIN) on the phone: payment requests and expiring subscriptions.
/// The rest of the admin panel (packages, revenue, users, audit) stays on the web.
class AdminHome extends StatefulWidget {
  const AdminHome({super.key, required this.session});

  final Session session;

  @override
  State<AdminHome> createState() => _AdminHomeState();
}

class _AdminHomeState extends State<AdminHome> {
  int _tab = 0;

  @override
  Widget build(BuildContext context) {
    final pages = [
      PaymentRequestsScreen(session: widget.session),
      ExpiringSubscriptionsScreen(session: widget.session),
      _AdminAccount(session: widget.session),
    ];
    return Scaffold(
      body: IndexedStack(index: _tab, children: pages),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) => setState(() => _tab = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.receipt_long_outlined), selectedIcon: Icon(Icons.receipt_long), label: 'Sorğular'),
          NavigationDestination(icon: Icon(Icons.timer_outlined), selectedIcon: Icon(Icons.timer), label: 'Bitənlər'),
          NavigationDestination(icon: Icon(Icons.person_outline), selectedIcon: Icon(Icons.person), label: 'Hesab'),
        ],
      ),
    );
  }
}

double _n(Object? v) => (v as num?)?.toDouble() ?? 0;
DateTime? _d(Object? v) => v == null ? null : DateTime.tryParse('$v');

Future<void> _launch(BuildContext context, Uri uri) async {
  final ok = await launchUrl(uri, mode: LaunchMode.externalApplication);
  if (!ok && context.mounted) {
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Tətbiq açıla bilmədi')));
  }
}

void _toast(BuildContext context, String text) {
  if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
}

// ---------------------------------------------------------------- payment requests

class PaymentRequestsScreen extends StatefulWidget {
  const PaymentRequestsScreen({super.key, required this.session});

  final Session session;

  @override
  State<PaymentRequestsScreen> createState() => _PaymentRequestsScreenState();
}

class _PaymentRequestsScreenState extends State<PaymentRequestsScreen> {
  String _status = 'PENDING';
  late Future<List<Map<String, dynamic>>> _items;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _items = widget.session.api
        .get('/api/admin/subscriptions', query: _status.isEmpty ? null : {'status': _status})
        .then((j) => (j as List).cast<Map<String, dynamic>>());
  }

  Future<void> _refresh() async {
    setState(_load);
    await _items.catchError((Object _) => <Map<String, dynamic>>[]);
  }

  Future<void> _open(Map<String, dynamic> sub) async {
    final changed = await Navigator.push<bool>(
      context,
      MaterialPageRoute(builder: (_) => _RequestDetail(session: widget.session, sub: sub)),
    );
    if (changed == true) _refresh();
  }

  @override
  Widget build(BuildContext context) {
    const filters = {'PENDING': 'Gözləyən', 'ACTIVE': 'Təsdiqlənmiş', 'REJECTED': 'Rədd edilmiş', '': 'Hamısı'};
    return Scaffold(
      appBar: AppBar(title: const Text('Ödəniş sorğuları')),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<Map<String, dynamic>>>(
          future: _items,
          builder: (context, snap) {
            final items = snap.data ?? const [];
            return ListView(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
              children: [
                SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(children: [
                    for (final f in filters.entries)
                      Padding(
                        padding: const EdgeInsets.only(right: 6),
                        child: ChoiceChip(
                          label: Text(f.value),
                          selected: _status == f.key,
                          onSelected: (_) {
                            _status = f.key;
                            _refresh();
                          },
                        ),
                      ),
                  ]),
                ),
                const SizedBox(height: 8),
                if (snap.hasError) ErrorBanner(errorMessage(snap.error!)),
                if (snap.connectionState != ConnectionState.done) const Padding(padding: EdgeInsets.all(32), child: Center(child: CircularProgressIndicator())),
                if (snap.connectionState == ConnectionState.done && !snap.hasError && items.isEmpty)
                  EmptyState(title: _status == 'PENDING' ? 'Gözləyən sorğu yoxdur' : 'Sorğu tapılmadı'),
                for (final s in items)
                  Card(
                    child: ListTile(
                      onTap: () => _open(s),
                      title: Text('${s['storeName'] ?? '—'}', style: const TextStyle(fontWeight: FontWeight.w600)),
                      subtitle: Text([
                        '${s['planName'] ?? ''}',
                        if (_d(s['requestedAt']) != null) formatDateTime(_d(s['requestedAt'])!),
                      ].where((t) => t.isNotEmpty).join(' · ')),
                      trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                        Text(formatMoney(_n(s['declaredAmount'] ?? s['amount'])), style: const TextStyle(fontWeight: FontWeight.w800, color: navy)),
                        _RequestStatus('${s['requestStatus'] ?? s['status']}'),
                      ]),
                    ),
                  ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _RequestStatus extends StatelessWidget {
  const _RequestStatus(this.status);

  final String status;

  @override
  Widget build(BuildContext context) {
    final (label, color) = switch (status) {
      'APPROVED' => ('Təsdiqlənib', Colors.green),
      'REJECTED' => ('Rədd edilib', Colors.red),
      _ => ('Gözləyir', Colors.orange),
    };
    return Text(label, style: TextStyle(color: color.shade700, fontSize: 12, fontWeight: FontWeight.w600));
  }
}

class _RequestDetail extends StatefulWidget {
  const _RequestDetail({required this.session, required this.sub});

  final Session session;
  final Map<String, dynamic> sub;

  @override
  State<_RequestDetail> createState() => _RequestDetailState();
}

class _RequestDetailState extends State<_RequestDetail> {
  bool _busy = false;

  Map<String, dynamic> get s => widget.sub;

  Future<void> _receipt() async {
    try {
      final bytes = Uint8List.fromList(await widget.session.api.getBytes('/api/admin/subscriptions/${s['id']}/receipt'));
      if (!mounted) return;
      final isPdf = bytes.length > 4 && String.fromCharCodes(bytes.take(4)) == '%PDF';
      if (isPdf) {
        final name = 'qebz-${'${s['id']}'.substring(0, 8)}.pdf';
        await SharePlus.instance.share(ShareParams(
          files: [XFile.fromData(bytes, mimeType: 'application/pdf', name: name)],
          fileNameOverrides: [name],
        ));
      } else {
        await showProductImage(context, bytes, 'Qəbz — ${s['storeName'] ?? ''}');
      }
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _approve() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Təsdiq et'),
        content: Text('“${s['storeName']}” üçün ${s['planName']} abunəliyi aktiv ediləcək.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Geri')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Təsdiq et')),
        ],
      ),
    );
    if (ok != true) return;
    await _run(() => widget.session.api.post('/api/admin/subscriptions/${s['id']}/approve'), 'Abunəlik aktiv edildi');
  }

  Future<void> _reject() async {
    final ctrl = TextEditingController();
    final reason = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Rədd et'),
        content: TextField(
          controller: ctrl,
          autofocus: true,
          maxLength: 500,
          decoration: const InputDecoration(labelText: 'Səbəb', hintText: 'Məs: çekdəki məbləğ uyğun deyil'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Geri')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red.shade700),
            onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
            child: const Text('Rədd et'),
          ),
        ],
      ),
    );
    if (reason == null || reason.isEmpty) return;
    await _run(() => widget.session.api.post('/api/admin/subscriptions/${s['id']}/reject', {'reason': reason}), 'Sorğu rədd edildi');
  }

  Future<void> _run(Future<dynamic> Function() call, String done) async {
    setState(() => _busy = true);
    try {
      await call();
      if (!mounted) return;
      _toast(context, done);
      Navigator.pop(context, true);
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final pending = '${s['requestStatus'] ?? s['status']}' == 'PENDING';
    final phone = (s['ownerPhone'] ?? s['storePhone']) as String?;
    Widget row(String label, Object? value) => value == null || '$value'.isEmpty
        ? const SizedBox.shrink()
        : Padding(
            padding: const EdgeInsets.symmetric(vertical: 5),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              SizedBox(width: 120, child: Text(label, style: TextStyle(color: Colors.grey.shade600))),
              Expanded(child: Text('$value', style: const TextStyle(fontWeight: FontWeight.w600))),
            ]),
          );
    const methods = {'CARD': 'Bank kartı', 'M10': 'M10'};
    return Scaffold(
      appBar: AppBar(title: Text('${s['storeName'] ?? 'Sorğu'}')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Expanded(child: Text('${s['planName'] ?? ''}', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: navy))),
                _RequestStatus('${s['requestStatus'] ?? s['status']}'),
              ]),
              const Divider(height: 24),
              row('Paket məbləği', formatMoney(_n(s['amount']))),
              if (s['declaredAmount'] != null) row('Ödənilən', formatMoney(_n(s['declaredAmount']))),
              row('Ödəniş üsulu', methods[s['paymentMethod']] ?? s['paymentMethod']),
              row('Ödəyən', s['payerName']),
              row('Ödəniş tarixi', _d(s['paymentDate']) == null ? null : formatDate(_d(s['paymentDate']))),
              row('Əməliyyat kodu', s['payerReference']),
              row('Qeyd', s['note']),
              row('Sorğu tarixi', _d(s['requestedAt']) == null ? null : formatDateTime(_d(s['requestedAt'])!)),
              if (s['endDate'] != null) row('Bitmə tarixi', formatDate(_d(s['endDate']))),
              row('Rədd səbəbi', s['rejectReason']),
              row('Baxan', s['reviewedByName']),
            ]),
          ),
        ),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('Mağaza sahibi', style: TextStyle(fontWeight: FontWeight.w700, color: navy)),
              const SizedBox(height: 6),
              row('Ad', s['ownerName']),
              row('E-poçt', s['ownerEmail']),
              row('Telefon', phone),
              if (phone != null && phone.isNotEmpty)
                Wrap(spacing: 8, children: [
                  OutlinedButton.icon(
                    onPressed: () => _launch(context, Uri(scheme: 'tel', path: phone.replaceAll(RegExp(r'[^+\d]'), ''))),
                    icon: const Icon(Icons.call_outlined),
                    label: const Text('Zəng'),
                  ),
                  if (waNumber(phone) != null)
                    OutlinedButton.icon(
                      onPressed: () => _launch(context, waUri(phone, 'Salam! HesabPro abunəlik sorğunuz haqqında yazırıq.')!),
                      icon: const Icon(Icons.chat_outlined),
                      label: const Text('WhatsApp'),
                    ),
                ]),
            ]),
          ),
        ),
        const SizedBox(height: 8),
        if (s['hasReceipt'] == true)
          OutlinedButton.icon(onPressed: _receipt, icon: const Icon(Icons.image_outlined), label: const Text('Qəbzə bax'))
        else
          Text('Qəbz əlavə edilməyib', style: TextStyle(color: Colors.grey.shade600)),
        if (pending) ...[
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: _busy ? null : _approve,
            style: FilledButton.styleFrom(backgroundColor: Colors.green.shade700),
            icon: const Icon(Icons.check),
            label: const Text('Təsdiq et'),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: _busy ? null : _reject,
            style: OutlinedButton.styleFrom(foregroundColor: Colors.red.shade700),
            icon: const Icon(Icons.close),
            label: const Text('Rədd et'),
          ),
        ],
      ]),
    );
  }
}

// ---------------------------------------------------------------- expiring subscriptions

const _soonDays = 7;
const _expiredLookbackDays = 30;
const _recentReminderDays = 3;

/// Stores whose subscription ends within a week or ended in the last 30 days, with a ready reminder.
class ExpiringSubscriptionsScreen extends StatefulWidget {
  const ExpiringSubscriptionsScreen({super.key, required this.session});

  final Session session;

  @override
  State<ExpiringSubscriptionsScreen> createState() => _ExpiringSubscriptionsScreenState();
}

class _ExpiringSubscriptionsScreenState extends State<ExpiringSubscriptionsScreen> {
  late Future<List<Map<String, dynamic>>> _items;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _items = widget.session.api.get('/api/admin/stores').then((j) => (j as List).cast<Map<String, dynamic>>());
  }

  Future<void> _refresh() async {
    setState(_load);
    await _items.catchError((Object _) => <Map<String, dynamic>>[]);
  }

  /// 0 = today/tomorrow, 1 = within a week, 2 = ended recently; null = not in this list.
  static int? _group(Map<String, dynamic> s, DateTime now) {
    final end = _d(s['expiresAt']);
    if (s['subscriptionState'] == 'NONE' || end == null) return null;
    final left = end.difference(now);
    if (left.isNegative) return left.inDays > -_expiredLookbackDays ? 2 : null;
    if (left.inHours <= 48) return 0;
    if (left.inDays < _soonDays) return 1;
    return null;
  }

  static bool _recent(Map<String, dynamic> s, DateTime now) {
    final at = _d(s['lastRemindedAt']);
    return at != null && now.difference(at).inDays < _recentReminderDays;
  }

  String _text(Map<String, dynamic> s, bool expired) {
    final owner = s['ownerName'] as String?;
    final hello = owner == null || owner.isEmpty ? 'Salam!' : 'Salam, $owner!';
    final end = formatDate(_d(s['subscriptionEnd']));
    final body = expired
        ? 'HesabPro-da “${s['name']}” mağazanızın abunəliyi $end tarixində bitib. Məlumatlarınız qorunur — abunəliyi yeniləyən kimi hər şey əvvəlki kimi işləyəcək.'
        : 'HesabPro-da “${s['name']}” mağazanızın abunəliyi $end tarixində bitir. İşinizdə fasilə olmasın deyə vaxtında yeniləməyi xatırladırıq.';
    return '$hello\n$body\nYeniləmək üçün: Profil → Abunəlik bölməsində paketi seçib ödəniş qəbzini göndərin. Sualınız olsa, bu nömrəyə yazın.\nHesabPro';
  }

  Future<void> _remind(Map<String, dynamic> s, String channel, bool expired) async {
    final phone = (s['ownerPhone'] ?? s['phone']) as String?;
    final text = _text(s, expired);
    final Uri uri;
    if (channel == 'WHATSAPP') {
      uri = waUri(phone, text)!;
    } else if (channel == 'PHONE') {
      uri = Uri(scheme: 'tel', path: (phone ?? '').replaceAll(RegExp(r'[^+\d]'), ''));
    } else {
      uri = Uri(scheme: 'mailto', path: '${s['ownerEmail']}', query: Uri(queryParameters: {
        'subject': expired ? 'HesabPro abunəliyiniz bitib' : 'HesabPro abunəliyiniz bitir',
        'body': text,
      }).query);
    }
    await _launch(context, uri);
    try {
      await widget.session.api.post('/api/admin/stores/${s['id']}/remind', {'channel': channel});
      if (mounted) setState(() => s['lastRemindedAt'] = DateTime.now().toIso8601String());
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _extend(Map<String, dynamic> s) async {
    final days = await showModalBottomSheet<int>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text('“${s['name']}” — neçə gün uzadılsın?', style: const TextStyle(fontWeight: FontWeight.w600)),
          ),
          for (final d in const [3, 7, 30])
            ListTile(leading: const Icon(Icons.more_time), title: Text('$d gün'), onTap: () => Navigator.pop(ctx, d)),
        ]),
      ),
    );
    if (days == null) return;
    try {
      await widget.session.api.post('/api/admin/stores/${s['id']}/extend', {'days': days});
      if (!mounted) return;
      _toast(context, 'Abunəlik $days gün uzadıldı');
      _refresh();
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    const titles = ['Bu gün və sabah bitir', '$_soonDays gün içində bitir', 'Vaxtı bitib (son $_expiredLookbackDays gün)'];
    return Scaffold(
      appBar: AppBar(title: const Text('Bitən abunəliklər')),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<Map<String, dynamic>>>(
          future: _items,
          builder: (context, snap) {
            final now = DateTime.now();
            final groups = [<Map<String, dynamic>>[], <Map<String, dynamic>>[], <Map<String, dynamic>>[]];
            for (final s in snap.data ?? const <Map<String, dynamic>>[]) {
              final g = _group(s, now);
              if (g != null) groups[g].add(s);
            }
            for (var g = 0; g < 3; g++) {
              groups[g].sort((a, b) {
                final r = (_recent(a, now) ? 1 : 0) - (_recent(b, now) ? 1 : 0);
                if (r != 0) return r;
                final ea = _d(a['expiresAt'])!, eb = _d(b['expiresAt'])!;
                return g == 2 ? eb.compareTo(ea) : ea.compareTo(eb);
              });
            }
            final empty = groups.every((g) => g.isEmpty);
            return ListView(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
              children: [
                if (snap.hasError) ErrorBanner(errorMessage(snap.error!)),
                if (snap.connectionState != ConnectionState.done) const Padding(padding: EdgeInsets.all(32), child: Center(child: CircularProgressIndicator())),
                if (snap.connectionState == ConnectionState.done && !snap.hasError && empty)
                  const EmptyState(title: 'Bitmək üzrə olan abunəlik yoxdur', text: 'Növbəti $_soonDays gündə heç bir abunəlik bitmir.'),
                for (var g = 0; g < 3; g++)
                  if (groups[g].isNotEmpty) ...[
                    Padding(
                      padding: const EdgeInsets.fromLTRB(4, 12, 4, 6),
                      child: Text('${titles[g]} · ${groups[g].length}',
                          style: TextStyle(fontWeight: FontWeight.w700, color: g == 1 ? navy : Colors.red.shade700)),
                    ),
                    for (final s in groups[g]) _storeCard(s, g == 2, now),
                  ],
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _storeCard(Map<String, dynamic> s, bool expired, DateTime now) {
    final phone = (s['ownerPhone'] ?? s['phone']) as String?;
    final end = _d(s['expiresAt'])!;
    final left = end.difference(now);
    final when = expired
        ? '${(-left.inDays).clamp(1, 999)} gün əvvəl bitib'
        : left.inHours < 24
            ? '${left.inHours} saat qalıb'
            : '${left.inDays} gün qalıb';
    final reminded = _d(s['lastRemindedAt']);
    final recent = _recent(s, now);
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(child: Text('${s['name']}', style: const TextStyle(fontWeight: FontWeight.w700, color: navy))),
            Text(when, style: TextStyle(fontWeight: FontWeight.w700, color: Colors.red.shade700, fontSize: 13)),
          ]),
          const SizedBox(height: 2),
          Text(
            [s['activePlan'], s['ownerName'], phone, 'Bitmə: ${formatDate(_d(s['subscriptionEnd']))}']
                .whereType<String>()
                .where((t) => t.isNotEmpty)
                .join(' · '),
            style: TextStyle(color: Colors.grey.shade600, fontSize: 12),
          ),
          if (s['pendingPlanCode'] != null || reminded != null || s['status'] != 'ACTIVE')
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Wrap(spacing: 6, runSpacing: 4, children: [
                if (s['pendingPlanCode'] != null) _chip('Ödəniş sorğusu göndərib', Colors.orange),
                if (s['status'] != 'ACTIVE') _chip('Dayandırılıb', Colors.red),
                if (reminded != null) _chip('Xatırladıldı: ${formatDateTime(reminded)}', recent ? Colors.green : Colors.blueGrey),
              ]),
            ),
          const SizedBox(height: 4),
          Wrap(spacing: 4, children: [
            if (waNumber(phone) != null)
              TextButton.icon(
                onPressed: () => _remind(s, 'WHATSAPP', expired),
                icon: const Icon(Icons.chat_outlined, size: 18),
                label: Text(recent ? 'Yenə yaz' : 'WhatsApp'),
              ),
            if (phone != null && phone.isNotEmpty)
              TextButton.icon(onPressed: () => _remind(s, 'PHONE', expired), icon: const Icon(Icons.call_outlined, size: 18), label: const Text('Zəng')),
            if (s['ownerEmail'] != null)
              TextButton.icon(onPressed: () => _remind(s, 'EMAIL', expired), icon: const Icon(Icons.mail_outline, size: 18), label: const Text('E-poçt')),
            if (s['activePlan'] != null)
              TextButton.icon(onPressed: () => _extend(s), icon: const Icon(Icons.more_time, size: 18), label: const Text('Uzat')),
          ]),
        ]),
      ),
    );
  }

  Widget _chip(String text, MaterialColor color) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(999)),
        child: Text(text, style: TextStyle(color: color.shade800, fontSize: 11, fontWeight: FontWeight.w600)),
      );
}

// ---------------------------------------------------------------- account

class _AdminAccount extends StatelessWidget {
  const _AdminAccount({required this.session});

  final Session session;

  @override
  Widget build(BuildContext context) {
    final me = session.me!;
    return Scaffold(
      appBar: AppBar(title: const Text('Hesab')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Card(
          child: ListTile(
            leading: const CircleAvatar(backgroundColor: navy, child: Icon(Icons.admin_panel_settings, color: gold)),
            title: Text(me.fullName, style: const TextStyle(fontWeight: FontWeight.w600)),
            subtitle: Text('${me.email}\nPlatforma administratoru'),
            isThreeLine: true,
          ),
        ),
        Card(
          child: Column(children: [
            ListTile(
              leading: const Icon(Icons.person_outline),
              title: const Text('Profil və şifrə'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => ProfileScreen(session: session))),
            ),
            ListTile(
              leading: const Icon(Icons.open_in_new),
              title: const Text('Tam admin paneli (veb)'),
              subtitle: const Text('Paketlər, gəlir, istifadəçilər, audit jurnalı'),
              onTap: () => _launch(context, Uri.parse('https://hesabpro.cloud/admin')),
            ),
          ]),
        ),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          onPressed: session.logout,
          style: OutlinedButton.styleFrom(foregroundColor: Colors.red.shade700),
          icon: const Icon(Icons.logout),
          label: const Text('Çıxış'),
        ),
      ]),
    );
  }
}
