import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import '../whatsapp.dart';
import 'common.dart';
import 'party_profile_screen.dart';

double _n(Object? v) => (v as num?)?.toDouble() ?? 0;

/// Avtomatik borc xatırlatmaları: the system lists every day whose debt is due soon or overdue;
/// one tap opens WhatsApp / SMS with the ready message.
class RemindersScreen extends StatefulWidget {
  const RemindersScreen({super.key, required this.session});

  final Session session;

  @override
  State<RemindersScreen> createState() => _RemindersScreenState();
}

class _RemindersScreenState extends State<RemindersScreen> {
  String _status = 'PENDING';
  late Future<List<Map<String, dynamic>>> _data = _fetch();

  Future<List<Map<String, dynamic>>> _fetch() async =>
      ((await widget.session.api.get('/api/reminders', query: {'status': _status})) as List).cast<Map<String, dynamic>>();

  void _reload() => setState(() => _data = _fetch());

  Map<String, dynamic>? _sms;

  @override
  void initState() {
    super.initState();
    _loadSms();
  }

  Future<void> _loadSms() async {
    try {
      final s = await widget.session.api.get('/api/settings/sms') as Map<String, dynamic>;
      if (mounted) setState(() => _sms = s);
    } catch (_) {}
  }

  /// "Hamısını SMS ilə göndər": the platform's SMS provider sends every pending reminder now.
  Future<void> _sendAllSms() async {
    try {
      final r = await widget.session.api.post('/api/reminders/send-sms') as Map<String, dynamic>;
      if (!mounted) return;
      final n = (r['sent'] as num?)?.toInt() ?? 0;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(n > 0 ? '$n xatırlatma SMS ilə göndərildi' : 'Heç bir SMS göndərilmədi (telefonu olan xatırlatma yoxdur və ya limit bitib)')));
      _reload();
      _loadSms();
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _mark(Map<String, dynamic> r, String action, [String? channel]) async {
    try {
      await widget.session.api.post('/api/reminders/${r['id']}/$action', action == 'sent' ? {'channel': channel} : {});
      _reload();
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _send(Map<String, dynamic> r, String channel) async {
    final text = '${r['message']}';
    final phone = r['customerPhone'] as String?;
    final Uri? uri = channel == 'WHATSAPP'
        ? waUri(phone, text)
        : (phone == null || phone.trim().isEmpty)
            ? null
            : Uri(scheme: 'sms', path: phone.replaceAll(RegExp(r'[^+0-9]'), ''), queryParameters: {'body': text});
    if (uri == null) return showError(context, 'Müştərinin telefon nömrəsi yoxdur və ya düzgün deyil');
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication)) {
      if (mounted) showError(context, channel == 'WHATSAPP' ? 'WhatsApp açılmadı' : 'SMS açılmadı');
      return;
    }
    await _mark(r, 'sent', channel);
  }

  Future<void> _settings() async {
    try {
      final s = await widget.session.api.get('/api/settings/reminders') as Map<String, dynamic>;
      if (!mounted) return;
      bool enabled = s['enabled'] == true;
      final smsOn = _sms?['configured'] == true;
      bool autoSms = _sms?['autoReminders'] == true;
      int before = (s['daysBefore'] as num).toInt();
      int repeat = (s['repeatDays'] as num).toInt();
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => StatefulBuilder(
          builder: (ctx, set) => AlertDialog(
            title: const Text('Xatırlatma parametrləri'),
            content: Column(mainAxisSize: MainAxisSize.min, children: [
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Avtomatik xatırlatmalar'),
                value: enabled,
                onChanged: (v) => set(() => enabled = v),
              ),
              _stepper('Son tarixdən neçə gün əvvəl', before, 0, 30, (v) => set(() => before = v)),
              _stepper('Gecikmişi neçə gündən bir', repeat, 1, 60, (v) => set(() => repeat = v)),
              const Divider(),
              smsOn
                  ? SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Avtomatik SMS'),
                      subtitle: Text('Hər səhər 9:00-da özü göndərsin · bu ay ${_sms?['sentThisMonth']} / ${_sms?['monthlyLimit']}'),
                      value: autoSms,
                      onChanged: (v) => set(() => autoSms = v),
                    )
                  : Text('Avtomatik SMS: SMS xidməti hələ qoşulmayıb', style: TextStyle(color: Colors.grey.shade600, fontSize: 13)),
            ]),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Ləğv et')),
              FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Saxla')),
            ],
          ),
        ),
      );
      if (ok != true) return;
      await widget.session.api.put('/api/settings/reminders', {'enabled': enabled, 'daysBefore': before, 'repeatDays': repeat});
      if (smsOn) await widget.session.api.put('/api/settings/sms', {'autoReminders': autoSms});
      _reload();
      _loadSms();
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Widget _stepper(String label, int value, int min, int max, ValueChanged<int> onChanged) => Row(children: [
        Expanded(child: Text(label)),
        IconButton(icon: const Icon(Icons.remove_circle_outline), onPressed: value > min ? () => onChanged(value - 1) : null),
        Text('$value', style: const TextStyle(fontWeight: FontWeight.w700)),
        IconButton(icon: const Icon(Icons.add_circle_outline), onPressed: value < max ? () => onChanged(value + 1) : null),
      ]);

  @override
  Widget build(BuildContext context) {
    final owner = widget.session.me?.isOwner ?? false;
    return Scaffold(
      appBar: AppBar(title: const Text('Borc xatırlatmaları'), actions: [
        if (_sms?['configured'] == true && _status == 'PENDING')
          IconButton(tooltip: 'Hamısını SMS ilə göndər', icon: const Icon(Icons.sms_outlined), onPressed: _sendAllSms),
        if (owner) IconButton(tooltip: 'Parametrlər', icon: const Icon(Icons.tune), onPressed: _settings),
      ]),
      body: Column(children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
          child: SegmentedButton<String>(
            segments: const [
              ButtonSegment(value: 'PENDING', label: Text('Göndəriləcək')),
              ButtonSegment(value: 'SENT', label: Text('Göndərilib')),
              ButtonSegment(value: 'SKIPPED', label: Text('Keçilib')),
            ],
            selected: {_status},
            onSelectionChanged: (s) {
              _status = s.first;
              _reload();
            },
          ),
        ),
        Expanded(
          child: FutureBuilder<List<Map<String, dynamic>>>(
            future: _data,
            builder: (context, snap) {
              if (snap.hasError) return ListView(children: [ErrorBanner(errorMessage(snap.error!))]);
              if (!snap.hasData) return const Center(child: CircularProgressIndicator());
              final list = snap.data!;
              return RefreshIndicator(
                onRefresh: () async => _reload(),
                child: list.isEmpty
                    ? ListView(children: [
                        EmptyState(
                          title: _status == 'PENDING' ? 'Bu gün xatırlatma yoxdur' : 'Siyahı boşdur',
                          text: _status == 'PENDING' ? 'Son ödəmə tarixi qoyulmuş borclar yaxınlaşanda burada görünəcək' : null,
                        ),
                      ])
                    : ListView.builder(
                        padding: const EdgeInsets.fromLTRB(12, 8, 12, 24),
                        itemCount: list.length,
                        itemBuilder: (_, i) => _card(list[i]),
                      ),
              );
            },
          ),
        ),
      ]),
    );
  }

  Widget _card(Map<String, dynamic> r) {
    final overdue = r['kind'] == 'OVERDUE';
    final color = overdue ? Colors.red.shade700 : Colors.orange.shade800;
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(
              child: InkWell(
                onTap: () => Navigator.of(context).push(MaterialPageRoute(
                    builder: (_) => PartyProfileScreen(session: widget.session, isCustomer: true, partyId: '${r['customerId']}'))),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('${r['customerName']}', style: const TextStyle(fontWeight: FontWeight.w700, color: navy, fontSize: 16)),
                  Text('${r['customerPhone'] ?? 'telefon yoxdur'} · son tarix ${formatDate(DateTime.tryParse('${r['dueDate']}'))}',
                      style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
                ]),
              ),
            ),
            Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(99)),
                child: Text(overdue ? 'Gecikib' : 'Tarixi yaxınlaşır', style: TextStyle(color: color, fontSize: 11, fontWeight: FontWeight.w700)),
              ),
              const SizedBox(height: 4),
              Text(formatMoney(_n(r['amount'])), style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
            ]),
          ]),
          if (_status == 'PENDING') ...[
            const SizedBox(height: 8),
            Text('${r['message']}', style: TextStyle(color: Colors.grey.shade800, fontSize: 13)),
            const SizedBox(height: 4),
            Wrap(spacing: 4, children: [
              FilledButton.icon(
                style: FilledButton.styleFrom(backgroundColor: const Color(0xFF25D366), visualDensity: VisualDensity.compact),
                onPressed: () => _send(r, 'WHATSAPP'),
                icon: const Icon(Icons.chat, size: 18),
                label: const Text('WhatsApp'),
              ),
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(visualDensity: VisualDensity.compact),
                onPressed: () => _send(r, 'SMS'),
                icon: const Icon(Icons.sms_outlined, size: 18),
                label: const Text('SMS'),
              ),
              TextButton(onPressed: () => _mark(r, 'sent', 'CALL'), child: const Text('Zəng etdim')),
              TextButton(onPressed: () => _mark(r, 'skip'), child: const Text('Keç')),
            ]),
          ] else
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(
                r['status'] == 'SENT'
                    ? '${{'WHATSAPP': 'WhatsApp', 'SMS': 'SMS', 'CALL': 'Zəng'}[r['channel']] ?? 'Digər'} ilə göndərilib'
                    : 'Keçilib',
                style: TextStyle(color: Colors.grey.shade600, fontSize: 12),
              ),
            ),
        ]),
      ),
    );
  }
}
