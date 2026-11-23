import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import '../whatsapp.dart';
import 'common.dart';

double _n(Object? v) => (v as num?)?.toDouble() ?? 0;

const _statusLabel = {
  'NEW': 'Yeni',
  'ASSIGNED': 'Kuryerə verilib',
  'ON_THE_WAY': 'Yoldadır',
  'DELIVERED': 'Çatdırılıb',
  'CANCELLED': 'Ləğv edilib',
};

MaterialColor _statusColor(String s) => switch (s) {
      'ASSIGNED' => Colors.blue,
      'ON_THE_WAY' => Colors.orange,
      'DELIVERED' => Colors.green,
      'CANCELLED' => Colors.red,
      _ => Colors.blueGrey,
    };

/// Çatdırılma və kuryer. Sales staff create deliveries and give them to a courier; a courier (job title Kuryer)
/// sees only their own and moves them: "Yola çıxdım" → "Çatdırdım".
class DeliveriesScreen extends StatefulWidget {
  const DeliveriesScreen({super.key, required this.session});

  final Session session;

  @override
  State<DeliveriesScreen> createState() => _DeliveriesScreenState();
}

class _DeliveriesScreenState extends State<DeliveriesScreen> {
  String _status = 'ACTIVE';
  late Future<List<Map<String, dynamic>>> _data = _fetch();
  List<Map<String, dynamic>> _couriers = [];

  bool get _manager => widget.session.me!.can('SALES');

  @override
  void initState() {
    super.initState();
    if (_manager) {
      widget.session.api.get('/api/deliveries/couriers').then((v) {
        if (mounted) setState(() => _couriers = (v as List).cast<Map<String, dynamic>>());
      }).catchError((_) {});
    }
  }

  Future<List<Map<String, dynamic>>> _fetch() async =>
      ((await widget.session.api.get('/api/deliveries', query: {'status': _status})) as List).cast<Map<String, dynamic>>();

  void _reload() => setState(() => _data = _fetch());

  Future<void> _act(Map<String, dynamic> d, String action, [Map<String, dynamic>? body]) async {
    try {
      await widget.session.api.post('/api/deliveries/${d['id']}/$action', body ?? {});
      _reload();
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _deliver(Map<String, dynamic> d) async {
    final expected = _n(d['collectAmount']);
    if (expected <= 0) return _act(d, 'deliver');
    final ctrl = TextEditingController(text: expected.toStringAsFixed(2));
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Müştəridən alınan məbləğ'),
        content: TextField(
          controller: ctrl,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(suffixText: '₼', helperText: 'Gözlənilən: ${formatMoney(expected)}'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Ləğv et')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Çatdırıldı')),
        ],
      ),
    );
    if (ok != true) return;
    final v = double.tryParse(ctrl.text.trim().replaceAll(',', '.'));
    if (v == null || v < 0) {
      if (mounted) showError(context, 'Məbləğ düzgün deyil');
      return;
    }
    await _act(d, 'deliver', {'collectedAmount': v});
  }

  Future<void> _cancel(Map<String, dynamic> d) async {
    final ctrl = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('${d['number']} ləğv edilsin?'),
        content: TextField(controller: ctrl, decoration: const InputDecoration(labelText: 'Səbəb (istəyə görə)')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Xeyr')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Ləğv et')),
        ],
      ),
    );
    if (ok == true) await _act(d, 'cancel', {'reason': ctrl.text.trim()});
  }

  Future<void> _shareLink(Map<String, dynamic> d) async {
    final text = 'Salam, ${d['recipientName']}! Sifarişiniz (${d['number']}) yoldadır. '
        'Çatdırılmanı buradan izləyin: https://www.hesabpro.cloud/izle/${d['trackToken']}';
    final uri = waUri(d['recipientPhone'] as String?, text);
    if (uri == null) return showError(context, 'Alıcının telefon nömrəsi düzgün deyil');
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication) && mounted) showError(context, 'WhatsApp açılmadı');
  }

  Future<void> _openMap(Map<String, dynamic> d) async {
    final uri = Uri.parse('https://www.google.com/maps/search/?api=1&query=${Uri.encodeComponent('${d['address']}')}');
    await launchUrl(uri, mode: LaunchMode.externalApplication);
  }

  Future<void> _edit({Map<String, dynamic>? assign}) async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(bottom: sheetBottomInset(ctx)),
        child: _DeliverySheet(session: widget.session, couriers: _couriers, assign: assign),
      ),
    );
    if (saved == true) _reload();
  }

  @override
  Widget build(BuildContext context) {
    final courierOnly = !_manager;
    return Scaffold(
      appBar: AppBar(title: Text(courierOnly ? 'Mənim çatdırılmalarım' : 'Çatdırılma')),
      floatingActionButton: _manager
          ? FloatingActionButton.extended(onPressed: () => _edit(), icon: const Icon(Icons.add), label: const Text('Çatdırılma'))
          : null,
      body: Column(children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
          child: SegmentedButton<String>(
            segments: [
              const ButtonSegment(value: 'ACTIVE', label: Text('Aktiv')),
              const ButtonSegment(value: 'DELIVERED', label: Text('Çatdırılıb')),
              if (!courierOnly) const ButtonSegment(value: 'CANCELLED', label: Text('Ləğv')),
            ],
            selected: {_status},
            onSelectionChanged: (s) {
              _status = s.first;
              _reload();
            },
          ),
        ),
        Expanded(
          child: FutureBuilder<List<Map<String, dynamic>>>(
            future: _data,
            builder: (context, snap) {
              if (snap.hasError) return Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(errorMessage(snap.error!)));
              if (!snap.hasData) return const Center(child: CircularProgressIndicator());
              final items = snap.data!;
              if (items.isEmpty) return EmptyState(title: _status == 'ACTIVE' ? 'Aktiv çatdırılma yoxdur' : 'Siyahı boşdur');
              return RefreshIndicator(
                onRefresh: () async => _reload(),
                child: ListView.builder(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 96),
                  itemCount: items.length,
                  itemBuilder: (context, i) => _card(items[i]),
                ),
              );
            },
          ),
        ),
      ]),
    );
  }

  Widget _card(Map<String, dynamic> d) {
    final status = '${d['status']}';
    final color = _statusColor(status);
    final open = status == 'NEW' || status == 'ASSIGNED' || status == 'ON_THE_WAY';
    final collect = _n(d['collectAmount']);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(
              child: Text('${d['recipientName']}', style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16, color: navy)),
            ),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(999)),
              child: Text(_statusLabel[status] ?? status, style: TextStyle(color: color.shade800, fontSize: 12, fontWeight: FontWeight.w600)),
            ),
          ]),
          Text('${d['number']}${d['saleNumber'] != null ? ' · satış ${d['saleNumber']}' : ''}', style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
          const SizedBox(height: 8),
          InkWell(
            onTap: () => _openMap(d),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Icon(Icons.place_outlined, size: 18, color: gold),
              const SizedBox(width: 4),
              Expanded(child: Text('${d['address']}')),
            ]),
          ),
          if (d['note'] != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text('${d['note']}', style: TextStyle(color: Colors.grey.shade700, fontSize: 13))),
          const SizedBox(height: 6),
          Wrap(spacing: 12, runSpacing: 4, children: [
            Text('Kuryer: ${d['courierName'] ?? 'təyin edilməyib'}', style: const TextStyle(fontSize: 13)),
            if (collect > 0) Text('Alınacaq: ${formatMoney(collect)}', style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700)),
            if (d['collectedAmount'] != null)
              Text('Alındı: ${formatMoney(_n(d['collectedAmount']))}', style: TextStyle(fontSize: 13, color: Colors.green.shade700)),
            if (_n(d['fee']) > 0) Text('Haqq: ${formatMoney(_n(d['fee']))}', style: const TextStyle(fontSize: 13)),
          ]),
          const SizedBox(height: 8),
          Wrap(spacing: 8, runSpacing: 4, children: [
            OutlinedButton.icon(
              onPressed: () => launchUrl(Uri(scheme: 'tel', path: '${d['recipientPhone']}')),
              icon: const Icon(Icons.call, size: 18),
              label: const Text('Zəng'),
            ),
            if (open && status == 'ASSIGNED') FilledButton(onPressed: () => _act(d, 'pick-up'), child: const Text('Yola çıxdım')),
            if (open && status != 'NEW') FilledButton(onPressed: () => _deliver(d), child: const Text('Çatdırdım')),
            if (open && _manager) OutlinedButton(onPressed: () => _edit(assign: d), child: Text(d['courierName'] == null ? 'Kuryerə ver' : 'Kuryeri dəyiş')),
            if (open) TextButton(onPressed: () => _shareLink(d), child: const Text('Müştəriyə link')),
            if (open && _manager) TextButton(onPressed: () => _cancel(d), child: Text('Ləğv et', style: TextStyle(color: Colors.red.shade700))),
          ]),
        ]),
      ),
    );
  }
}

/// New delivery, or (with [assign]) giving an existing one to a courier.
class _DeliverySheet extends StatefulWidget {
  const _DeliverySheet({required this.session, required this.couriers, this.assign});

  final Session session;
  final List<Map<String, dynamic>> couriers;
  final Map<String, dynamic>? assign;

  @override
  State<_DeliverySheet> createState() => _DeliverySheetState();
}

class _DeliverySheetState extends State<_DeliverySheet> {
  final _name = TextEditingController();
  final _phone = TextEditingController();
  final _address = TextEditingController();
  final _note = TextEditingController();
  final _collect = TextEditingController();
  final _fee = TextEditingController();
  final _courierName = TextEditingController();
  final _courierPhone = TextEditingController();
  String? _courierId;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    final a = widget.assign;
    if (a != null) {
      _courierId = a['courierUserId'] as String?;
      if (_courierId == null) {
        _courierName.text = '${a['courierName'] ?? ''}';
        _courierPhone.text = '${a['courierPhone'] ?? ''}';
      }
    }
  }

  @override
  void dispose() {
    for (final c in [_name, _phone, _address, _note, _collect, _fee, _courierName, _courierPhone]) {
      c.dispose();
    }
    super.dispose();
  }

  Map<String, dynamic> get _courier => _courierId != null
      ? {'courierUserId': _courierId}
      : {
          'courierName': _courierName.text.trim().isEmpty ? null : _courierName.text.trim(),
          'courierPhone': _courierPhone.text.trim().isEmpty ? null : _courierPhone.text.trim(),
        };

  Future<void> _save() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final api = widget.session.api;
      if (widget.assign != null) {
        await api.post('/api/deliveries/${widget.assign!['id']}/assign', _courier);
      } else {
        await api.post('/api/deliveries', {
          'recipientName': _name.text.trim(),
          'recipientPhone': _phone.text.trim(),
          'address': _address.text.trim(),
          'note': _note.text.trim().isEmpty ? null : _note.text.trim(),
          'collectAmount': double.tryParse(_collect.text.trim().replaceAll(',', '.')) ?? 0,
          'fee': double.tryParse(_fee.text.trim().replaceAll(',', '.')) ?? 0,
          ..._courier,
        });
      }
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) setState(() => _error = errorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    const gap = SizedBox(height: 10);
    const decimal = TextInputType.numberWithOptions(decimal: true);
    final isNew = widget.assign == null;
    return ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
      Text(isNew ? 'Yeni çatdırılma' : 'Kuryer — ${widget.assign!['number']}',
          style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: navy)),
      const SizedBox(height: 12),
      if (_error != null) ErrorBanner(_error!),
      if (isNew) ...[
        TextField(controller: _name, textCapitalization: TextCapitalization.words, decoration: const InputDecoration(labelText: 'Alıcının adı *')),
        gap,
        TextField(controller: _phone, keyboardType: TextInputType.phone, decoration: const InputDecoration(labelText: 'Telefon *', hintText: '050 123 45 67')),
        gap,
        TextField(controller: _address, minLines: 1, maxLines: 3, decoration: const InputDecoration(labelText: 'Ünvan *')),
        gap,
        Row(children: [
          Expanded(child: TextField(controller: _collect, keyboardType: decimal, decoration: const InputDecoration(labelText: 'Qapıda alınacaq', suffixText: '₼'))),
          const SizedBox(width: 12),
          Expanded(child: TextField(controller: _fee, keyboardType: decimal, decoration: const InputDecoration(labelText: 'Çatdırılma haqqı', suffixText: '₼'))),
        ]),
        gap,
        TextField(controller: _note, decoration: const InputDecoration(labelText: 'Qeyd', hintText: 'məs. 18:00-dan sonra')),
        const SizedBox(height: 16),
      ],
      DropdownButtonFormField<String?>(
        initialValue: _courierId,
        decoration: InputDecoration(
          labelText: 'Kuryer',
          helperText: widget.couriers.isEmpty ? 'Vəzifəsi “Kuryer” olan işçi yoxdur — kənar kuryerin adını yazın' : null,
          helperMaxLines: 2,
        ),
        items: [
          const DropdownMenuItem<String?>(value: null, child: Text('Kənar kuryer / sonra')),
          for (final c in widget.couriers)
            DropdownMenuItem<String?>(
              value: c['userId'] as String,
              child: Text('${c['fullName']}${(c['activeDeliveries'] as num) > 0 ? ' (${c['activeDeliveries']} aktiv)' : ''}'),
            ),
        ],
        onChanged: (v) => setState(() => _courierId = v),
      ),
      if (_courierId == null) ...[
        gap,
        TextField(controller: _courierName, decoration: const InputDecoration(labelText: 'Kənar kuryerin adı', hintText: 'məs. Taksi Elvin')),
        gap,
        TextField(controller: _courierPhone, keyboardType: TextInputType.phone, decoration: const InputDecoration(labelText: 'Kuryerin telefonu')),
      ],
      const SizedBox(height: 16),
      FilledButton(
        onPressed: _busy ? null : _save,
        style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(50)),
        child: Text(isNew ? 'Yarat' : 'Saxla'),
      ),
    ]);
  }
}
