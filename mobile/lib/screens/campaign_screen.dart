import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import '../whatsapp.dart';
import 'common.dart';

double _n(Object? v) => (v as num?)?.toDouble() ?? 0;

const _filters = {
  'ALL': 'Bütün müştərilər',
  'NO_DEBT': 'Borcu olmayanlar',
  'HAS_DEBT': 'Borcu olanlar',
  'INACTIVE': '60 gündür alış etməyənlər',
  'BONUS': 'Bonusu olanlar',
};

/// WhatsApp kampaniyası: one text ({ad}, {borc}, {bonus}, {mağaza}) to chosen customers, opened one by one;
/// birthday greetings on top.
class CampaignScreen extends StatefulWidget {
  const CampaignScreen({super.key, required this.session});

  final Session session;

  @override
  State<CampaignScreen> createState() => _CampaignScreenState();
}

class _CampaignScreenState extends State<CampaignScreen> {
  String _filter = 'ALL';
  final _text = TextEditingController(text: 'Salam, {ad}! {mağaza}-da bu həftə bütün mallara 10% endirim var. Sizi gözləyirik!');
  late Future<List<Map<String, dynamic>>> _list = _load();
  late final Future<List<Map<String, dynamic>>> _birthdays = widget.session.api
      .get('/api/campaign/birthdays', query: {'days': '7'}).then((j) => (j as List).cast<Map<String, dynamic>>());
  final _sent = <String>{};

  String get _store => widget.session.me?.storeName ?? 'Mağazamız';

  Future<List<Map<String, dynamic>>> _load() async =>
      ((await widget.session.api.get('/api/campaign/recipients', query: {'filter': _filter})) as List).cast<Map<String, dynamic>>();

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  String _fill(Map<String, dynamic> r) => _text.text
      .replaceAll('{ad}', '${r['name']}')
      .replaceAll('{mağaza}', _store)
      .replaceAll('{borc}', formatMoney(_n(r['openBalance'])))
      .replaceAll('{bonus}', formatMoney(_n(r['bonusBalance'])));

  Future<void> _open(String? phone, String text, {String? id}) async {
    final uri = waUri(phone, text);
    if (uri == null) return;
    if (await launchUrl(uri, mode: LaunchMode.externalApplication) && id != null && mounted) setState(() => _sent.add(id));
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('WhatsApp kampaniyası')),
        body: ListView(padding: const EdgeInsets.all(12), children: [
          FutureBuilder<List<Map<String, dynamic>>>(
            future: _birthdays,
            builder: (context, snap) {
              final list = snap.data ?? const [];
              if (list.isEmpty) return const SizedBox.shrink();
              return Card(
                child: Column(children: [
                  const ListTile(title: Text('🎂 Ad günləri (7 gün)', style: TextStyle(fontWeight: FontWeight.w800, color: navy))),
                  for (final b in list)
                    ListTile(
                      dense: true,
                      title: Text('${b['name']}'),
                      subtitle: Text(b['daysUntil'] == 0 ? 'Bu gün' : '${b['daysUntil']} gün sonra'),
                      trailing: TextButton(
                        onPressed: () => _open(b['phone'] as String?,
                            'Salam, ${b['name']}! $_store kollektivi olaraq ad gününüzü təbrik edirik! 🎂 Sizə cansağlığı və uğurlar arzulayırıq!'),
                        child: const Text('Təbrik et'),
                      ),
                    ),
                ]),
              );
            },
          ),
          DropdownButtonFormField<String>(
            initialValue: _filter,
            decoration: const InputDecoration(labelText: 'Kimə'),
            items: [for (final e in _filters.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
            onChanged: (v) => setState(() {
              _filter = v!;
              _sent.clear();
              _list = _load();
            }),
          ),
          const SizedBox(height: 10),
          TextField(
            controller: _text,
            maxLines: 4,
            maxLength: 1000,
            onChanged: (_) => setState(() {}),
            decoration: const InputDecoration(labelText: 'Mesaj', helperText: '{ad}, {borc}, {bonus}, {mağaza} avtomatik dəyişir', helperMaxLines: 2),
          ),
          FutureBuilder<List<Map<String, dynamic>>>(
            future: _list,
            builder: (context, snap) {
              if (snap.hasError) return ErrorBanner(errorMessage(snap.error!));
              if (!snap.hasData) return const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()));
              final list = snap.data!;
              if (list.isEmpty) return const EmptyState(title: 'Telefonu olan müştəri yoxdur');
              return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${list.length} müştəri · göndərilib ${_sent.length}', style: const TextStyle(fontWeight: FontWeight.w700)),
                for (final r in list)
                  Card(
                    child: ListTile(
                      title: Text('${r['name']}'),
                      subtitle: Text('${r['phone']}${_n(r['openBalance']) > 0 ? ' · borc ${formatMoney(_n(r['openBalance']))}' : ''}'),
                      trailing: _sent.contains('${r['id']}')
                          ? Icon(Icons.check_circle, color: Colors.green.shade600)
                          : FilledButton(
                              style: FilledButton.styleFrom(backgroundColor: const Color(0xFF25D366), visualDensity: VisualDensity.compact),
                              onPressed: () => _open(r['phone'] as String?, _fill(r), id: '${r['id']}'),
                              child: const Text('Göndər'),
                            ),
                    ),
                  ),
              ]);
            },
          ),
        ]),
      );
}
