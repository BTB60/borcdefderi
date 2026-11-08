import 'package:flutter/material.dart';

import '../models/models.dart';
import '../state/session.dart';
import 'common.dart';

class NotificationsScreen extends StatefulWidget {
  const NotificationsScreen({super.key, required this.session});

  final Session session;

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends State<NotificationsScreen> {
  late final Future<List<AppNotification>> _items = widget.session.api
      .get('/api/notifications')
      .then((j) => (j as List).map((e) => AppNotification.fromJson(e as Map<String, dynamic>)).toList());

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Bildirişlər')),
      body: FutureBuilder<List<AppNotification>>(
        future: _items,
        builder: (context, snap) {
          if (snap.hasError) return Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!)));
          if (!snap.hasData) return const Center(child: CircularProgressIndicator());
          if (snap.data!.isEmpty) return const EmptyState(title: 'Yeni bildiriş yoxdur');
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              for (final n in snap.data!)
                Card(
                  child: ListTile(
                    leading: Icon(
                      Icons.circle,
                      size: 12,
                      color: switch (n.severity) {
                        'DANGER' => Colors.red,
                        'WARNING' => Colors.orange,
                        _ => Colors.blue,
                      },
                    ),
                    title: Text(n.title, style: const TextStyle(fontWeight: FontWeight.w700)),
                    subtitle: Text(n.message),
                  ),
                ),
            ],
          );
        },
      ),
    );
  }
}
