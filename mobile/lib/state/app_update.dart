import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;

/// A newer HesabPro APK on the "mobile-latest" release.
class AppUpdateInfo {
  const AppUpdateInfo({required this.build, required this.version, required this.url, required this.size});

  final int build;
  final String version;
  final String url;
  final int size;
}

/// In-app updates on Android: compare the installed build with the latest released APK, download it with
/// progress and hand it to the Android installer (same signing key → installs over this one, data kept).
class AppUpdate {
  static const _channel = MethodChannel('hesabpro/update');
  static const _release = 'https://api.github.com/repos/BTB60/borcdefderi/releases/tags/mobile-latest';

  static bool get supported => !kIsWeb && Platform.isAndroid && !kDebugMode;

  /// The newer version, or null when this one is the latest (or we cannot tell).
  static Future<AppUpdateInfo?> check() async {
    if (!supported) return null;
    try {
      final current = await _channel.invokeMethod<int>('versionCode') ?? 0;
      final res = await http.get(Uri.parse(_release), headers: {'Accept': 'application/vnd.github+json'})
          .timeout(const Duration(seconds: 15));
      if (res.statusCode != 200) return null;
      final j = jsonDecode(res.body) as Map<String, dynamic>;
      final m = RegExp(r'v(\d+)\.(\d+)\.(\d+)').firstMatch('${j['name']}');
      if (m == null) return null;
      final build = int.parse(m.group(3)!);
      final asset = ((j['assets'] as List?) ?? const []).cast<Map<String, dynamic>>()
          .where((a) => '${a['name']}'.endsWith('.apk')).firstOrNull;
      if (asset == null || build <= current) return null;
      return AppUpdateInfo(build: build, version: m.group(0)!.substring(1), url: '${asset['browser_download_url']}',
          size: (asset['size'] as num?)?.toInt() ?? 0);
    } catch (_) {
      return null;
    }
  }

  /// Downloads the APK (progress 0..1) and returns the file path.
  static Future<String> download(AppUpdateInfo info, void Function(double progress) onProgress) async {
    final dir = await _channel.invokeMethod<String>('cacheDir');
    final file = File('$dir/HesabPro-${info.build}.apk');
    if (await file.exists() && info.size > 0 && await file.length() == info.size) {
      onProgress(1);
      return file.path;
    }
    final client = http.Client();
    try {
      final res = await client.send(http.Request('GET', Uri.parse(info.url)));
      if (res.statusCode != 200) throw HttpException('Yükləmə alınmadı (${res.statusCode})');
      final total = res.contentLength ?? info.size;
      var got = 0;
      final sink = file.openWrite();
      await for (final chunk in res.stream) {
        sink.add(chunk);
        got += chunk.length;
        if (total > 0) onProgress(got / total);
      }
      await sink.close();
      return file.path;
    } finally {
      client.close();
    }
  }

  /// Opens the Android installer. 'permission' = the user must first allow installs from HesabPro.
  static Future<String?> install(String path) => _channel.invokeMethod<String>('install', {'path': path});
}
