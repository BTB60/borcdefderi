import 'package:flutter/material.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

String _time(Object? v) {
  if (v == null) return '—';
  final d = DateTime.parse('$v').toUtc().add(const Duration(hours: 4)); // Asia/Baku, no DST
  return '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
}

String _hours(Object? v) {
  final m = (v as num?)?.toInt() ?? 0;
  return '${m ~/ 60} saat ${m % 60} dəq';
}

String _iso(DateTime d) => '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// İşçi davamiyyəti: "Gəldim" / "Getdim" for everyone; the owner also sees each person's days, lateness and hours.
class AttendanceScreen extends StatefulWidget {
  const AttendanceScreen({super.key, required this.session});

  final Session session;

  @override
  State<AttendanceScreen> createState() => _AttendanceScreenState();
}

class _AttendanceScreenState extends State<AttendanceScreen> {
  Map<String, dynamic>? _current;
  List<Map<String, dynamic>>? _records;
  List<Map<String, dynamic>>? _summary;
  String? _error;
  bool _busy = false;
  final _note = TextEditingController();

  bool get _owner => widget.session.me?.isOwner ?? false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final now = DateTime.now();
    final range = {'from': _iso(DateTime(now.year, now.month, 1)), 'to': _iso(now)};
    try {
      final api = widget.session.api;
      final cur = await api.get('/api/attendance/current');
      final rec = (await api.get('/api/attendance', query: range) as List).cast<Map<String, dynamic>>();
      final sum = _owner ? (await api.get('/api/attendance/summary', query: range) as List).cast<Map<String, dynamic>>() : null;
      if (!mounted) return;
      setState(() {
        _current = cur as Map<String, dynamic>?;
        _records = rec;
        _summary = sum;
        _error = null;
      });
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    }
  }

  Future<void> _clock(String kind) async {
    setState(() => _busy = true);
    try {
      final note = _note.text.trim();
      await widget.session.api.post('/api/attendance/$kind', note.isEmpty ? {} : {'note': note});
      _note.clear();
      await _load();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(kind == 'check-in' ? 'Gəliş qeyd olundu' : 'Gediş qeyd olundu')));
      }
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _workStart() async {
    try {
      final s = await widget.session.api.get('/api/settings/attendance') as Map<String, dynamic>;
      if (!mounted) return;
      final parts = (s['workStart'] as String?)?.split(':');
      final picked = await showTimePicker(
        context: context,
        helpText: 'İşin başlama saatı',
        initialTime: parts == null ? const TimeOfDay(hour: 9, minute: 0) : TimeOfDay(hour: int.parse(parts[0]), minute: int.parse(parts[1])),
      );
      if (picked == null) return;
      await widget.session.api.put('/api/settings/attendance', {
        'workStart': '${picked.hour.toString().padLeft(2, '0')}:${picked.minute.toString().padLeft(2, '0')}',
        'graceMinutes': (s['graceMinutes'] as num?)?.toInt() ?? 10,
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text('İş ${picked.format(context)}-da başlayır; ${(s['graceMinutes'] as num?)?.toInt() ?? 10} dəqiqədən gec gələn gecikmiş sayılır')));
      }
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cur = _current;
    return Scaffold(
      appBar: AppBar(title: const Text('Davamiyyət'), actions: [
        if (_owner) IconButton(tooltip: 'İş saatı', icon: const Icon(Icons.schedule), onPressed: _workStart),
      ]),
      body: RefreshIndicator(
        onRefresh: _load,
        child: ListView(padding: const EdgeInsets.all(16), children: [
          if (_error != null) ErrorBanner(_error!),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                Text(cur != null ? 'İşdəsiniz' : 'Hələ “Gəldim” qeyd etməmisiniz', style: TextStyle(color: Colors.grey.shade600)),
                const SizedBox(height: 4),
                Text(
                  cur != null ? '${_time(cur['checkIn'])}-dən bəri · ${_hours(cur['minutes'])}' : _time(DateTime.now().toUtc().toIso8601String()),
                  style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: navy),
                ),
                if (cur?['late'] == true)
                  Text('Gecikmə ilə gəlmisiniz', style: TextStyle(color: Colors.orange.shade800, fontWeight: FontWeight.w600)),
                const SizedBox(height: 12),
                TextField(controller: _note, maxLength: 300, decoration: const InputDecoration(labelText: 'Qeyd (istəyə görə)', counterText: '')),
                const SizedBox(height: 12),
                cur != null
                    ? OutlinedButton.icon(
                        onPressed: _busy ? null : () => _clock('check-out'),
                        icon: const Icon(Icons.logout),
                        label: const Text('Getdim'),
                        style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(52)),
                      )
                    : FilledButton.icon(
                        onPressed: _busy ? null : () => _clock('check-in'),
                        icon: const Icon(Icons.login),
                        label: const Text('Gəldim'),
                        style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52)),
                      ),
              ]),
            ),
          ),
          if (_summary != null) ...[
            const SizedBox(height: 16),
            const Text('BU AY — İŞÇİLƏR', style: TextStyle(fontSize: 12, letterSpacing: 0.8, fontWeight: FontWeight.w700, color: Colors.grey)),
            const SizedBox(height: 6),
            for (final p in _summary!)
              Card(
                child: ListTile(
                  leading: Icon(Icons.circle, size: 14, color: p['presentNow'] == true ? Colors.green : Colors.grey.shade300),
                  title: Text('${p['fullName']}', style: const TextStyle(fontWeight: FontWeight.w600)),
                  subtitle: Text('${p['days']} gün · ${_hours(p['minutes'])}'
                      '${(p['lateDays'] as num) > 0 ? ' · ${p['lateDays']} dəfə gecikib' : ''}'),
                  trailing: p['presentNow'] == true ? Text('işdədir', style: TextStyle(color: Colors.green.shade700)) : null,
                ),
              ),
          ],
          const SizedBox(height: 16),
          Text(_owner ? 'BU AYIN QEYDLƏRİ' : 'MƏNİM QEYDLƏRİM (BU AY)',
              style: const TextStyle(fontSize: 12, letterSpacing: 0.8, fontWeight: FontWeight.w700, color: Colors.grey)),
          const SizedBox(height: 6),
          if (_records == null && _error == null)
            const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()))
          else if (_records != null && _records!.isEmpty)
            const EmptyState(title: 'Bu ay qeyd yoxdur')
          else if (_records != null)
            for (final r in _records!)
              Card(
                child: ListTile(
                  title: Text('${_owner ? '${r['fullName']} · ' : ''}${formatDate(DateTime.parse('${r['workDate']}'))}', style: const TextStyle(fontWeight: FontWeight.w600)),
                  subtitle: Text('${_time(r['checkIn'])} – ${r['open'] == true ? 'işdədir' : _time(r['checkOut'])}'
                      '${r['note'] != null ? '\n${r['note']}' : ''}'),
                  isThreeLine: r['note'] != null,
                  trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                    Text(_hours(r['minutes']), style: const TextStyle(fontWeight: FontWeight.w600)),
                    if (r['late'] == true) Text('gecikib', style: TextStyle(color: Colors.orange.shade800, fontSize: 12)),
                  ]),
                ),
              ),
        ]),
      ),
    );
  }
}
