import 'package:flutter/material.dart';

import '../api/api_client.dart';
import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

/// Edits amount, dates, description and note of an existing debt. Party and type stay fixed.
/// Returns the updated debt, or null if cancelled.
class EditDebtSheet extends StatefulWidget {
  const EditDebtSheet({super.key, required this.session, required this.debt});

  final Session session;
  final Debt debt;

  @override
  State<EditDebtSheet> createState() => _EditDebtSheetState();
}

class _EditDebtSheetState extends State<EditDebtSheet> {
  final _formKey = GlobalKey<FormState>();
  late final _amount = TextEditingController(text: widget.debt.amount.toStringAsFixed(2));
  late final _description = TextEditingController(text: widget.debt.description ?? '');
  late final _note = TextEditingController(text: widget.debt.note ?? '');
  late DateTime _debtDate = widget.debt.debtDate;
  late DateTime? _dueDate = widget.debt.dueDate;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _amount.dispose();
    _description.dispose();
    _note.dispose();
    super.dispose();
  }

  String _iso(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  Future<void> _pickDate(bool due) async {
    final picked = await showDatePicker(
      context: context,
      initialDate: due ? (_dueDate ?? _debtDate) : _debtDate,
      firstDate: due ? _debtDate : DateTime(2020),
      lastDate: DateTime(2100),
      locale: const Locale('az'),
    );
    if (picked == null) return;
    setState(() {
      if (due) {
        _dueDate = picked;
      } else {
        _debtDate = picked;
        if (_dueDate != null && _dueDate!.isBefore(picked)) _dueDate = null;
      }
    });
  }

  Future<void> _save() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final res = await widget.session.api.put('/api/debts/${widget.debt.id}', {
        'amount': double.parse(_amount.text.replaceAll(',', '.')).toStringAsFixed(2),
        'debtDate': _iso(_debtDate),
        'dueDate': _dueDate == null ? null : _iso(_dueDate!),
        'description': _description.text.trim().isEmpty ? null : _description.text.trim(),
        'note': _note.text.trim().isEmpty ? null : _note.text.trim(),
        'version': widget.debt.version,
      });
      if (mounted) Navigator.of(context).pop(Debt.fromJson(res as Map<String, dynamic>));
    } on ApiException catch (e) {
      if (mounted) {
        setState(() => _error = e.code == 'VERSION_CONFLICT'
            ? 'Bu borc başqa istifadəçi tərəfindən dəyişdirilib. Bağlayıb yenidən açın.'
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
    final paid = widget.debt.paidAmount;
    return Form(
      key: _formKey,
      child: ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
        const Text('Borcu redaktə et', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: navy)),
        const SizedBox(height: 4),
        Text(widget.debt.partyName, style: TextStyle(color: Colors.grey.shade700)),
        const SizedBox(height: 16),
        if (_error != null) ErrorBanner(_error!),
        TextFormField(
          controller: _amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(
            labelText: 'Məbləğ (₼)',
            helperText: paid > 0 ? 'Artıq ödənilib: ${formatMoney(paid)} — məbləğ bundan az ola bilməz' : null,
          ),
          validator: (v) {
            final n = double.tryParse((v ?? '').replaceAll(',', '.'));
            if (n == null || n <= 0) return 'Düzgün məbləğ daxil edin';
            if (n + 0.0001 < paid) return 'Ödənilmiş məbləğdən (${formatMoney(paid)}) az ola bilməz';
            return null;
          },
        ),
        const SizedBox(height: 8),
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Borc tarixi'),
          subtitle: Text(formatDate(_debtDate)),
          trailing: const Icon(Icons.calendar_today_outlined),
          onTap: () => _pickDate(false),
        ),
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Son ödəmə tarixi (istəyə bağlı)'),
          subtitle: Text(formatDate(_dueDate)),
          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
            if (_dueDate != null)
              IconButton(tooltip: 'Sil', icon: const Icon(Icons.clear), onPressed: () => setState(() => _dueDate = null)),
            const Icon(Icons.event_outlined),
          ]),
          onTap: () => _pickDate(true),
        ),
        const SizedBox(height: 6),
        TextFormField(
          controller: _description,
          maxLength: 255,
          decoration: const InputDecoration(labelText: 'Təsvir'),
        ),
        const SizedBox(height: 6),
        TextFormField(
          controller: _note,
          maxLines: 3,
          maxLength: 1000,
          decoration: const InputDecoration(labelText: 'Qeyd'),
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
