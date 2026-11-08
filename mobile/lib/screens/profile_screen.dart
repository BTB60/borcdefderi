import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../api/api_client.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

/// Downloaded profile pictures by version, so every [UserAvatar] shares one download.
final Map<int, Future<Uint8List?>> _avatarCache = {};

/// The signed-in user's profile picture, or their initial.
class UserAvatar extends StatelessWidget {
  const UserAvatar({super.key, required this.session, required this.name, required this.version, this.radius = 22});

  final Session session;
  final String name;
  final int? version;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final initial = Text(
      name.isEmpty ? '?' : name.characters.first.toUpperCase(),
      style: TextStyle(color: navy, fontWeight: FontWeight.w700, fontSize: radius * 0.8),
    );
    final v = version;
    if (v == null) return CircleAvatar(radius: radius, backgroundColor: gold, child: initial);
    final future = _avatarCache.putIfAbsent(
      v,
      () => session.api
          .getBytes('/api/profile/avatar')
          .then<Uint8List?>((b) => Uint8List.fromList(b))
          .catchError((Object _) => null),
    );
    return FutureBuilder<Uint8List?>(
      future: future,
      builder: (context, snap) => snap.data == null
          ? CircleAvatar(radius: radius, backgroundColor: gold, child: initial)
          : CircleAvatar(radius: radius, backgroundImage: MemoryImage(snap.data!)),
    );
  }
}

/// "Parametrlər": profile picture, personal data, store data (owner) and password — for every role.
class ProfileScreen extends StatefulWidget {
  const ProfileScreen({super.key, required this.session});

  final Session session;

  @override
  State<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends State<ProfileScreen> {
  Profile? _profile;
  String? _error;
  bool _busy = false;

  final _fullName = TextEditingController();
  final _phone = TextEditingController();
  final _email = TextEditingController();
  final _emailPassword = TextEditingController();
  final _storeName = TextEditingController();
  final _storePhone = TextEditingController();
  final _storeAddress = TextEditingController();
  final _current = TextEditingController();
  final _next = TextEditingController();
  final _repeat = TextEditingController();

  ApiClient get _api => widget.session.api;

  @override
  void initState() {
    super.initState();
    _load();
    _email.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    for (final c in [_fullName, _phone, _email, _emailPassword, _storeName, _storePhone, _storeAddress, _current, _next, _repeat]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _load() async {
    try {
      _fill(Profile.fromJson(await _api.get('/api/profile') as Map<String, dynamic>));
    } catch (e) {
      setState(() => _error = errorMessage(e));
    }
  }

  void _fill(Profile p) {
    setState(() {
      _profile = p;
      _error = null;
      _fullName.text = p.fullName;
      _phone.text = p.phone ?? '';
      _email.text = p.email;
      _emailPassword.clear();
      _storeName.text = p.storeName ?? '';
      _storePhone.text = p.storePhone ?? '';
      _storeAddress.text = p.storeAddress ?? '';
    });
  }

  Future<void> _run(Future<void> Function() action, String success) async {
    setState(() => _busy = true);
    try {
      await action();
      await widget.session.refresh();
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(success)));
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  bool get _emailChanged => _profile != null && _email.text.trim().toLowerCase() != _profile!.email;

  Future<void> _pickAvatar(ImageSource source) async {
    final picked = await ImagePicker().pickImage(source: source, maxWidth: 1024, maxHeight: 1024, imageQuality: 90);
    if (picked == null) return;
    final bytes = await picked.readAsBytes();
    if (bytes.length > 3 * 1024 * 1024) {
      if (mounted) showError(context, const ApiException(0, 'FILE_TOO_LARGE', 'Şəklin ölçüsü 3 MB-dan çox ola bilməz'));
      return;
    }
    final isPng = bytes.length > 3 && bytes[0] == 0x89 && bytes[1] == 0x50;
    await _run(() async {
      _fill(Profile.fromJson(await _api.upload('/api/profile/avatar', 'file', bytes,
          isPng ? 'avatar.png' : 'avatar.jpg', isPng ? 'image/png' : 'image/jpeg') as Map<String, dynamic>));
    }, 'Profil şəkli yeniləndi');
  }

  void _avatarMenu() {
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          ListTile(
            leading: const Icon(Icons.photo_library_outlined),
            title: const Text('Qalereyadan seç'),
            onTap: () {
              Navigator.pop(ctx);
              _pickAvatar(ImageSource.gallery);
            },
          ),
          ListTile(
            leading: const Icon(Icons.photo_camera_outlined),
            title: const Text('Kamera ilə çək'),
            onTap: () {
              Navigator.pop(ctx);
              _pickAvatar(ImageSource.camera);
            },
          ),
          if (_profile?.hasAvatar ?? false)
            ListTile(
              leading: const Icon(Icons.delete_outline, color: Colors.red),
              title: const Text('Şəkli sil', style: TextStyle(color: Colors.red)),
              onTap: () {
                Navigator.pop(ctx);
                _run(() async => _fill(Profile.fromJson(await _api.delete('/api/profile/avatar') as Map<String, dynamic>)),
                    'Profil şəkli silindi');
              },
            ),
        ]),
      ),
    );
  }

  Future<void> _savePersonal() => _run(() async {
        final body = <String, dynamic>{
          'fullName': _fullName.text.trim(),
          'phone': _phone.text.trim().isEmpty ? null : _phone.text.trim(),
          if (_emailChanged) 'email': _email.text.trim(),
          if (_emailChanged) 'currentPassword': _emailPassword.text,
        };
        _fill(Profile.fromJson(await _api.put('/api/profile', body) as Map<String, dynamic>));
      }, 'Şəxsi məlumatlar yadda saxlanıldı');

  Future<void> _saveStore() => _run(() async {
        _fill(Profile.fromJson(await _api.put('/api/profile/store', {
          'name': _storeName.text.trim(),
          'phone': _storePhone.text.trim().isEmpty ? null : _storePhone.text.trim(),
          'address': _storeAddress.text.trim().isEmpty ? null : _storeAddress.text.trim(),
        }) as Map<String, dynamic>));
      }, 'Mağaza məlumatları yadda saxlanıldı');

  Future<void> _changePassword() async {
    final min = (_profile?.isSuperAdmin ?? false) ? 12 : 8;
    if (_next.text.length < min) {
      showError(context, ApiException(0, 'PASSWORD_TOO_SHORT', 'Yeni şifrə ən azı $min simvol olmalıdır'));
      return;
    }
    if (_next.text != _repeat.text) {
      showError(context, const ApiException(0, 'MISMATCH', 'Yeni şifrələr eyni deyil'));
      return;
    }
    await _run(() async {
      final res = await _api.put('/api/profile/password', {'currentPassword': _current.text, 'newPassword': _next.text})
          as Map<String, dynamic>;
      await widget.session.applyAuthResponse(res);
      _current.clear();
      _next.clear();
      _repeat.clear();
    }, 'Şifrə dəyişdirildi. Digər cihazlarda hesabdan çıxış edildi.');
  }

  @override
  Widget build(BuildContext context) {
    final p = _profile;
    return Scaffold(
      appBar: AppBar(title: const Text('Parametrlər')),
      body: p == null
          ? Center(child: _error != null ? ErrorBanner(_error!) : const CircularProgressIndicator())
          : AbsorbPointer(
              absorbing: _busy,
              child: ListView(padding: const EdgeInsets.all(16), children: [
                Center(
                  child: GestureDetector(
                    onTap: _avatarMenu,
                    child: Stack(children: [
                      UserAvatar(session: widget.session, name: p.fullName, version: p.hasAvatar ? p.avatarVersion : null, radius: 52),
                      Positioned(
                        right: 0,
                        bottom: 0,
                        child: CircleAvatar(radius: 16, backgroundColor: navy, child: _busy
                            ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                            : const Icon(Icons.camera_alt, size: 16, color: Colors.white)),
                      ),
                    ]),
                  ),
                ),
                const SizedBox(height: 8),
                Center(child: Text(p.fullName, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: navy))),
                Center(
                  child: Text(p.isSuperAdmin ? 'Platforma administratoru' : p.isOwner ? 'Mağaza sahibi' : 'İşçi',
                      style: TextStyle(color: Colors.grey.shade600)),
                ),
                const SizedBox(height: 20),
                _section('Şəxsi məlumatlar', [
                  TextField(controller: _fullName, decoration: const InputDecoration(labelText: 'Ad, soyad')),
                  const SizedBox(height: 12),
                  TextField(controller: _phone, keyboardType: TextInputType.phone, decoration: const InputDecoration(labelText: 'Telefon')),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _email,
                    keyboardType: TextInputType.emailAddress,
                    autocorrect: false,
                    decoration: const InputDecoration(labelText: 'E-poçt (giriş adı)'),
                  ),
                  if (_emailChanged) ...[
                    const SizedBox(height: 12),
                    TextField(
                      controller: _emailPassword,
                      obscureText: true,
                      decoration: const InputDecoration(labelText: 'Cari şifrə (e-poçtu dəyişmək üçün)'),
                    ),
                  ],
                  const SizedBox(height: 12),
                  FilledButton(onPressed: _savePersonal, child: const Text('Yadda saxla')),
                ]),
                if (p.isOwner)
                  _section('Mağaza məlumatları', [
                    TextField(controller: _storeName, decoration: const InputDecoration(labelText: 'Mağazanın adı')),
                    const SizedBox(height: 12),
                    TextField(controller: _storePhone, keyboardType: TextInputType.phone, decoration: const InputDecoration(labelText: 'Mağazanın telefonu')),
                    const SizedBox(height: 12),
                    TextField(controller: _storeAddress, decoration: const InputDecoration(labelText: 'Ünvan')),
                    const SizedBox(height: 12),
                    FilledButton(onPressed: _saveStore, child: const Text('Yadda saxla')),
                  ]),
                _section('Şifrəni dəyiş', [
                  TextField(controller: _current, obscureText: true, decoration: const InputDecoration(labelText: 'Cari şifrə')),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _next,
                    obscureText: true,
                    decoration: InputDecoration(labelText: 'Yeni şifrə (ən azı ${p.isSuperAdmin ? 12 : 8} simvol)'),
                  ),
                  const SizedBox(height: 12),
                  TextField(controller: _repeat, obscureText: true, decoration: const InputDecoration(labelText: 'Yeni şifrə (təkrar)')),
                  const SizedBox(height: 12),
                  FilledButton(
                    style: FilledButton.styleFrom(backgroundColor: gold, foregroundColor: navy),
                    onPressed: _changePassword,
                    child: const Text('Şifrəni dəyiş'),
                  ),
                  const SizedBox(height: 6),
                  Text('Şifrə dəyişəndən sonra digər cihazlarda yenidən daxil olmaq lazım olacaq.',
                      style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
                ]),
              ]),
            ),
    );
  }

  Widget _section(String title, List<Widget> children) => Card(
        margin: const EdgeInsets.only(bottom: 16),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(title, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: navy)),
            const SizedBox(height: 12),
            ...children,
          ]),
        ),
      );
}
