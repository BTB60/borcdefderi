import 'package:flutter/material.dart';

import '../state/app_update.dart';
import '../theme.dart';

/// "Yeni versiya var — Yenilə": shown on top of the app when a newer APK is released. The download starts by
/// itself; when it is done one tap opens the Android installer.
class UpdateBanner extends StatefulWidget {
  const UpdateBanner({super.key});

  @override
  State<UpdateBanner> createState() => _UpdateBannerState();
}

class _UpdateBannerState extends State<UpdateBanner> with WidgetsBindingObserver {
  AppUpdateInfo? _info;
  double? _progress; // null = not started
  String? _path;
  String? _error;
  bool _hidden = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _check();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _info == null) _check();
  }

  Future<void> _check() async {
    final info = await AppUpdate.check();
    if (info == null || !mounted) return;
    setState(() => _info = info);
    _download(); // starts by itself
  }

  Future<void> _download() async {
    final info = _info;
    if (info == null || (_progress != null && _path == null && _error == null)) return;
    setState(() {
      _progress = 0;
      _error = null;
    });
    try {
      final path = await AppUpdate.download(info, (p) {
        if (mounted) setState(() => _progress = p);
      });
      if (mounted) setState(() => _path = path);
    } catch (_) {
      if (mounted) setState(() => _error = 'Yükləmə alınmadı');
    }
  }

  Future<void> _install() async {
    final path = _path;
    if (path == null) return;
    final r = await AppUpdate.install(path);
    if (r == 'permission' && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          duration: Duration(seconds: 6),
          content: Text('"Bu mənbədən icazə ver" seçimini açın, sonra geri qayıdıb yenə "Yenilə" basın')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final info = _info;
    if (info == null || _hidden) return const SizedBox.shrink();
    final ready = _path != null;
    return Material(
      color: navy,
      child: SafeArea(
        top: false,
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 8, 6, 8),
          child: Row(children: [
            Icon(ready ? Icons.system_update : Icons.downloading, color: gold),
            const SizedBox(width: 10),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
                Text(ready ? 'Yeni versiya hazırdır (${info.version})' : 'Yeni versiya yüklənir (${info.version})',
                    style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700)),
                if (_error != null)
                  Text(_error!, style: TextStyle(color: Colors.red.shade200, fontSize: 12))
                else if (!ready)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: LinearProgressIndicator(value: _progress, color: gold, backgroundColor: Colors.white24, minHeight: 4),
                  ),
              ]),
            ),
            const SizedBox(width: 8),
            if (ready)
              FilledButton(
                style: FilledButton.styleFrom(backgroundColor: gold, foregroundColor: navy, visualDensity: VisualDensity.compact),
                onPressed: _install,
                child: const Text('Yenilə', style: TextStyle(fontWeight: FontWeight.w800)),
              )
            else if (_error != null)
              TextButton(onPressed: _download, child: const Text('Yenidən', style: TextStyle(color: gold))),
            IconButton(
              tooltip: 'Sonra',
              icon: const Icon(Icons.close, color: Colors.white54, size: 20),
              onPressed: () => setState(() => _hidden = true),
            ),
          ]),
        ),
      ),
    );
  }
}
