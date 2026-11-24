import 'dart:async';

import 'package:flutter/material.dart';

import '../api.dart';
import '../main.dart';
import 'cards_screen.dart';

/// Sign-in: the phone and an SMS code, or (without SMS) a card's number and its activation code.
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> with SingleTickerProviderStateMixin {
  final _phone = TextEditingController();
  final _code = TextEditingController();
  final _card = TextEditingController();
  final _cardCode = TextEditingController();
  late final AnimationController _intro = AnimationController(vsync: this, duration: const Duration(milliseconds: 1200))..forward();
  bool? _smsLogin;
  bool _byCard = false;
  bool _codeSent = false;
  bool _busy = false;
  String? _error;
  String? _info;
  /// Seconds until "Kodu yenidən göndər" can be pressed again.
  int _wait = 0;
  Timer? _timer;

  void _startWait() {
    _timer?.cancel();
    setState(() => _wait = 60);
    _timer = Timer.periodic(const Duration(seconds: 1), (t) {
      if (!mounted) return t.cancel();
      setState(() => _wait--);
      if (_wait <= 0) t.cancel();
    });
  }

  Future<void> _sendCode({bool again = false}) => _run(() async {
        await Api.instance.post('/code', {'phone': _phone.text});
        _code.clear();
        setState(() {
          _codeSent = true;
          _info = again ? 'Yeni kod göndərildi' : null;
        });
        _startWait();
      });

  @override
  void initState() {
    super.initState();
    Api.instance.get('/config').then((v) {
      if (mounted) setState(() => _smsLogin = (v as Map)['smsLogin'] == true);
    }).catchError((_) {
      if (mounted) setState(() => _smsLogin = false);
    });
  }

  @override
  void dispose() {
    for (final c in [_phone, _code, _card, _cardCode]) {
      c.dispose();
    }
    _intro.dispose();
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _run(Future<void> Function() f) async {
    setState(() {
      _busy = true;
      _error = null;
      _info = null;
    });
    try {
      await f();
    } on ApiException catch (e) {
      setState(() => _error = e.message);
    } catch (_) {
      setState(() => _error = 'Xəta baş verdi');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _signedIn(Map<String, dynamic> s) async {
    await Api.instance.save('${s['token']}');
    if (!mounted) return;
    Navigator.of(context).pushReplacement(PageRouteBuilder(
      transitionDuration: const Duration(milliseconds: 600),
      pageBuilder: (_, _, _) => const CardsScreen(welcome: true),
      transitionsBuilder: (_, a, _, child) => FadeTransition(opacity: a, child: child),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final sms = _smsLogin == true && !_byCard;
    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(colors: [navy, Color(0xFF3B1F6B)], begin: Alignment.topCenter, end: Alignment.bottomCenter),
        ),
        child: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: FadeTransition(
                opacity: CurvedAnimation(parent: _intro, curve: Curves.easeOut),
                child: SlideTransition(
                  position: Tween(begin: const Offset(0, 0.08), end: Offset.zero).animate(CurvedAnimation(parent: _intro, curve: Curves.easeOutCubic)),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                    const Icon(Icons.credit_card_rounded, size: 64, color: gold),
                    const SizedBox(height: 12),
                    const Text('HesabPro Kart', textAlign: TextAlign.center,
                        style: TextStyle(color: Colors.white, fontSize: 30, fontWeight: FontWeight.w800)),
                    const SizedBox(height: 6),
                    Text('Bütün bonus kartlarınız bir yerdə', textAlign: TextAlign.center,
                        style: TextStyle(color: Colors.white.withValues(alpha: 0.75))),
                    const SizedBox(height: 28),
                    Card(
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
                      child: Padding(
                        padding: const EdgeInsets.all(20),
                        child: _smsLogin == null
                            ? const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()))
                            : Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                                Text(sms ? (_codeSent ? 'SMS kodu' : 'Telefon nömrəniz') : 'Kart ilə daxil olun',
                                    style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: navy)),
                                const SizedBox(height: 14),
                                if (_error != null) ...[
                                  Text(_error!, style: TextStyle(color: Colors.red.shade700)),
                                  const SizedBox(height: 10),
                                ],
                                if (sms && !_codeSent) ...[
                                  TextField(controller: _phone, keyboardType: TextInputType.phone, autofocus: true,
                                      decoration: const InputDecoration(labelText: 'Telefon', hintText: '050 123 45 67', prefixIcon: Icon(Icons.phone))),
                                  const SizedBox(height: 14),
                                  FilledButton(
                                    onPressed: _busy ? null : () => _sendCode(),
                                    child: const Text('Kod göndər'),
                                  ),
                                ] else if (sms) ...[
                                  Text('${_phone.text} nömrəsinə 6 rəqəmli kod göndərildi', style: TextStyle(color: Colors.grey.shade700)),
                                  const SizedBox(height: 10),
                                  TextField(controller: _code, keyboardType: TextInputType.number, autofocus: true, maxLength: 6,
                                      textAlign: TextAlign.center, style: const TextStyle(fontSize: 24, letterSpacing: 8),
                                      decoration: const InputDecoration(counterText: '', hintText: '••••••')),
                                  const SizedBox(height: 14),
                                  FilledButton(
                                    onPressed: _busy ? null : () => _run(() async {
                                      final s = await Api.instance.post('/verify', {'phone': _phone.text, 'code': _code.text});
                                      await _signedIn(s as Map<String, dynamic>);
                                    }),
                                    child: const Text('Daxil ol'),
                                  ),
                                  if (_info != null)
                                    Padding(
                                      padding: const EdgeInsets.only(top: 8),
                                      child: Text(_info!, textAlign: TextAlign.center, style: TextStyle(color: Colors.green.shade700)),
                                    ),
                                  TextButton.icon(
                                    onPressed: _busy || _wait > 0 ? null : () => _sendCode(again: true),
                                    icon: const Icon(Icons.refresh),
                                    label: Text(_wait > 0 ? 'Kodu yenidən göndər ($_wait san.)' : 'Kodu yenidən göndər'),
                                  ),
                                  TextButton(
                                    onPressed: _busy ? null : () => setState(() {
                                      _codeSent = false;
                                      _info = null;
                                    }),
                                    child: const Text('Nömrəni dəyiş'),
                                  ),
                                ] else ...[
                                  TextField(controller: _card, textCapitalization: TextCapitalization.characters, autofocus: true,
                                      decoration: const InputDecoration(labelText: 'Kart nömrəsi', hintText: 'HP-000123', prefixIcon: Icon(Icons.credit_card))),
                                  const SizedBox(height: 10),
                                  TextField(controller: _cardCode, keyboardType: TextInputType.number, maxLength: 6,
                                      decoration: const InputDecoration(labelText: 'Aktivləşdirmə kodu', counterText: '', prefixIcon: Icon(Icons.key))),
                                  const SizedBox(height: 6),
                                  Text('Kodu kartı verən mağaza verir (kartın üzərində və ya WhatsApp mesajında).',
                                      style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
                                  const SizedBox(height: 14),
                                  FilledButton(
                                    onPressed: _busy ? null : () => _run(() async {
                                      final s = await Api.instance.post('/activate', {'cardNumber': _card.text, 'code': _cardCode.text});
                                      await _signedIn(s as Map<String, dynamic>);
                                    }),
                                    child: const Text('Daxil ol'),
                                  ),
                                ],
                                if (_smsLogin == true)
                                  TextButton(
                                    onPressed: _busy ? null : () => setState(() {
                                      _byCard = !_byCard;
                                      _error = null;
                                    }),
                                    child: Text(_byCard ? 'Telefon nömrəsi ilə daxil ol' : 'Kart nömrəsi və kodla daxil ol'),
                                  ),
                              ]),
                      ),
                    ),
                  ]),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
