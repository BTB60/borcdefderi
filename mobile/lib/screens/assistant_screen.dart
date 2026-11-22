import 'package:flutter/material.dart';

import '../api/api_client.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

const _suggestions = [
  'Bu gün nə qədər satış olub?',
  'Ən çox borcu olan 5 müştəri kimdir?',
  'Hansı mallar az qalıb?',
  'Bu ayın mənfəəti nə qədərdir?',
  'Kassada nə qədər pul var?',
];

/// Köməkçi (AI): a chat about the store's own data. Read-only on the server: it never changes anything.
/// The conversation lives only while this screen is open.
class AssistantScreen extends StatefulWidget {
  const AssistantScreen({super.key, required this.session});

  final Session session;

  @override
  State<AssistantScreen> createState() => _AssistantScreenState();
}

class _AssistantScreenState extends State<AssistantScreen> {
  final _text = TextEditingController();
  final _scroll = ScrollController();
  final List<({String role, String text})> _turns = [];
  Map<String, dynamic>? _status;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    widget.session.api.get('/api/assistant').then((s) {
      if (mounted) setState(() => _status = s as Map<String, dynamic>);
    }).catchError((Object e) {
      if (mounted) setState(() => _error = errorMessage(e));
    });
  }

  @override
  void dispose() {
    _text.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _toBottom() => WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_scroll.hasClients) {
          _scroll.animateTo(_scroll.position.maxScrollExtent, duration: const Duration(milliseconds: 200), curve: Curves.easeOut);
        }
      });

  Future<void> _send(String question) async {
    final q = question.trim();
    if (q.isEmpty || _busy) return;
    final before = List.of(_turns);
    setState(() {
      _turns.add((role: 'user', text: q));
      _text.clear();
      _busy = true;
      _error = null;
    });
    _toBottom();
    try {
      final recent = _turns.length > 20 ? _turns.sublist(_turns.length - 20) : _turns;
      final r = await widget.session.api.post('/api/assistant/chat', {
        'messages': [for (final t in recent) {'role': t.role, 'text': t.text}],
      }) as Map<String, dynamic>;
      if (!mounted) return;
      setState(() {
        _turns.add((role: 'assistant', text: '${r['reply']}'));
        _status = {...?_status, 'usedToday': r['usedToday'], 'dailyLimit': r['dailyLimit']};
      });
    } catch (e) {
      if (!mounted) return;
      // the question goes back into the field and out of the history
      setState(() {
        _turns
          ..clear()
          ..addAll(before);
        _text.text = q;
        _error = e is ApiException ? e.message : errorMessage(e);
      });
    } finally {
      if (mounted) setState(() => _busy = false);
      _toBottom();
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = _status;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Köməkçi'),
        actions: [
          if (_turns.isNotEmpty)
            IconButton(tooltip: 'Təmizlə', icon: const Icon(Icons.delete_sweep_outlined), onPressed: () => setState(_turns.clear)),
        ],
      ),
      body: s == null
          ? Center(child: _error != null ? ErrorBanner(_error!) : const CircularProgressIndicator())
          : s['enabled'] != true
              ? const Padding(
                  padding: EdgeInsets.all(20),
                  child: Text('Köməkçi hələ qoşulmayıb. Platforma administratoru qoşandan sonra burada işləyəcək.'),
                )
              : Column(children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                    child: Text('Yalnız baxır və hesablayır, heç nəyi dəyişmir · bu gün ${s['usedToday']} / ${s['dailyLimit']} sual',
                        style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
                  ),
                  Expanded(
                    child: _turns.isEmpty
                        ? ListView(padding: const EdgeInsets.all(16), children: [
                            Text('Məsələn, bunları soruşa bilərsiniz:', style: TextStyle(color: Colors.grey.shade700)),
                            const SizedBox(height: 10),
                            Wrap(spacing: 8, runSpacing: 8, children: [
                              for (final q in _suggestions) ActionChip(label: Text(q), onPressed: () => _send(q)),
                            ]),
                          ])
                        : ListView.builder(
                            controller: _scroll,
                            padding: const EdgeInsets.all(12),
                            itemCount: _turns.length + (_busy ? 1 : 0),
                            itemBuilder: (context, i) {
                              if (i == _turns.length) return const _Bubble(text: 'Yazır…', mine: false, faded: true);
                              final t = _turns[i];
                              return _Bubble(text: t.text, mine: t.role == 'user');
                            },
                          ),
                  ),
                  if (_error != null) Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: ErrorBanner(_error!)),
                  SafeArea(
                    top: false,
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
                      child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
                        Expanded(
                          child: TextField(
                            controller: _text,
                            minLines: 1,
                            maxLines: 4,
                            maxLength: 2000,
                            textInputAction: TextInputAction.send,
                            onSubmitted: _send,
                            decoration: const InputDecoration(hintText: 'Sualınızı yazın…', counterText: ''),
                          ),
                        ),
                        const SizedBox(width: 6),
                        IconButton.filled(
                          tooltip: 'Göndər',
                          onPressed: _busy ? null : () => _send(_text.text),
                          icon: const Icon(Icons.send),
                        ),
                      ]),
                    ),
                  ),
                ]),
    );
  }
}

class _Bubble extends StatelessWidget {
  const _Bubble({required this.text, required this.mine, this.faded = false});

  final String text;
  final bool mine;
  final bool faded;

  @override
  Widget build(BuildContext context) => Align(
        alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
        child: Container(
          constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.82),
          margin: const EdgeInsets.symmetric(vertical: 4),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: mine ? navy : Colors.white,
            borderRadius: BorderRadius.circular(16),
            border: mine ? null : Border.all(color: Colors.grey.shade300),
          ),
          child: SelectableText(text,
              style: TextStyle(color: mine ? Colors.white : (faded ? Colors.grey : Colors.black87), fontSize: 14.5, height: 1.4)),
        ),
      );
}
