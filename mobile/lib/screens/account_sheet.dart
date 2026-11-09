import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import '../whatsapp.dart';
import 'common.dart';

const _sectionPermission = {
  'CUSTOMER_DEBT': 'CUSTOMER_DEBTS',
  'COMPANY_RECEIVABLE': 'COMPANY_RECEIVABLES',
  'COMPANY_PAYABLE': 'COMPANY_PAYABLES',
};

const directionLabels = {
  'CUSTOMER_DEBT': 'Müştəri borcu',
  'COMPANY_RECEIVABLE': 'Firma bizə borcludur',
  'COMPANY_PAYABLE': 'Biz firmaya borcluyuq',
};

enum AccountMode { charge, pay }

/// Opens "Borc əlavə et" / "Borc ödə" for a customer or company. Returns a message when something was saved.
Future<String?> openAccountSheet(BuildContext context,
    {required Session session, required bool isCustomer, required String partyId, required AccountMode mode, String? initialType}) {
  return showModalBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    useSafeArea: true,
    builder: (ctx) => Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
      child: _AccountSheet(session: session, isCustomer: isCustomer, partyId: partyId, mode: mode, initialType: initialType),
    ),
  );
}

class _AccountSheet extends StatefulWidget {
  const _AccountSheet({required this.session, required this.isCustomer, required this.partyId, required this.mode, this.initialType});

  final Session session;
  final bool isCustomer;
  final String partyId;
  final AccountMode mode;
  final String? initialType;

  @override
  State<_AccountSheet> createState() => _AccountSheetState();
}

class _AccountSheetState extends State<_AccountSheet> {
  final _formKey = GlobalKey<FormState>();
  final _amount = TextEditingController();
  final _receipt = TextEditingController();
  final _note = TextEditingController();
  DateTime _date = DateTime.now();
  DateTime? _due;
  String _method = 'CASH';
  String? _type;
  String? _partyName;
  String? _partyPhone;
  Map<String, double>? _balances;
  bool _busy = false;
  String? _error;

  static final _money = RegExp(r'^\d+([.,]\d{1,2})?$');

  bool get _isCharge => widget.mode == AccountMode.charge;

  List<String> get _types => [
        for (final t in widget.isCustomer ? const ['CUSTOMER_DEBT'] : const ['COMPANY_RECEIVABLE', 'COMPANY_PAYABLE'])
          if (widget.session.me!.can(_sectionPermission[t]!)) t
      ];

  double get _balance => _type == null ? 0 : (_balances?[_type] ?? 0);

  double? get _value {
    final t = _amount.text.trim();
    if (!_money.hasMatch(t)) return null;
    final v = double.tryParse(t.replaceAll(',', '.'));
    return (v == null || v <= 0) ? null : v;
  }

  @override
  void initState() {
    super.initState();
    _amount.addListener(() => setState(() {}));
    _load();
  }

  Future<void> _load() async {
    try {
      final j = await widget.session.api
          .get('/api/${widget.isCustomer ? 'customers' : 'companies'}/${widget.partyId}/profile') as Map<String, dynamic>;
      final balances = <String, double>{};
      for (final d in (j['debts'] as List).map((e) => Debt.fromJson(e as Map<String, dynamic>))) {
        balances[d.debtType] = ((balances[d.debtType] ?? 0) + d.remainingAmount);
      }
      balances.updateAll((k, v) => (v * 100).roundToDouble() / 100);
      final party = j['party'] as Map<String, dynamic>;
      if (!mounted) return;
      setState(() {
        _balances = balances;
        _partyName = party['name'] as String?;
        _partyPhone = party['phone'] as String?;
        final types = _types;
        if (widget.initialType != null && types.contains(widget.initialType)) {
          _type = widget.initialType;
          return;
        }
        _type = _isCharge
            ? (types.isEmpty ? null : types.first)
            : types.firstWhere((t) => (balances[t] ?? 0) > 0, orElse: () => types.isEmpty ? '' : types.first);
        if (_type == '') _type = null;
      });
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    }
  }

  @override
  void dispose() {
    _amount.dispose();
    _receipt.dispose();
    _note.dispose();
    super.dispose();
  }

  String _iso(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  Future<void> _save() async {
    if (_busy || _type == null || !(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    final base = '/api/${widget.isCustomer ? 'customers' : 'companies'}/${widget.partyId}';
    try {
      if (_isCharge) {
        final res = await widget.session.api.post('$base/charge', {
          'debtType': _type,
          'amount': _value!.toStringAsFixed(2),
          'date': _iso(_date),
          'receiptNumber': _receipt.text.trim().isEmpty ? null : _receipt.text.trim(),
          'note': _note.text.trim().isEmpty ? null : _note.text.trim(),
          if (_due != null) 'dueDate': _iso(_due!),
          'expectedRemaining': _balance.toStringAsFixed(2),
        }) as Map<String, dynamic>;
        if (mounted) {
          Navigator.of(context).pop('Borc əlavə edildi. Qalıq borc: ${formatMoney((res['remainingAfter'] as num).toDouble())}');
        }
      } else {
        final res = await widget.session.api.post('$base/pay', {
          'debtType': _type,
          'amount': _value!.toStringAsFixed(2),
          'paymentMethod': _method,
          'description': _note.text.trim().isEmpty ? null : _note.text.trim(),
          'expectedRemaining': _balance.toStringAsFixed(2),
        }) as Map<String, dynamic>;
        final remaining = (res['remainingAfter'] as num).toDouble();
        final payments = (res['payments'] as List).cast<Map<String, dynamic>>();
        if (mounted) await _afterPayment(remaining, payments.isEmpty ? null : payments.last['receiptUrl'] as String?);
        if (mounted) {
          Navigator.of(context).pop('Ödəniş qeydə alındı: ${formatMoney(_value!)}. Qalıq borc: ${formatMoney(remaining)}');
        }
      }
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Offers to open WhatsApp with a ready confirmation text (only opens WhatsApp; the user sends it).
  Future<void> _afterPayment(double remaining, String? receiptUrl) async {
    if (_type == 'COMPANY_PAYABLE') return;
    final uri = waUri(_partyPhone,
        paymentConfirmationText(widget.session.me?.storeName ?? '', _partyName ?? '', _value!, remaining, receiptUrl));
    if (uri == null) return;
    final open = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Ödəniş qeydə alındı'),
        content: Text('Qalıq borc: ${formatMoney(remaining)}\n\nMüştəriyə WhatsApp-da hazır mesaj açılsın? '
            'Mesajı WhatsApp-da özünüz göndərəcəksiniz.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Yox')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('WhatsApp-ı aç')),
        ],
      ),
    );
    if (open == true) await launchUrl(uri, mode: LaunchMode.externalApplication);
  }

  @override
  Widget build(BuildContext context) {
    final title = _isCharge ? 'Borc əlavə et' : 'Borc ödə';
    final v = _value ?? 0;
    final after = (_balance + (_isCharge ? v : -v)).clamp(0, double.infinity).toDouble();
    final exceeds = !_isCharge && v > _balance + 1e-9;
    return Form(
      key: _formKey,
      child: ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
        Text(title, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: navy)),
        if (_partyName != null) Text(_partyName!, style: TextStyle(color: Colors.grey.shade700)),
        const SizedBox(height: 14),
        if (_error != null) ErrorBanner(_error!),
        if (_balances == null && _error == null) const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator())),
        if (_balances != null) ...[
          if (_types.isEmpty) const ErrorBanner('Bu əməliyyat üçün icazəniz yoxdur.'),
          if (!widget.isCustomer && _types.length > 1)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: SegmentedButton<String>(
                segments: [
                  for (final t in _types)
                    ButtonSegment(
                      value: t,
                      label: Text('${directionLabels[t]}\n${formatMoney(_balances![t] ?? 0)}',
                          textAlign: TextAlign.center, style: const TextStyle(fontSize: 12)),
                    ),
                ],
                selected: {?_type},
                showSelectedIcon: false,
                onSelectionChanged: (s) => setState(() => _type = s.first),
              ),
            ),
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(color: navy, borderRadius: BorderRadius.circular(14)),
            child: Row(children: [
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  const Text('İndiki qalıq', style: TextStyle(color: Colors.white60, fontSize: 12)),
                  FittedBox(
                      fit: BoxFit.scaleDown,
                      child: Text(formatMoney(_balance), style: const TextStyle(color: gold, fontSize: 18, fontWeight: FontWeight.w700))),
                ]),
              ),
              const Icon(Icons.arrow_forward, color: Colors.white38, size: 18),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
                  Text(_isCharge ? 'Əlavədən sonra' : 'Ödənişdən sonra', style: const TextStyle(color: Colors.white60, fontSize: 12)),
                  FittedBox(
                      fit: BoxFit.scaleDown,
                      child: Text(formatMoney(after), style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w700))),
                ]),
              ),
            ]),
          ),
          if (!_isCharge && _balance <= 0)
            const Padding(padding: EdgeInsets.only(top: 12), child: ErrorBanner('Bu hesab üzrə ödəniləcək borc yoxdur.')),
          const SizedBox(height: 14),
          TextFormField(
            controller: _amount,
            autofocus: true,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(
              labelText: 'Məbləğ (₼)',
              errorText: exceeds ? 'Qalıq borcdan çox ola bilməz' : null,
              suffixIcon: _isCharge || _balance <= 0
                  ? null
                  : TextButton(
                      onPressed: () => _amount.text = _balance.toStringAsFixed(2),
                      child: const Text('Tam'),
                    ),
            ),
            validator: (_) => _value == null ? 'Düzgün məbləğ daxil edin (ən çox 2 onluq)' : null,
          ),
          const SizedBox(height: 8),
          if (_isCharge) ...[
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Tarix'),
              subtitle: Text(formatDate(_date)),
              trailing: const Icon(Icons.calendar_today_outlined),
              onTap: () async {
                final picked = await showDatePicker(
                    context: context, initialDate: _date, firstDate: DateTime(2020), lastDate: DateTime.now(), locale: const Locale('az'));
                if (picked != null) setState(() => _date = picked);
              },
            ),
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Son ödəmə tarixi (istəyə bağlı)'),
              subtitle: Text(_due == null ? 'Keçəndə borc gecikmiş sayılır' : formatDate(_due!)),
              trailing: _due == null
                  ? const Icon(Icons.event_outlined)
                  : IconButton(icon: const Icon(Icons.clear), onPressed: () => setState(() => _due = null)),
              onTap: () async {
                final picked = await showDatePicker(
                    context: context, initialDate: _due ?? _date, firstDate: _date, lastDate: DateTime(2100), locale: const Locale('az'));
                if (picked != null) setState(() => _due = picked);
              },
            ),
            TextFormField(controller: _receipt, maxLength: 60, decoration: const InputDecoration(labelText: 'Çek nömrəsi (istəyə bağlı)')),
          ] else ...[
            Wrap(spacing: 8, runSpacing: 8, children: [
              for (final m in const ['CASH', 'CARD', 'TRANSFER', 'OTHER'])
                ChoiceChip(
                  label: Text(methodLabels[m] ?? m),
                  selected: _method == m,
                  onSelected: (_) => setState(() => _method = m),
                ),
            ]),
            const SizedBox(height: 8),
          ],
          TextFormField(
            controller: _note,
            maxLength: 255,
            decoration: InputDecoration(labelText: 'Qeyd', hintText: _isCharge ? 'Məs: 2 kisə un' : 'İstəyə bağlı'),
          ),
          const SizedBox(height: 8),
          FilledButton(
            style: _isCharge ? FilledButton.styleFrom(backgroundColor: gold, foregroundColor: navy) : null,
            onPressed: _busy || _type == null || _value == null || exceeds || (!_isCharge && _balance <= 0) ? null : _save,
            child: _busy
                ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : Text(_isCharge ? 'Borcu əlavə et' : 'Ödənişi qeydə al'),
          ),
        ],
      ]),
    );
  }
}
