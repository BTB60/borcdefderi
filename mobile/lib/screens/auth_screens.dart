import 'package:flutter/material.dart';

import '../state/session.dart';
import '../theme.dart';
import 'common.dart';
import 'password_screens.dart';

/// Login and registration in one screen with a toggle.
class AuthScreen extends StatefulWidget {
  const AuthScreen({super.key, required this.session});

  final Session session;

  @override
  State<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends State<AuthScreen> {
  final _formKey = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _fullName = TextEditingController();
  final _storeName = TextEditingController();
  final _storePhone = TextEditingController();
  bool _register = false;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    for (final c in [_email, _password, _fullName, _storeName, _storePhone]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      if (_register) {
        await widget.session.register(
          email: _email.text.trim(),
          password: _password.text,
          fullName: _fullName.text.trim(),
          storeName: _storeName.text.trim(),
          storePhone: _storePhone.text.trim(),
        );
      } else {
        await widget.session.login(_email.text.trim(), _password.text);
      }
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
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 440),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(children: [
                      Container(
                        width: 44,
                        height: 44,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(color: navy, borderRadius: BorderRadius.circular(12)),
                        child: const Text('H', style: TextStyle(color: gold, fontSize: 22, fontWeight: FontWeight.w700)),
                      ),
                      const SizedBox(width: 12),
                      const Text('HesabPro', style: TextStyle(color: navy, fontSize: 20, fontWeight: FontWeight.w700, letterSpacing: 0.5)),
                    ]),
                    const SizedBox(height: 28),
                    Text(_register ? 'Mağaza qeydiyyatı' : 'Xoş gəlmisiniz', style: const TextStyle(color: navy, fontSize: 26, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 4),
                    Text(_register ? 'Mağazanız üçün ayrıca iş sahəsi yaradılır' : 'Hesabınıza daxil olun', style: TextStyle(color: Colors.grey.shade600)),
                    const SizedBox(height: 24),
                    if (_error != null) ErrorBanner(_error!),
                    if (_register) ...[
                      TextFormField(
                        controller: _fullName,
                        decoration: const InputDecoration(labelText: 'Ad, soyad'),
                        validator: (v) => (v == null || v.trim().isEmpty) ? 'Ad daxil edin' : null,
                      ),
                      const SizedBox(height: 14),
                      TextFormField(
                        controller: _storeName,
                        decoration: const InputDecoration(labelText: 'Mağazanın adı'),
                        validator: (v) => (v == null || v.trim().isEmpty) ? 'Mağaza adını daxil edin' : null,
                      ),
                      const SizedBox(height: 14),
                      TextFormField(
                        controller: _storePhone,
                        keyboardType: TextInputType.phone,
                        decoration: const InputDecoration(labelText: 'Mağazanın telefonu (istəyə bağlı)'),
                      ),
                      const SizedBox(height: 14),
                    ],
                    TextFormField(
                      controller: _email,
                      keyboardType: TextInputType.emailAddress,
                      autofillHints: const [AutofillHints.email],
                      decoration: InputDecoration(labelText: _register ? 'E-poçt' : 'E-poçt və ya istifadəçi adı'),
                      // Login also accepts a plain user name (e.g. the platform admin "admin").
                      validator: (v) => (v == null || v.trim().isEmpty || (_register && !v.contains('@')))
                          ? (_register ? 'Düzgün e-poçt daxil edin' : 'E-poçt və ya istifadəçi adını daxil edin')
                          : null,
                    ),
                    const SizedBox(height: 14),
                    TextFormField(
                      controller: _password,
                      obscureText: true,
                      decoration: InputDecoration(labelText: 'Şifrə', helperText: _register ? 'Ən azı 8 simvol' : null),
                      validator: (v) => (v == null || v.length < (_register ? 8 : 1)) ? 'Şifrə ən azı ${_register ? 8 : 1} simvol olmalıdır' : null,
                    ),
                    if (!_register)
                      Align(
                        alignment: Alignment.centerRight,
                        child: TextButton(
                          onPressed: _busy
                              ? null
                              : () => Navigator.of(context).push(MaterialPageRoute(
                                  builder: (_) => ForgotPasswordScreen(api: widget.session.api, initialEmail: _email.text.trim()))),
                          child: const Text('Şifrəni unutmusunuz?'),
                        ),
                      ),
                    const SizedBox(height: 12),
                    FilledButton(
                      onPressed: _busy ? null : _submit,
                      child: _busy
                          ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                          : Text(_register ? 'Qeydiyyatdan keç' : 'Daxil ol'),
                    ),
                    const SizedBox(height: 12),
                    TextButton(
                      onPressed: _busy ? null : () => setState(() {
                        _register = !_register;
                        _error = null;
                      }),
                      child: Text(_register ? 'Artıq hesabım var — daxil ol' : 'Mağazanız yoxdur? Qeydiyyatdan keçin'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
