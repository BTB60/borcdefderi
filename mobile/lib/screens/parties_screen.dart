import 'dart:async';

import 'package:flutter/material.dart';

import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';
import 'account_sheet.dart';
import 'party_profile_screen.dart';

String _endpoint(bool isCustomer) => isCustomer ? '/api/customers' : '/api/companies';

/// Customers or companies of the store: search, add and edit.
class PartiesScreen extends StatefulWidget {
  const PartiesScreen({super.key, required this.session, required this.isCustomer});

  final Session session;
  final bool isCustomer;

  @override
  State<PartiesScreen> createState() => _PartiesScreenState();
}

class _PartiesScreenState extends State<PartiesScreen> {
  String _query = '';
  Timer? _debounce;
  late Future<List<Party>> _items = _fetch();

  Future<List<Party>> _fetch() => widget.session.api
      .get(_endpoint(widget.isCustomer), query: _query.isEmpty ? null : {'q': _query})
      .then((j) => (j as List).map((e) => Party.fromJson(e as Map<String, dynamic>)).toList());

  void _reload() => setState(() => _items = _fetch());

  void _onSearch(String v) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 350), () {
      _query = v.trim();
      _reload();
    });
  }

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  Future<void> _account(Party party, AccountMode mode) async {
    final msg = await openAccountSheet(context,
        session: widget.session, isCustomer: widget.isCustomer, partyId: party.id, mode: mode);
    if (msg == null || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
    _reload();
  }

  Future<void> _open([Party? party]) async {
    final saved = await openPartyForm(context, session: widget.session, isCustomer: widget.isCustomer, party: party);
    if (saved != null) _reload();
  }

  @override
  Widget build(BuildContext context) {
    final noun = widget.isCustomer ? 'müştəri' : 'şirkət';
    return Scaffold(
      appBar: AppBar(title: Text(widget.isCustomer ? 'Müştərilər' : 'Şirkətlər')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _open(),
        icon: const Icon(Icons.add),
        label: Text('Yeni $noun'),
      ),
      body: Column(children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
          child: TextField(
            decoration: const InputDecoration(prefixIcon: Icon(Icons.search), hintText: 'Ad və ya telefon ilə axtar'),
            onChanged: _onSearch,
          ),
        ),
        Expanded(
          child: RefreshIndicator(
            onRefresh: () async {
              _reload();
              await _items.catchError((_) => <Party>[]);
            },
            child: FutureBuilder<List<Party>>(
              future: _items,
              builder: (context, snap) {
                if (snap.connectionState != ConnectionState.done) return const Center(child: CircularProgressIndicator());
                if (snap.hasError) {
                  return ListView(children: [Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!)))]);
                }
                final items = snap.data!;
                if (items.isEmpty) {
                  return ListView(children: [
                    EmptyState(
                      title: _query.isEmpty ? 'Hələ $noun yoxdur' : 'Heç nə tapılmadı',
                      text: _query.isEmpty ? 'Aşağıdakı düymə ilə ilk $noun əlavə edin.' : null,
                    ),
                  ]);
                }
                return ListView.separated(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 96),
                  itemCount: items.length,
                  separatorBuilder: (_, _) => const SizedBox(height: 8),
                  itemBuilder: (context, i) {
                    final p = items[i];
                    return Card(
                      child: Column(children: [
                        ListTile(
                        leading: CircleAvatar(
                          backgroundColor: navy,
                          foregroundColor: gold,
                          child: Text(p.name.isEmpty ? '?' : p.name.characters.first.toUpperCase()),
                        ),
                        title: Text(p.name, style: const TextStyle(fontWeight: FontWeight.w600)),
                        subtitle: Text([
                          if (p.phone != null && p.phone!.isNotEmpty) p.phone!,
                          if (p.openDebtCount > 0) '${p.openDebtCount} açıq borc',
                        ].join(' · ')),
                        trailing: Text(
                          formatMoney(p.openBalance),
                          style: TextStyle(fontWeight: FontWeight.w700, color: p.openBalance > 0 ? Colors.red.shade700 : Colors.green.shade700),
                        ),
                        onTap: () async {
                          await Navigator.of(context).push(MaterialPageRoute(
                              builder: (_) => PartyProfileScreen(session: widget.session, isCustomer: widget.isCustomer, partyId: p.id)));
                          _reload();
                        },
                        onLongPress: () => _open(p),
                      ),
                        Padding(
                          padding: const EdgeInsets.fromLTRB(12, 0, 12, 10),
                          child: Row(children: [
                            Expanded(
                              child: FilledButton.tonalIcon(
                                onPressed: () => _account(p, AccountMode.charge),
                                icon: const Icon(Icons.add, size: 18),
                                label: const FittedBox(child: Text('Borc əlavə et')),
                              ),
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: FilledButton.icon(
                                onPressed: () => _account(p, AccountMode.pay),
                                icon: const Icon(Icons.payments_outlined, size: 18),
                                label: const FittedBox(child: Text('Borc ödə')),
                              ),
                            ),
                          ]),
                        ),
                      ]),
                    );
                  },
                );
              },
            ),
          ),
        ),
      ]),
    );
  }
}

/// Opens the add/edit form as a bottom sheet; returns the saved party, or null if cancelled.
Future<Party?> openPartyForm(BuildContext context,
    {required Session session, required bool isCustomer, Party? party}) {
  return showModalBottomSheet<Party>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (ctx) => Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
      child: _PartyForm(session: session, isCustomer: isCustomer, party: party),
    ),
  );
}

class _PartyForm extends StatefulWidget {
  const _PartyForm({required this.session, required this.isCustomer, this.party});

  final Session session;
  final bool isCustomer;
  final Party? party;

  @override
  State<_PartyForm> createState() => _PartyFormState();
}

class _PartyFormState extends State<_PartyForm> {
  final _formKey = GlobalKey<FormState>();
  late final _name = TextEditingController(text: widget.party?.name ?? '');
  late final _phone = TextEditingController(text: widget.party?.phone ?? '');
  late final _note = TextEditingController(text: widget.party?.note ?? '');
  bool _busy = false;
  String? _error;

  static final _phonePattern = RegExp(r'^[+0-9 ()-]{0,30}$');

  @override
  void dispose() {
    _name.dispose();
    _phone.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    final body = {
      'name': _name.text.trim(),
      'phone': _phone.text.trim().isEmpty ? null : _phone.text.trim(),
      'note': _note.text.trim().isEmpty ? null : _note.text.trim(),
    };
    try {
      final base = _endpoint(widget.isCustomer);
      final res = widget.party == null
          ? await widget.session.api.post(base, body)
          : await widget.session.api.put('$base/${widget.party!.id}', body);
      if (mounted) Navigator.of(context).pop(Party.fromJson(res as Map<String, dynamic>));
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final noun = widget.isCustomer ? 'müştəri' : 'şirkət';
    return Form(
      key: _formKey,
      child: ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 20, 16, 24), children: [
        Text(widget.party == null ? 'Yeni $noun' : 'Redaktə et',
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: navy)),
        const SizedBox(height: 16),
        if (_error != null) ErrorBanner(_error!),
        TextFormField(
          controller: _name,
          autofocus: widget.party == null,
          textCapitalization: TextCapitalization.words,
          decoration: InputDecoration(labelText: widget.isCustomer ? 'Ad, soyad' : 'Şirkətin adı'),
          validator: (v) {
            final t = (v ?? '').trim();
            if (t.isEmpty) return 'Adı daxil edin';
            if (t.length > 150) return 'Ən çox 150 simvol';
            return null;
          },
        ),
        const SizedBox(height: 14),
        TextFormField(
          controller: _phone,
          keyboardType: TextInputType.phone,
          decoration: const InputDecoration(labelText: 'Telefon (istəyə bağlı)', hintText: '+994 50 123 45 67'),
          validator: (v) => _phonePattern.hasMatch((v ?? '').trim()) ? null : 'Telefon nömrəsi düzgün deyil',
        ),
        const SizedBox(height: 14),
        TextFormField(
          controller: _note,
          maxLines: 3,
          maxLength: 1000,
          decoration: const InputDecoration(labelText: 'Qeyd (istəyə bağlı)'),
        ),
        const SizedBox(height: 12),
        FilledButton(
          onPressed: _busy ? null : _save,
          child: _busy
              ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : const Text('Yadda saxla'),
        ),
      ]),
    );
  }
}
