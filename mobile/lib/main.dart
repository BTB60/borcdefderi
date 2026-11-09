import 'package:flutter/material.dart';

import 'api/api_client.dart';
import 'config.dart';
import 'screens/auth_screens.dart';
import 'screens/home_shell.dart';
import 'screens/password_screens.dart';
import 'state/session.dart';
import 'theme.dart';

void main() {
  runApp(BorcdefteriApp(session: Session(api: ApiClient(baseUrl: apiBaseUrl), tokens: SecureTokenStore())));
}

class BorcdefteriApp extends StatefulWidget {
  const BorcdefteriApp({super.key, required this.session});

  final Session session;

  @override
  State<BorcdefteriApp> createState() => _BorcdefteriAppState();
}

class _BorcdefteriAppState extends State<BorcdefteriApp> {
  @override
  void initState() {
    super.initState();
    widget.session.restore();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'HesabPro',
      debugShowCheckedModeBanner: false,
      theme: buildTheme(),
      locale: const Locale('az'),
      home: ListenableBuilder(
        listenable: widget.session,
        builder: (context, _) {
          final session = widget.session;
          if (session.restoring) {
            return const Scaffold(body: Center(child: CircularProgressIndicator()));
          }
          if (!session.isSignedIn && session.restoreError != null) {
            return Scaffold(
              body: SafeArea(
                child: Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(mainAxisSize: MainAxisSize.min, children: [
                      const Icon(Icons.cloud_off, size: 48),
                      const SizedBox(height: 12),
                      Text(session.restoreError!, textAlign: TextAlign.center),
                      const SizedBox(height: 16),
                      FilledButton(onPressed: session.restore, child: const Text('Yenidən cəhd et')),
                      TextButton(onPressed: session.logout, child: const Text('Çıxış')),
                    ]),
                  ),
                ),
              ),
            );
          }
          if (!session.isSignedIn) {
            return AuthScreen(session: session);
          }
          if (session.me!.mustChangePassword) {
            return ForcePasswordChangeScreen(session: session);
          }
          return HomeShell(session: session);
        },
      ),
    );
  }
}
