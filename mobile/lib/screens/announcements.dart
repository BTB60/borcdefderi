import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../state/session.dart';

const _hiddenKey = 'hidden_announcements';

/// Announcements from the platform admin, on top of the home screen until the user closes them.
class AnnouncementBanner extends StatefulWidget {
  const AnnouncementBanner({super.key, required this.session});

  final Session session;

  @override
  State<AnnouncementBanner> createState() => _AnnouncementBannerState();
}

class _AnnouncementBannerState extends State<AnnouncementBanner> {
  List<Map<String, dynamic>> _items = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final list = ((await widget.session.api.get('/api/announcements')) as List).cast<Map<String, dynamic>>();
      final hidden = (await SharedPreferences.getInstance()).getStringList(_hiddenKey) ?? const [];
      if (mounted) setState(() => _items = list.where((a) => !hidden.contains('${a['id']}')).toList());
    } catch (_) {
      // an announcement is never worth an error on the home screen
    }
  }

  Future<void> _close(String id) async {
    setState(() => _items = _items.where((a) => '${a['id']}' != id).toList());
    final prefs = await SharedPreferences.getInstance();
    final hidden = [...(prefs.getStringList(_hiddenKey) ?? const <String>[]), id];
    await prefs.setStringList(_hiddenKey, hidden.length > 50 ? hidden.sublist(hidden.length - 50) : hidden);
  }

  @override
  Widget build(BuildContext context) {
    if (_items.isEmpty) return const SizedBox.shrink();
    return Column(children: [
      for (final a in _items)
        Builder(builder: (context) {
          final color = switch (a['level']) { 'WARNING' => Colors.orange, 'SUCCESS' => Colors.green, _ => Colors.blue };
          return Container(
            margin: const EdgeInsets.only(bottom: 10),
            padding: const EdgeInsets.fromLTRB(12, 10, 4, 10),
            decoration: BoxDecoration(
              color: color.shade50,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: color.shade200),
            ),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Icon(Icons.campaign_outlined, color: color.shade800),
              const SizedBox(width: 10),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('${a['title']}', style: TextStyle(fontWeight: FontWeight.w700, color: color.shade900)),
                  const SizedBox(height: 2),
                  Text('${a['body']}', style: TextStyle(color: color.shade900)),
                ]),
              ),
              IconButton(
                tooltip: 'Bağla',
                visualDensity: VisualDensity.compact,
                icon: const Icon(Icons.close, size: 18),
                onPressed: () => _close('${a['id']}'),
              ),
            ]),
          );
        }),
    ]);
  }
}
