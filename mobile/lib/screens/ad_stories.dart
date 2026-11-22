import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';

import '../state/session.dart';

const _seenKey = 'seen_ad_stories';
const _ring = Color(0xFF7F77DD);
const _storyLength = Duration(seconds: 5);

/// Story pictures by id and version, so the circle and the full-screen viewer share one download.
final Map<String, Future<Uint8List?>> _images = {};

Future<Uint8List?> _image(Session session, Map<String, dynamic> s) {
  final key = '${s['id']}:${s['imageVersion']}';
  return _images.putIfAbsent(
    key,
    () => session.api
        .getBytes('/api/ads/${s['id']}/image?v=${s['imageVersion']}')
        .then<Uint8List?>((b) => Uint8List.fromList(b))
        .catchError((Object _) => null),
  );
}

String _seenId(Map<String, dynamic> s) => '${s['id']}:${s['imageVersion']}';

/// Reklam hekayələri: circles on top of the home screen. Unseen stories have a coloured ring; tapping one opens
/// the stories full screen. Nothing is shown when there are no live stories (or the server is unreachable).
class AdStoriesBar extends StatefulWidget {
  const AdStoriesBar({super.key, required this.session});

  final Session session;

  @override
  State<AdStoriesBar> createState() => _AdStoriesBarState();
}

class _AdStoriesBarState extends State<AdStoriesBar> {
  List<Map<String, dynamic>> _stories = const [];
  Set<String> _seen = const {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final list = ((await widget.session.api.get('/api/ads')) as List).cast<Map<String, dynamic>>();
      final seen = (await SharedPreferences.getInstance()).getStringList(_seenKey) ?? const <String>[];
      if (!mounted) return;
      // unseen first, in the admin's order
      final unseen = list.where((s) => !seen.contains(_seenId(s))).toList();
      final old = list.where((s) => seen.contains(_seenId(s))).toList();
      setState(() {
        _stories = [...unseen, ...old];
        _seen = seen.toSet();
      });
    } catch (_) {
      // an advert is never worth an error on the home screen
    }
  }

  Future<void> _open(int index) async {
    await Navigator.of(context).push(PageRouteBuilder<void>(
      opaque: true,
      transitionDuration: const Duration(milliseconds: 180),
      pageBuilder: (_, _, _) => AdStoryViewer(session: widget.session, stories: _stories, start: index),
      transitionsBuilder: (_, a, _, child) => FadeTransition(opacity: a, child: child),
    ));
    final seen = (await SharedPreferences.getInstance()).getStringList(_seenKey) ?? const <String>[];
    if (mounted) setState(() => _seen = seen.toSet());
  }

  @override
  Widget build(BuildContext context) {
    if (_stories.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: SizedBox(
        height: 92,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          itemCount: _stories.length,
          separatorBuilder: (_, _) => const SizedBox(width: 12),
          itemBuilder: (context, i) {
            final s = _stories[i];
            final seen = _seen.contains(_seenId(s));
            return GestureDetector(
              onTap: () => _open(i),
              child: SizedBox(
                width: 68,
                child: Column(children: [
                  Container(
                    width: 64,
                    height: 64,
                    padding: const EdgeInsets.all(3),
                    decoration: BoxDecoration(shape: BoxShape.circle, border: Border.all(color: seen ? Colors.grey.shade300 : _ring, width: 2.5)),
                    child: ClipOval(
                      child: FutureBuilder<Uint8List?>(
                        future: _image(widget.session, s),
                        builder: (context, snap) => snap.data == null
                            ? Container(color: const Color(0xFFEEEDFE), child: const Icon(Icons.campaign_outlined, color: _ring))
                            : Image.memory(snap.data!, fit: BoxFit.cover, width: 58, height: 58),
                      ),
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text('${s['title']}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      textAlign: TextAlign.center,
                      style: TextStyle(fontSize: 11, color: seen ? Colors.grey.shade600 : Colors.black87)),
                ]),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// Full-screen stories: progress bars on top, 5 seconds each, tap right / left for next / previous, hold to pause,
/// the button opens the link or WhatsApp. Each story opened is counted once (view) and marked seen.
class AdStoryViewer extends StatefulWidget {
  const AdStoryViewer({super.key, required this.session, required this.stories, required this.start});

  final Session session;
  final List<Map<String, dynamic>> stories;
  final int start;

  @override
  State<AdStoryViewer> createState() => _AdStoryViewerState();
}

class _AdStoryViewerState extends State<AdStoryViewer> with SingleTickerProviderStateMixin {
  late final AnimationController _timer = AnimationController(vsync: this, duration: _storyLength)
    ..addStatusListener((status) {
      if (status == AnimationStatus.completed) _next();
    });
  late int _index = widget.start;
  final Set<String> _counted = {};

  Map<String, dynamic> get _story => widget.stories[_index];

  @override
  void initState() {
    super.initState();
    _show();
  }

  @override
  void dispose() {
    _timer.dispose();
    super.dispose();
  }

  Future<void> _show() async {
    _timer.forward(from: 0);
    final s = _story;
    final id = '${s['id']}';
    if (_counted.add(id)) {
      widget.session.api.post('/api/ads/$id/view').catchError((Object _) => null);
    }
    final prefs = await SharedPreferences.getInstance();
    final seen = [...(prefs.getStringList(_seenKey) ?? const <String>[])];
    if (!seen.contains(_seenId(s))) {
      seen.add(_seenId(s));
      await prefs.setStringList(_seenKey, seen.length > 100 ? seen.sublist(seen.length - 100) : seen);
    }
  }

  void _next() {
    if (_index + 1 >= widget.stories.length) {
      Navigator.of(context).maybePop();
      return;
    }
    setState(() => _index++);
    _show();
  }

  void _previous() {
    if (_index == 0) {
      _timer.forward(from: 0);
      return;
    }
    setState(() => _index--);
    _show();
  }

  Future<void> _action() async {
    final s = _story;
    final link = s['linkUrl'] as String?;
    final wa = s['whatsapp'] as String?;
    final uri = link != null && link.isNotEmpty
        ? Uri.tryParse(link)
        : (wa != null && wa.isNotEmpty ? Uri.parse('https://wa.me/${wa.replaceAll(RegExp(r'\D'), '')}') : null);
    if (uri == null) return;
    _timer.stop();
    widget.session.api.post('/api/ads/${s['id']}/click').catchError((Object _) => null);
    await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (mounted) _timer.forward();
  }

  @override
  Widget build(BuildContext context) {
    final s = _story;
    final hasAction = (s['linkUrl'] as String?)?.isNotEmpty == true || (s['whatsapp'] as String?)?.isNotEmpty == true;
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTapUp: (d) => d.globalPosition.dx < MediaQuery.of(context).size.width / 3 ? _previous() : _next(),
          onLongPressStart: (_) => _timer.stop(),
          onLongPressEnd: (_) => _timer.forward(),
          onVerticalDragEnd: (d) {
            if ((d.primaryVelocity ?? 0) > 300) Navigator.of(context).maybePop();
          },
          child: Stack(children: [
            Positioned.fill(
              child: FutureBuilder<Uint8List?>(
                key: ValueKey(_seenId(s)),
                future: _image(widget.session, s),
                builder: (context, snap) => snap.data == null
                    ? const Center(child: CircularProgressIndicator(color: Colors.white54))
                    : Image.memory(snap.data!, fit: BoxFit.contain),
              ),
            ),
            // progress bars + label + close
            Positioned(
              left: 8,
              right: 8,
              top: 8,
              child: Column(children: [
                Row(children: [
                  for (var i = 0; i < widget.stories.length; i++)
                    Expanded(
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 2),
                        child: AnimatedBuilder(
                          animation: _timer,
                          builder: (_, _) => LinearProgressIndicator(
                            value: i < _index ? 1 : (i == _index ? _timer.value : 0),
                            minHeight: 2.5,
                            backgroundColor: Colors.white24,
                            color: Colors.white,
                            borderRadius: BorderRadius.circular(2),
                          ),
                        ),
                      ),
                    ),
                ]),
                const SizedBox(height: 8),
                Row(children: [
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    decoration: BoxDecoration(color: Colors.white24, borderRadius: BorderRadius.circular(10)),
                    child: const Text('Reklam', style: TextStyle(color: Colors.white, fontSize: 11)),
                  ),
                  const Spacer(),
                  IconButton(
                    tooltip: 'Bağla',
                    icon: const Icon(Icons.close, color: Colors.white),
                    onPressed: () => Navigator.of(context).maybePop(),
                  ),
                ]),
              ]),
            ),
            // title, subtitle, button
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: Container(
                color: Colors.black54,
                padding: const EdgeInsets.fromLTRB(20, 14, 20, 20),
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
                  Text('${s['title']}', style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w700)),
                  if ((s['subtitle'] as String?)?.isNotEmpty == true) ...[
                    const SizedBox(height: 4),
                    Text('${s['subtitle']}', style: const TextStyle(color: Colors.white70, fontSize: 14)),
                  ],
                  if (hasAction) ...[
                    const SizedBox(height: 14),
                    FilledButton.icon(
                      style: FilledButton.styleFrom(backgroundColor: Colors.white, foregroundColor: const Color(0xFF26215C)),
                      onPressed: _action,
                      icon: Icon((s['linkUrl'] as String?)?.isNotEmpty == true ? Icons.open_in_new : Icons.chat_outlined),
                      label: Text('${s['buttonLabel'] ?? 'Ətraflı'}'),
                    ),
                  ],
                ]),
              ),
            ),
          ]),
        ),
      ),
    );
  }
}
