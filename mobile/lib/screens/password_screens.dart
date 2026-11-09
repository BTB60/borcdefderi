import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api/api_client.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

/// "Şifrəni unutmusunuz?": e-mail link (when the server has SMTP) or recovery through the platform admin.
class ForgotPasswordScreen extends StatefulWidget {
  const ForgotPasswordScreen({super.key, required this.api, this.initialEmail = ''});

  final ApiClient api;
  final String initialEmail;

  @override
  State<ForgotPasswordScreen> createState() => _ForgotPasswordScreenState();
}

class _ForgotPasswordScreenState extends State<ForgotPasswordScreen> {
  late final _email = TextEditingController(text: widget.initialEmail);
  bool _busy = false;
  bool? _emailEnabled;
  Contact? _contact;
  String? _error;

  @override
  void initState() {
    super.initState();
    widget.api.get('/api/public/contact').then((j) {
      if (mounted) setState(() => _contact = Contact.fromJson(j as Map<String, dynamic>));
    }).catchError((Object _) {});
  }

  @override
  void dispose() {
    _email.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_email.text.contains('@')) {
      setState(() => _error = 'Düzgün e-poçt daxil edin');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final res = await widget.api.post('/api/auth/forgot-password', {'email': _email.text.trim()}) as Map<String, dynamic>;
      setState(() => _emailEnabled = res['emailEnabled'] as bool? ?? false);
    } catch (e) {
      setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _open(Uri uri) async {
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication) && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Tətbiq açıla bilmədi')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final sent = _emailEnabled != null;
    final contact = _contact;
    return Scaffold(
      appBar: AppBar(title: const Text('Şifrəni unutmusunuz?')),
      body: ListView(padding: const EdgeInsets.all(20), children: [
        Text('Hesabınızın e-poçtunu yazın — şifrəni bərpa etməyə kömək edək.', style: TextStyle(color: Colors.grey.shade700)),
        const SizedBox(height: 16),
        if (_error != null) ErrorBanner(_error!),
        if (!sent) ...[
          TextField(
            controller: _email,
            keyboardType: TextInputType.emailAddress,
            autocorrect: false,
            decoration: const InputDecoration(labelText: 'E-poçt'),
          ),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: _busy ? null : _submit,
            child: _busy
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                : const Text('Şifrəni bərpa et'),
          ),
          const SizedBox(height: 8),
          Text('İşçisinizsə, mağaza sahibi də sizə yeni müvəqqəti şifrə verə bilər.',
              style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
        ] else
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(color: Colors.green.shade50, borderRadius: BorderRadius.circular(12)),
            child: Text(
              _emailEnabled!
                  ? 'Bu e-poçt qeydiyyatdadırsa, bərpa linki göndərildi. Linki 30 dəqiqə ərzində açın (spam qovluğunu da yoxlayın).'
                  : 'Sorğunuz administratora göndərildi. Administrator kimliyinizi təsdiqləyib sizə müvəqqəti şifrə verəcək.',
              style: TextStyle(color: Colors.green.shade900),
            ),
          ),
        if (sent && contact?.phone != null) ...[
          const SizedBox(height: 20),
          Text('Administrator: ${contact!.phone}', style: const TextStyle(fontWeight: FontWeight.w600, color: navy)),
          const SizedBox(height: 8),
          Row(children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: () => _open(Uri(scheme: 'tel', path: contact.phone!.replaceAll(RegExp(r'[^+\d]'), ''))),
                icon: const Icon(Icons.call),
                label: const Text('Zəng et'),
              ),
            ),
            if (contact.whatsapp != null) ...[
              const SizedBox(width: 8),
              Expanded(
                child: FilledButton.icon(
                  style: FilledButton.styleFrom(backgroundColor: Colors.green.shade600),
                  onPressed: () => _open(Uri.https('wa.me', '/${contact.whatsapp}', {
                    'text': 'Salam! HesabPro hesabımın şifrəsini unutmuşam. E-poçtum: ${_email.text.trim()}',
                  })),
                  icon: const Icon(Icons.chat),
                  label: const Text('WhatsApp'),
                ),
              ),
            ],
          ]),
        ],
      ]),
    );
  }
}

/// Shown after logging in with a temporary password: a new password must be chosen before anything else.
class ForcePasswordChangeScreen extends StatefulWidget {
  const ForcePasswordChangeScreen({super.key, required this.session});

  final Session session;

  @override
  State<ForcePasswordChangeScreen> createState() => _ForcePasswordChangeScreenState();
}

class _ForcePasswordChangeScreenState extends State<ForcePasswordChangeScreen> {
  final _current = TextEditingController();
  final _next = TextEditingController();
  final _repeat = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _current.dispose();
    _next.dispose();
    _repeat.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final min = (widget.session.me?.isSuperAdmin ?? false) ? 12 : 8;
    if (_next.text.length < min) {
      setState(() => _error = 'Yeni şifrə ən azı $min simvol olmalıdır');
      return;
    }
    if (_next.text != _repeat.text) {
      setState(() => _error = 'Yeni şifrələr eyni deyil');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final res = await widget.session.api.put('/api/profile/password', {
        'currentPassword': _current.text,
        'newPassword': _next.text,
      }) as Map<String, dynamic>;
      await widget.session.applyAuthResponse(res); // me.mustChangePassword becomes false → app continues
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: ListView(padding: const EdgeInsets.all(24), children: [
          const SizedBox(height: 24),
          const Text('Yeni şifrə təyin edin', style: TextStyle(fontSize: 24, fontWeight: FontWeight.w700, color: navy)),
          const SizedBox(height: 6),
          Text('Siz müvəqqəti şifrə ilə daxil oldunuz. Davam etmək üçün özünüzə yeni şifrə seçin.',
              style: TextStyle(color: Colors.grey.shade700)),
          const SizedBox(height: 20),
          if (_error != null) ErrorBanner(_error!),
          TextField(controller: _current, obscureText: true, decoration: const InputDecoration(labelText: 'Müvəqqəti (cari) şifrə')),
          const SizedBox(height: 12),
          TextField(controller: _next, obscureText: true, decoration: const InputDecoration(labelText: 'Yeni şifrə')),
          const SizedBox(height: 12),
          TextField(controller: _repeat, obscureText: true, decoration: const InputDecoration(labelText: 'Yeni şifrə (təkrar)')),
          const SizedBox(height: 20),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: gold, foregroundColor: navy),
            onPressed: _busy ? null : _save,
            child: _busy
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Yeni şifrəni saxla'),
          ),
          TextButton(onPressed: widget.session.logout, child: const Text('Çıxış')),
        ]),
      ),
    );
  }
}
