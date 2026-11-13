import 'package:flutter/material.dart';

import '../format.dart';
import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

/// Store owner only: "Düzəlt" / "Ləğv et" for one payment. Returns the changed payment, or null when nothing changed.
/// [maxAmount] (remaining debt + this payment) is checked locally when known; the server checks it anyway.
Future<Payment?> showPaymentActions(BuildContext context, Session session, Payment payment, {double? maxAmount}) async {
  final choice = await showModalBottomSheet<String>(
    context: context,
    showDragHandle: true,
    builder: (ctx) => SafeArea(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        ListTile(
          title: Text(payment.partyName, style: const TextStyle(fontWeight: FontWeight.w700, color: navy)),
          subtitle: Text('${formatDateTime(payment.paidAt)} · ${methodLabels[payment.paymentMethod]} · ${formatMoney(payment.amount)}'),
        ),
        const Divider(height: 1),
        ListTile(
          leading: const Icon(Icons.edit_outlined),
          title: const Text('Ödənişi düzəlt'),
          subtitle: const Text('Məbləğ, üsul, tarix, təsvir'),
          onTap: () => Navigator.of(ctx).pop('edit'),
        ),
        ListTile(
          leading: Icon(Icons.block, color: Colors.red.shade700),
          title: Text('Ödənişi ləğv et', style: TextStyle(color: Colors.red.shade700)),
          subtitle: const Text('Tarixçədə “ləğv edildi” kimi qalır'),
          onTap: () => Navigator.of(ctx).pop('cancel'),
        ),
      ]),
    ),
  );
  if (choice == null || !context.mounted) return null;
  if (choice == 'edit') {
    return showModalBottomSheet<Payment>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (_) => PaymentEditSheet(session: session, payment: payment, maxAmount: maxAmount),
    );
  }
  return _cancel(context, session, payment);
}

Future<Payment?> _cancel(BuildContext context, Session session, Payment payment) async {
  final reason = TextEditingController();
  final ok = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('Ödənişi ləğv et?'),
      content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('${payment.partyName} · ${formatMoney(payment.amount)}', style: const TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        const Text('Ödəniş silinmir — tarixçədə qalır və cəmlərə daxil olmur. Borc bu məbləğ qədər geri artır, '
            'kassaya əks əməliyyat yazılır. Bu əməliyyat geri qaytarılmır.'),
        const SizedBox(height: 12),
        TextField(controller: reason, maxLength: 500, decoration: const InputDecoration(labelText: 'Səbəb')),
      ]),
      actions: [
        TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('Bağla')),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: Colors.red.shade700),
          onPressed: () => Navigator.of(ctx).pop(true),
          child: const Text('Ləğv et'),
        ),
      ],
    ),
  );
  final text = reason.text.trim();
  reason.dispose();
  if (ok != true || !context.mounted) return null;
  try {
    final res = await session.api.post('/api/payments/${payment.id}/cancel', {'reason': text.isEmpty ? null : text});
    return Payment.fromJson(res as Map<String, dynamic>);
  } catch (e) {
    if (context.mounted) showError(context, e);
    return null;
  }
}

/// Baku calendar day of a timestamp.
DateTime _bakuDay(DateTime t) {
  final b = t.toUtc().add(const Duration(hours: 4));
  return DateTime(b.year, b.month, b.day);
}

class PaymentEditSheet extends StatefulWidget {
  const PaymentEditSheet({super.key, required this.session, required this.payment, this.maxAmount});

  final Session session;
  final Payment payment;
  final double? maxAmount;

  @override
  State<PaymentEditSheet> createState() => _PaymentEditSheetState();
}

class _PaymentEditSheetState extends State<PaymentEditSheet> {
  late final _amount = TextEditingController(text: widget.payment.amount.toStringAsFixed(2));
  late final _description = TextEditingController(text: widget.payment.description ?? '');
  late final _note = TextEditingController(text: widget.payment.note ?? '');
  late String _method = widget.payment.paymentMethod;
  late final DateTime _originalDay = _bakuDay(widget.payment.paidAt);
  late DateTime _day = _originalDay;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _amount.dispose();
    _description.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _pickDay() async {
    final today = _bakuDay(DateTime.now());
    final picked = await showDatePicker(
      context: context,
      initialDate: _day.isAfter(today) ? today : _day,
      firstDate: DateTime(2020),
      lastDate: today,
      locale: const Locale('az'),
    );
    if (picked != null) setState(() => _day = picked);
  }

  Future<void> _submit() async {
    final value = double.tryParse(_amount.text.replaceAll(',', '.'));
    if (value == null || value <= 0) {
      setState(() => _error = 'Məbləği düzgün daxil edin');
      return;
    }
    final max = widget.maxAmount;
    if (max != null && value > max + 0.001) {
      setState(() => _error = 'Məbləğ borcun qalığından (${formatMoney(max)}) çox ola bilməz');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    final d = _day;
    String two(int n) => n.toString().padLeft(2, '0');
    try {
      final res = await widget.session.api.put('/api/payments/${widget.payment.id}', {
        'amount': value.toStringAsFixed(2),
        'paymentMethod': _method,
        // same day → keep the original time; another day → noon of that day in Baku
        'paidAt': d == _originalDay
            ? widget.payment.paidAt.toUtc().toIso8601String()
            : '${d.year}-${two(d.month)}-${two(d.day)}T12:00:00+04:00',
        'description': _description.text.trim().isEmpty ? null : _description.text.trim(),
        'note': _note.text.trim().isEmpty ? null : _note.text.trim(),
      });
      if (mounted) Navigator.of(context).pop(Payment.fromJson(res as Map<String, dynamic>));
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(20, 0, 20, MediaQuery.viewInsetsOf(context).bottom + 20),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text('Ödənişi düzəlt — ${widget.payment.partyName}',
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: navy)),
        if (widget.maxAmount != null) ...[
          const SizedBox(height: 4),
          Text('Ən çox: ${formatMoney(widget.maxAmount!)}', style: TextStyle(color: Colors.grey.shade700)),
        ],
        const SizedBox(height: 16),
        if (_error != null) ErrorBanner(_error!),
        TextField(
          controller: _amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Məbləğ (₼)'),
        ),
        const SizedBox(height: 12),
        Wrap(spacing: 8, runSpacing: 8, children: [
          for (final m in const [
            ('CASH', 'Nağd', Icons.money),
            ('CARD', 'Kart', Icons.credit_card),
            ('TRANSFER', 'Köçürmə', Icons.swap_horiz),
            ('OTHER', 'Digər', Icons.more_horiz),
          ])
            ChoiceChip(
              avatar: Icon(m.$3, size: 18),
              label: Text(m.$2),
              selected: _method == m.$1,
              onSelected: (_) => setState(() => _method = m.$1),
            ),
        ]),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          onPressed: _pickDay,
          icon: const Icon(Icons.event_outlined),
          label: Text('Tarix: ${formatDate(_day)}'),
        ),
        const SizedBox(height: 12),
        TextField(controller: _description, maxLength: 255, decoration: const InputDecoration(labelText: 'Təsvir')),
        TextField(controller: _note, maxLength: 1000, decoration: const InputDecoration(labelText: 'Qeyd')),
        const SizedBox(height: 4),
        Text('Borcun qalığı və kassa avtomatik düzəldilir; dəyişiklik tarixçədə qalır.',
            style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
        const SizedBox(height: 16),
        FilledButton(
          onPressed: _busy ? null : _submit,
          child: _busy
              ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : const Text('Yadda saxla'),
        ),
      ]),
    );
  }
}
