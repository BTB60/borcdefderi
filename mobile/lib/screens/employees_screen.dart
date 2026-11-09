import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import '../whatsapp.dart';
import 'common.dart';

class Employee {
  final String memberId;
  final String email;
  final String fullName;
  final String? phone;
  final bool active;
  final List<String> permissions;

  const Employee({
    required this.memberId,
    required this.email,
    required this.fullName,
    required this.phone,
    required this.active,
    required this.permissions,
  });

  factory Employee.fromJson(Map<String, dynamic> j) => Employee(
        memberId: j['memberId'] as String,
        email: j['email'] as String? ?? '',
        fullName: j['fullName'] as String? ?? '',
        phone: j['phone'] as String?,
        active: j['active'] as bool? ?? true,
        permissions: ((j['permissions'] as List?) ?? const []).map((e) => e.toString()).toList(),
      );
}

/// Store owner only: list, add, change permissions, activate/deactivate and reset passwords of employees.
class EmployeesScreen extends StatefulWidget {
  const EmployeesScreen({super.key, required this.session});

  final Session session;

  @override
  State<EmployeesScreen> createState() => _EmployeesScreenState();
}

class _EmployeesScreenState extends State<EmployeesScreen> {
  late Future<List<Employee>> _items = _fetch();

  Future<List<Employee>> _fetch() => widget.session.api
      .get('/api/employees')
      .then((j) => (j as List).map((e) => Employee.fromJson(e as Map<String, dynamic>)).toList());

  void _reload() => setState(() => _items = _fetch());

  Future<void> _open([Employee? employee]) async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
        child: _EmployeeForm(session: widget.session, employee: employee),
      ),
    );
    if (saved == true) _reload();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('İşçilər')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _open(),
        icon: const Icon(Icons.person_add_alt_1),
        label: const Text('Yeni işçi'),
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          _reload();
          await _items.catchError((_) => <Employee>[]);
        },
        child: FutureBuilder<List<Employee>>(
          future: _items,
          builder: (context, snap) {
            if (snap.connectionState != ConnectionState.done) return const Center(child: CircularProgressIndicator());
            if (snap.hasError) {
              return ListView(children: [Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!)))]);
            }
            final items = snap.data!;
            if (items.isEmpty) {
              return ListView(children: const [
                EmptyState(title: 'Hələ işçi yoxdur', text: 'İşçi əlavə edin və hansı bölmələri görə biləcəyini seçin.'),
              ]);
            }
            return ListView.separated(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 96),
              itemCount: items.length,
              separatorBuilder: (_, _) => const SizedBox(height: 8),
              itemBuilder: (context, i) {
                final e = items[i];
                return Card(
                  child: ListTile(
                    leading: CircleAvatar(
                      backgroundColor: e.active ? navy : Colors.grey.shade400,
                      foregroundColor: e.active ? gold : Colors.white,
                      child: Text(e.fullName.isEmpty ? '?' : e.fullName.characters.first.toUpperCase()),
                    ),
                    title: Row(children: [
                      Flexible(child: Text(e.fullName, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600))),
                      if (!e.active) ...[
                        const SizedBox(width: 6),
                        Text('deaktiv', style: TextStyle(fontSize: 12, color: Colors.red.shade700)),
                      ],
                    ]),
                    subtitle: Text(
                      '${e.email}\n${e.permissions.isEmpty ? 'İcazə yoxdur' : e.permissions.map((p) => permissionLabels[p] ?? p).join(', ')}',
                    ),
                    isThreeLine: true,
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () => _open(e),
                  ),
                );
              },
            );
          },
        ),
      ),
    );
  }
}

class _EmployeeForm extends StatefulWidget {
  const _EmployeeForm({required this.session, this.employee});

  final Session session;
  final Employee? employee;

  @override
  State<_EmployeeForm> createState() => _EmployeeFormState();
}

class _EmployeeFormState extends State<_EmployeeForm> {
  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _email = TextEditingController();
  final _phone = TextEditingController();
  final _password = TextEditingController();
  late final Set<String> _perms = {...?widget.employee?.permissions};
  late bool _active = widget.employee?.active ?? true;
  bool _busy = false;
  bool _showPassword = false;
  String? _error;

  bool get _isNew => widget.employee == null;

  static final _phonePattern = RegExp(r'^[+0-9 ()-]{0,30}$');
  static final _emailPattern = RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$');

  @override
  void dispose() {
    _name.dispose();
    _email.dispose();
    _phone.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final perms = _perms.toList()..sort();
      if (_isNew) {
        await widget.session.api.post('/api/employees', {
          'fullName': _name.text.trim(),
          'email': _email.text.trim(),
          'password': _password.text,
          'phone': _phone.text.trim().isEmpty ? null : _phone.text.trim(),
          'permissions': perms,
        });
      } else {
        await widget.session.api.put('/api/employees/${widget.employee!.memberId}', {
          'permissions': perms,
          'active': _active,
        });
      }
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _resetPassword() async {
    final e = widget.employee!;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Şifrəni sıfırla'),
        content: Text('${e.fullName} üçün müvəqqəti şifrə yaradılsın? Köhnə şifrə işləməyəcək.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Ləğv et')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Sıfırla')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    setState(() => _busy = true);
    try {
      final res = await widget.session.api.post('/api/employees/${e.memberId}/reset-password') as Map<String, dynamic>;
      if (!mounted) return;
      await _showTemporaryPassword(res);
    } catch (err) {
      if (mounted) setState(() => _error = errorMessage(err));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _showTemporaryPassword(Map<String, dynamic> res) async {
    final temp = res['temporaryPassword'] as String? ?? '';
    final name = res['fullName'] as String? ?? widget.employee!.fullName;
    final email = res['email'] as String? ?? widget.employee!.email;
    final phone = res['phone'] as String? ?? widget.employee!.phone;
    final text = 'Salam, $name! HesabPro üçün müvəqqəti şifrəniz: $temp\nGiriş: $email\n'
        'Daxil olduqdan sonra yeni şifrə təyin etməyiniz tələb olunacaq.';
    final wa = waUri(phone, text);
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Müvəqqəti şifrə'),
        content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('Bu şifrəni işçiyə çatdırın. Bir daha göstərilməyəcək.'),
          const SizedBox(height: 12),
          SelectableText(temp, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: navy, letterSpacing: 1)),
        ]),
        actions: [
          TextButton.icon(
            icon: const Icon(Icons.copy, size: 18),
            label: const Text('Kopyala'),
            onPressed: () {
              Clipboard.setData(ClipboardData(text: temp));
              ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Kopyalandı')));
            },
          ),
          if (wa != null)
            TextButton.icon(
              icon: const Icon(Icons.chat_outlined, size: 18, color: Colors.green),
              label: const Text('WhatsApp'),
              onPressed: () => launchUrl(wa, mode: LaunchMode.externalApplication),
            ),
          FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('Bağla')),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final e = widget.employee;
    return Form(
      key: _formKey,
      child: ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
        Text(_isNew ? 'Yeni işçi' : e!.fullName, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: navy)),
        if (!_isNew) Text(e!.email, style: TextStyle(color: Colors.grey.shade700)),
        const SizedBox(height: 16),
        if (_error != null) ErrorBanner(_error!),
        if (_isNew) ...[
          TextFormField(
            controller: _name,
            textCapitalization: TextCapitalization.words,
            decoration: const InputDecoration(labelText: 'Ad, soyad'),
            validator: (v) => (v ?? '').trim().isEmpty ? 'Adı daxil edin' : null,
          ),
          const SizedBox(height: 14),
          TextFormField(
            controller: _email,
            keyboardType: TextInputType.emailAddress,
            autocorrect: false,
            decoration: const InputDecoration(labelText: 'E-poçt (giriş üçün)'),
            validator: (v) => _emailPattern.hasMatch((v ?? '').trim()) ? null : 'Düzgün e-poçt daxil edin',
          ),
          const SizedBox(height: 14),
          TextFormField(
            controller: _phone,
            keyboardType: TextInputType.phone,
            decoration: const InputDecoration(labelText: 'Telefon (istəyə bağlı)'),
            validator: (v) => _phonePattern.hasMatch((v ?? '').trim()) ? null : 'Telefon nömrəsi düzgün deyil',
          ),
          const SizedBox(height: 14),
          TextFormField(
            controller: _password,
            obscureText: !_showPassword,
            decoration: InputDecoration(
              labelText: 'Şifrə',
              helperText: 'Ən azı 8 simvol',
              suffixIcon: IconButton(
                icon: Icon(_showPassword ? Icons.visibility_off : Icons.visibility),
                onPressed: () => setState(() => _showPassword = !_showPassword),
              ),
            ),
            validator: (v) {
              final n = (v ?? '').length;
              if (n < 8) return 'Şifrə ən azı 8 simvol olmalıdır';
              if (n > 72) return 'Ən çox 72 simvol';
              return null;
            },
          ),
          const SizedBox(height: 16),
        ],
        const Text('İcazələr', style: TextStyle(fontWeight: FontWeight.w700, color: navy)),
        for (final entry in permissionLabels.entries)
          CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            title: Text(entry.value),
            value: _perms.contains(entry.key),
            onChanged: (v) => setState(() => v == true ? _perms.add(entry.key) : _perms.remove(entry.key)),
          ),
        if (!_isNew)
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Aktiv'),
            subtitle: const Text('Deaktiv işçi sistemə daxil ola bilmir'),
            value: _active,
            onChanged: (v) => setState(() => _active = v),
          ),
        const SizedBox(height: 12),
        FilledButton(
          onPressed: _busy ? null : _save,
          child: _busy
              ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : Text(_isNew ? 'İşçini əlavə et' : 'Yadda saxla'),
        ),
        if (!_isNew) ...[
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: _busy ? null : _resetPassword,
            icon: const Icon(Icons.lock_reset),
            label: const Text('Şifrəni sıfırla'),
          ),
        ],
      ]),
    );
  }
}
