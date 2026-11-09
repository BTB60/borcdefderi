import 'package:flutter/material.dart';

import '../api/api_client.dart';
import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

/// Opens "Borc artır" as a bottom sheet; returns the updated debt, or null if cancelled.
Future<Debt?> openDebtIncrease(BuildContext context, {required Session session, required Debt debt}) {
  return showModalBottomSheet<Debt>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    useSafeArea: true,
    builder: (ctx) => Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
      child: DebtIncreaseSheet(session: session, debt: debt),
    ),
  );
}

/// The customer took more on credit: the amount is added on top of the existing debt
/// and kept in the history as its own entry (date, receipt number, note).
class DebtIncreaseSheet extends StatefulWidget {
  const DebtIncreaseSheet({super.key, required this.session, required this.debt});

  final Session session;
  final Debt debt;

  @override
  State<DebtIncreaseSheet> createState() => _DebtIncreaseSheetState();
}

class _DebtIncreaseSheetState extends State<DebtIncreaseSheet> {
  final _formKey = GlobalKey<FormState>();
  final _amount = TextEditingController();
  final _receipt = TextEditingController();
  final _note = TextEditingController();
  DateTime _date = DateTime.now();
  bool _busy = false;
  String? _error;

  static final _amountPattern = RegExp(r'^\d+([.,]\d{1,2})?$');

  @override
  void initState() {
    super.initState();
    _amount.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _amount.dispose();
    _receipt.dispose();
    _note.dispose();
    super.dispose();
  }

  double? get _value {
    final t = _amount.text.trim();
    if (!_amountPattern.hasMatch(t)) return null;
    final v = double.tryParse(t.replaceAll(',', '.'));
    return (v == null || v <= 0) ? null : v;
  }

  String _iso(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  Future<void> _pickDate() async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: _date,
      firstDate: DateTime(2020),
      lastDate: now,
      locale: const Locale('az'),
    );
    if (picked != null) setState(() => _date = picked);
  }

  Future<void> _save() async {
    if (_busy || !(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final res = await widget.session.api.post('/api/debts/${widget.debt.id}/increase', {
        'amount': _value!.toStringAsFixed(2),
        'date': _iso(_date),
        'receiptNumber': _receipt.text.trim().isEmpty ? null : _receipt.text.trim(),
        'note': _note.text.trim().isEmpty ? null : _note.text.trim(),
        'version': widget.debt.version,
      });
      if (mounted) Navigator.of(context).pop(Debt.fromJson(res as Map<String, dynamic>));
    } on ApiException catch (e) {
      if (mounted) {
        setState(() => _error = e.code == 'VERSION_CONFLICT'
            ? 'Bu borc artıq dəyişdirilib. Bağlayıb yenidən açın.'
            : errorMessage(e));
      }
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final after = widget.debt.remainingAmount + (_value ?? 0);
    return Form(
      key: _formKey,
      child: ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
        const Text('Borc artır', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: navy)),
        const SizedBox(height: 4),
        Text(widget.debt.partyName, style: TextStyle(color: Colors.grey.shade700)),
        const SizedBox(height: 14),
        Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(color: navy, borderRadius: BorderRadius.circular(14)),
          child: Row(children: [
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('İndiki qalıq', style: TextStyle(color: Colors.white60, fontSize: 12)),
                FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(formatMoney(widget.debt.remainingAmount),
                      style: const TextStyle(color: gold, fontSize: 18, fontWeight: FontWeight.w700)),
                ),
              ]),
            ),
            const Icon(Icons.arrow_forward, color: Colors.white38, size: 18),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
                const Text('Artımdan sonra', style: TextStyle(color: Colors.white60, fontSize: 12)),
                FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(formatMoney(after),
                      style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w700)),
                ),
              ]),
            ),
          ]),
        ),
        const SizedBox(height: 16),
        if (_error != null) ErrorBanner(_error!),
        TextFormField(
          controller: _amount,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Əlavə məbləğ (₼)'),
          validator: (_) => _value == null ? 'Düzgün məbləğ daxil edin (ən çox 2 onluq)' : null,
        ),
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Tarix'),
          subtitle: Text(formatDate(_date)),
          trailing: const Icon(Icons.calendar_today_outlined),
          onTap: _pickDate,
        ),
        TextFormField(
          controller: _receipt,
          maxLength: 60,
          decoration: const InputDecoration(labelText: 'Çek nömrəsi (istəyə bağlı)'),
        ),
        TextFormField(
          controller: _note,
          maxLength: 500,
          decoration: const InputDecoration(labelText: 'Qeyd', hintText: 'Məs: 2 kisə un'),
        ),
        Text('Artım tarixçədə ayrıca sətir kimi qalır; əvvəlki məlumatlar silinmir.',
            style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
        const SizedBox(height: 14),
        FilledButton(
          onPressed: _busy || _value == null ? null : _save,
          child: _busy
              ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : const Text('Borcu artır'),
        ),
      ]),
    );
  }
}
