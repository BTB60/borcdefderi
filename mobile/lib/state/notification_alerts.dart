import 'dart:async';
import 'dart:convert';

import 'package:flutter/widgets.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../models/models.dart';
import 'session.dart';

/// Plays the system notification sound and shows a phone notification when the server reports
/// something new (debt due/overdue, subscription news). Checks while the app is open and on resume.
class NotificationAlerts with WidgetsBindingObserver {
  NotificationAlerts(this.session);

  final Session session;

  static const _channelId = 'hesabpro_alerts';
  static const _seenKey = 'bdf_seen_notifications';
  static const _interval = Duration(minutes: 2);

  final _plugin = FlutterLocalNotificationsPlugin();
  final _storage = const FlutterSecureStorage();
  Timer? _timer;
  bool _ready = false;
  bool _checking = false;
  String? _user;

  Future<void> start() async {
    try {
      await _plugin.initialize(
        settings: const InitializationSettings(android: AndroidInitializationSettings('ic_stat_notify')),
      );
      final android = _plugin.resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>();
      await android?.createNotificationChannel(const AndroidNotificationChannel(
        _channelId,
        'HesabPro bildirişləri',
        description: 'Borc son tarixləri və abunəlik xəbərləri',
        importance: Importance.high,
        playSound: true,
        enableVibration: true,
      ));
      _ready = true;
    } catch (_) {
      _ready = false; // e.g. unsupported platform: the app works without alerts
    }
    WidgetsBinding.instance.addObserver(this);
    session.addListener(_onSession);
    _onSession();
  }

  void _onSession() {
    final user = session.me?.email;
    if (user == _user) return;
    _user = user;
    _timer?.cancel();
    if (user == null) return;
    _requestPermission();
    check();
    _timer = Timer.periodic(_interval, (_) => check());
  }

  Future<void> _requestPermission() async {
    if (!_ready) return;
    try {
      await _plugin.resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()?.requestNotificationsPermission();
    } catch (_) {}
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && session.isSignedIn) {
      check();
      _timer ??= Timer.periodic(_interval, (_) => check());
    } else if (state == AppLifecycleState.paused) {
      _timer?.cancel();
      _timer = null;
    }
  }

  /// Fetches notifications and alerts (with sound) about those not alerted before on this device.
  Future<void> check() async {
    final user = _user;
    if (!_ready || user == null || _checking) return;
    _checking = true;
    try {
      final list = (await session.api.get('/api/notifications') as List)
          .map((e) => AppNotification.fromJson(e as Map<String, dynamic>))
          .toList();
      final storageKey = '$_seenKey:$user';
      final seen = <String>{...?(jsonDecode(await _storage.read(key: storageKey) ?? '[]') as List?)?.cast<String>()};
      final fresh = list.where((n) => !seen.contains(n.key)).toList();
      if (fresh.isEmpty || _user != user) return;

      if (fresh.length == 1) {
        await _show(fresh.first.title, fresh.first.message);
      } else {
        await _show('${fresh.length} yeni bildiriş', fresh.map((n) => n.title).toSet().join(' · '));
      }
      // Remember only what the server still reports, so the list never grows without bound.
      final current = list.map((n) => n.key).toList();
      await _storage.write(key: storageKey, value: jsonEncode(current));
    } catch (_) {
      // offline or server asleep: try again on the next tick
    } finally {
      _checking = false;
    }
  }

  Future<void> _show(String title, String body) => _plugin.show(
        id: 1,
        title: title,
        body: body,
        notificationDetails: NotificationDetails(
          android: AndroidNotificationDetails(
            _channelId,
            'HesabPro bildirişləri',
            channelDescription: 'Borc son tarixləri və abunəlik xəbərləri',
            importance: Importance.high,
            priority: Priority.high,
            playSound: true,
            enableVibration: true,
            color: const Color(0xFF0288D1),
            styleInformation: BigTextStyleInformation(body),
          ),
        ),
      );
}
