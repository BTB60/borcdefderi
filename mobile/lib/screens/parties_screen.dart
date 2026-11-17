import 'dart:async';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';

import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import 'campaign_screen.dart';
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

  static const _xlsx = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

  String get _kind => widget.isCustomer ? 'customers' : 'companies';

  Future<void> _share(String path, String name) async {
    try {
      final bytes = await widget.session.api.getBytes(path);
      await SharePlus.instance.share(ShareParams(
        files: [XFile.fromData(Uint8List.fromList(bytes), mimeType: _xlsx, name: name)],
        fileNameOverrides: [name],
      ));
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  /// Excel import (e.g. from another program): pick a file, send it, show what was added or the wrong rows.
  Future<void> _import() async {
    final picked = await FilePicker.pickFiles(type: FileType.custom, allowedExtensions: const ['xlsx', 'xls']);
    if (picked.isEmpty || !mounted) return;
    final file = picked.first;
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Import edilir…')));
    try {
      final bytes = await file.readAsBytes();
      final r = await widget.session.api.upload('/api/$_kind/import', 'file', bytes, file.name, _xlsx) as Map<String, dynamic>;
      if (!mounted) return;
      ScaffoldMessenger.of(context).hideCurrentSnackBar();
      final errors = (r['errors'] as List).cast<Map<String, dynamic>>();
      final ok = r['applied'] == true;
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          icon: Icon(ok ? Icons.check_circle_outline : Icons.error_outline,
              color: ok ? Colors.green.shade700 : Colors.red.shade700, size: 40),
          title: Text(ok ? 'Import tamamlandı' : 'Faylda səhv var'),
          content: ok
              ? Text('${r['created']} yeni ${widget.isCustomer ? 'müştəri' : 'şirkət'}\n'
                  '${r['withBalance']} nəfərə başlanğıc borc\n${r['skipped']} artıq var idi (keçildi)',
                  textAlign: TextAlign.center, style: const TextStyle(fontSize: 16, height: 1.5))
              : SizedBox(
                  width: double.maxFinite,
                  child: ListView(shrinkWrap: true, children: [
                    const Text('Heç nə saxlanmadı. Düzəldib yenidən yükləyin.'),
                    const SizedBox(height: 8),
                    for (final e in errors)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 3),
                        child: Text('${e['row'] == 0 ? 'Fayl' : 'Sətir ${e['row']}'}: ${e['message']}',
                            style: TextStyle(color: Colors.red.shade800)),
                      ),
                  ]),
                ),
          actions: [FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('Oldu'))],
        ),
      );
      if (ok) _reload();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).hideCurrentSnackBar();
        showError(context, e);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final noun = widget.isCustomer ? 'müştəri' : 'şirkət';
    return Scaffold(
      appBar: AppBar(title: Text(widget.isCustomer ? 'Müştərilər' : 'Şirkətlər'), actions: [
        if (widget.isCustomer)
          IconButton(
            tooltip: 'WhatsApp kampaniyası',
            icon: const Icon(Icons.campaign_outlined),
            onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => CampaignScreen(session: widget.session))),
          ),
        PopupMenuButton<String>(
          tooltip: 'Excel',
          icon: const Icon(Icons.table_view_outlined),
          onSelected: (v) => v == 'import'
              ? _import()
              : v == 'template'
                  ? _share('/api/$_kind/import-template', '${widget.isCustomer ? 'musteri' : 'sirket'}-import-sablonu.xlsx')
                  : _share('/api/reports/$_kind/export', '$_kind.xlsx'),
          itemBuilder: (_) => const [
            PopupMenuItem(value: 'import', child: ListTile(leading: Icon(Icons.upload_file), title: Text('Excel-dən import'))),
            PopupMenuItem(value: 'template', child: ListTile(leading: Icon(Icons.description_outlined), title: Text('Boş import şablonu'))),
            PopupMenuItem(value: 'export', child: ListTile(leading: Icon(Icons.download), title: Text('Excel-ə export'))),
          ],
        ),
      ]),
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
      padding: EdgeInsets.only(bottom: sheetBottomInset(ctx)),
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
  late final _discount = TextEditingController(
      text: (widget.party?.discountPercent ?? 0) > 0 ? widget.party!.discountPercent.toString().replaceAll(RegExp(r'\.0$'), '') : '');
  late final _limit = TextEditingController(
      text: widget.party?.creditLimit == null ? '' : widget.party!.creditLimit!.toStringAsFixed(2));
  bool _busy = false;
  String? _error;

  static final _phonePattern = RegExp(r'^[+0-9 ()-]{0,30}$');

  @override
  void dispose() {
    _name.dispose();
    _phone.dispose();
    _note.dispose();
    _discount.dispose();
    _limit.dispose();
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
      if (widget.isCustomer) 'discountPercent': double.tryParse(_discount.text.trim().replaceAll(',', '.')) ?? 0,
      if (widget.isCustomer && _limit.text.trim().isNotEmpty) 'creditLimit': _limit.text.trim().replaceAll(',', '.'),
      if (widget.isCustomer && _limit.text.trim().isEmpty) 'noCreditLimit': true,
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
        if (widget.isCustomer) ...[
          const SizedBox(height: 14),
          TextFormField(
            controller: _discount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(
                labelText: 'Daimi endirim (%)', helperText: 'Satışda bu müştəri seçiləndə avtomatik tətbiq olunur', suffixText: '%'),
            validator: (v) {
              final t = (v ?? '').trim();
              if (t.isEmpty) return null;
              final d = double.tryParse(t.replaceAll(',', '.'));
              return d == null || d < 0 || d > 100 ? '0 ilə 100 arası' : null;
            },
          ),
          const SizedBox(height: 12),
          TextFormField(
            controller: _limit,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(
              labelText: 'Borc limiti',
              helperText: 'Bundan çox nisyə satılmır (sahib icazə verə bilər). Boş = limitsiz',
              helperMaxLines: 2,
              suffixText: '₼',
            ),
            validator: (v) {
              final t = (v ?? '').trim();
              if (t.isEmpty) return null;
              final d = double.tryParse(t.replaceAll(',', '.'));
              return d == null || d < 0 ? 'Düzgün məbləğ yazın' : null;
            },
          ),
        ],
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
