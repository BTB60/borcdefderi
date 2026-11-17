import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import '../whatsapp.dart';
import 'common.dart';

const presetTags = ['VIP', 'Zəng et', 'Problemli', 'Maraqlanmır', 'Endirim istəyir', 'Yeni'];

/// "Bu gün" on the admin overview: follow-ups that are due, with WhatsApp / call / done.
class AdminFollowUpsCard extends StatefulWidget {
  const AdminFollowUpsCard({super.key, required this.session});

  final Session session;

  @override
  State<AdminFollowUpsCard> createState() => _AdminFollowUpsCardState();
}

class _AdminFollowUpsCardState extends State<AdminFollowUpsCard> {
  List<Map<String, dynamic>> _notes = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final l = ((await widget.session.api.get('/api/admin/follow-ups')) as List).cast<Map<String, dynamic>>();
      if (mounted) setState(() => _notes = l);
    } catch (_) {}
  }

  Future<void> _done(Map<String, dynamic> n) async {
    setState(() => _notes = _notes.where((x) => x['id'] != n['id']).toList());
    try {
      await widget.session.api.post('/api/admin/notes/${n['id']}/done', {'done': true});
    } catch (_) {
      _load();
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_notes.isEmpty) return const SizedBox.shrink();
    return Card(
      color: Colors.blue.shade50,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('⏰ Bu gün (${_notes.length})', style: const TextStyle(fontWeight: FontWeight.w800, color: navy, fontSize: 16)),
          for (final n in _notes)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${n['storeName']}', style: const TextStyle(fontWeight: FontWeight.w700)),
                Text('${n['body']}'),
                Row(children: [
                  if (n['ownerPhone'] ?? n['storePhone'] != null) ...[
                    TextButton(
                      onPressed: () {
                        final uri = waUri('${n['ownerPhone'] ?? n['storePhone']}', 'Salam! HesabPro dəstək xidmətindən yazırıq.');
                        if (uri != null) launchUrl(uri, mode: LaunchMode.externalApplication);
                      },
                      child: const Text('WhatsApp'),
                    ),
                    TextButton(
                      onPressed: () => launchUrl(Uri(scheme: 'tel', path: '${n['ownerPhone'] ?? n['storePhone']}'.replaceAll(RegExp(r'[^+\d]'), ''))),
                      child: const Text('Zəng'),
                    ),
                  ],
                  const Spacer(),
                  FilledButton.tonal(onPressed: () => _done(n), child: const Text('Edildi ✓')),
                ]),
              ]),
            ),
        ]),
      ),
    );
  }
}

/// Store card → "Qeydlər": the admin's notes with remind-me dates, and the store's tags.
class AdminNotesTab extends StatefulWidget {
  const AdminNotesTab({super.key, required this.session, required this.storeId, required this.tags});

  final Session session;
  final String storeId;
  final List<String> tags;

  @override
  State<AdminNotesTab> createState() => _AdminNotesTabState();
}

class _AdminNotesTabState extends State<AdminNotesTab> {
  late List<String> _tags = [...widget.tags];
  late Future<List<Map<String, dynamic>>> _notes = _load();
  final _body = TextEditingController();
  DateTime? _followUp;

  Future<List<Map<String, dynamic>>> _load() async =>
      ((await widget.session.api.get('/api/admin/stores/${widget.storeId}/notes')) as List).cast<Map<String, dynamic>>();

  @override
  void dispose() {
    _body.dispose();
    super.dispose();
  }

  Future<void> _saveTags(List<String> next) async {
    try {
      final r = await widget.session.api.put('/api/admin/stores/${widget.storeId}/tags', {'tags': next}) as Map<String, dynamic>;
      if (mounted) setState(() => _tags = (r['tags'] as List).cast<String>());
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _add() async {
    if (_body.text.trim().isEmpty) return;
    try {
      await widget.session.api.post('/api/admin/stores/${widget.storeId}/notes', {
        'body': _body.text.trim(),
        if (_followUp != null) 'followUpOn': _followUp!.toIso8601String().substring(0, 10),
      });
      _body.clear();
      if (mounted) {
        setState(() {
          _followUp = null;
          _notes = _load();
        });
        FocusScope.of(context).unfocus();
      }
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _done(Map<String, dynamic> n) async {
    await widget.session.api.post('/api/admin/notes/${n['id']}/done', {'done': n['doneAt'] == null});
    if (mounted) setState(() => _notes = _load());
  }

  @override
  Widget build(BuildContext context) => ListView(padding: const EdgeInsets.all(12), children: [
        const Text('Etiketlər', style: TextStyle(fontWeight: FontWeight.w800, color: navy)),
        const SizedBox(height: 6),
        Wrap(spacing: 6, runSpacing: 4, children: [
          for (final t in {...presetTags, ..._tags})
            FilterChip(
              label: Text(t),
              selected: _tags.contains(t),
              onSelected: (v) => _saveTags(v ? [..._tags, t] : _tags.where((x) => x != t).toList()),
            ),
        ]),
        const Divider(height: 28),
        TextField(
          controller: _body,
          maxLines: 3,
          maxLength: 1000,
          decoration: const InputDecoration(labelText: 'Yeni qeyd', hintText: 'Məs.: Zəng etdim, ayın 20-də ödəyəcək'),
        ),
        Row(children: [
          TextButton.icon(
            icon: const Icon(Icons.alarm, size: 18),
            label: Text(_followUp == null ? 'Xatırlat (istəyə bağlı)' : 'Xatırlat: ${formatDate(_followUp)}'),
            onPressed: () async {
              final now = DateTime.now();
              final d = await showDatePicker(context: context, initialDate: now.add(const Duration(days: 1)), firstDate: now, lastDate: now.add(const Duration(days: 365)));
              if (d != null) setState(() => _followUp = d);
            },
          ),
          const Spacer(),
          FilledButton(onPressed: _add, child: const Text('Saxla')),
        ]),
        const SizedBox(height: 8),
        FutureBuilder<List<Map<String, dynamic>>>(
          future: _notes,
          builder: (context, snap) {
            if (!snap.hasData) return const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()));
            final list = snap.data!;
            if (list.isEmpty) return const EmptyState(title: 'Hələ qeyd yoxdur');
            return Column(children: [
              for (final n in list)
                Card(
                  color: n['doneAt'] != null ? Colors.grey.shade100 : null,
                  child: ListTile(
                    title: Text('${n['body']}',
                        style: TextStyle(decoration: n['doneAt'] != null ? TextDecoration.lineThrough : null)),
                    subtitle: Text([
                      formatDateTime(DateTime.parse('${n['createdAt']}').toLocal()),
                      if (n['followUpOn'] != null) '⏰ ${formatDate(DateTime.tryParse('${n['followUpOn']}'))}',
                    ].join(' · ')),
                    trailing: n['followUpOn'] == null
                        ? null
                        : IconButton(
                            tooltip: n['doneAt'] == null ? 'Edildi' : 'Geri aç',
                            icon: Icon(n['doneAt'] == null ? Icons.check_circle_outline : Icons.undo),
                            onPressed: () => _done(n),
                          ),
                  ),
                ),
            ]);
          },
        ),
      ]);
}
