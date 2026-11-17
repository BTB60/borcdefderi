import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

import '../format.dart';
import '../state/session.dart';
import '../theme.dart';
import '../whatsapp.dart';
import 'common.dart';

double _n(Object? v) => (v as num?)?.toDouble() ?? 0;

const _status = {'NEW': 'Yeni', 'CONFIRMED': 'Təsdiqləndi', 'DONE': 'Satıldı', 'CANCELLED': 'Ləğv edildi'};

/// Onlayn sifarişlər: orders from the public catalog link; the owner can switch the catalog on and share it.
class OnlineOrdersScreen extends StatefulWidget {
  const OnlineOrdersScreen({super.key, required this.session});

  final Session session;

  @override
  State<OnlineOrdersScreen> createState() => _OnlineOrdersScreenState();
}

class _OnlineOrdersScreenState extends State<OnlineOrdersScreen> {
  late Future<List<Map<String, dynamic>>> _orders = _load();
  Map<String, dynamic>? _settings;

  @override
  void initState() {
    super.initState();
    widget.session.api.get('/api/catalog/settings').then((s) {
      if (mounted) setState(() => _settings = s as Map<String, dynamic>);
    }).catchError((_) {});
  }

  Future<List<Map<String, dynamic>>> _load() async =>
      ((await widget.session.api.get('/api/online-orders')) as List).cast<Map<String, dynamic>>();

  String? get _link => _settings?['slug'] == null ? null : 'https://www.hesabpro.cloud/k/${_settings!['slug']}';

  Future<void> _toggle(bool on) async {
    try {
      final s = await widget.session.api.put('/api/catalog/settings', {'enabled': on, 'note': _settings?['note']});
      if (mounted) setState(() => _settings = s as Map<String, dynamic>);
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> _setStatus(Map<String, dynamic> o, String st) async {
    try {
      await widget.session.api.post('/api/online-orders/${o['id']}/status', {'status': st});
      if (mounted) setState(() => _orders = _load());
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final owner = widget.session.me?.isOwner ?? false;
    final on = _settings?['enabled'] == true;
    return Scaffold(
      appBar: AppBar(title: const Text('Onlayn sifarişlər')),
      body: RefreshIndicator(
        onRefresh: () async {
          setState(() => _orders = _load());
          await _orders;
        },
        child: ListView(padding: const EdgeInsets.all(12), children: [
          if (owner && _settings != null)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    const Expanded(child: Text('Onlayn kataloq', style: TextStyle(fontWeight: FontWeight.w800, color: navy, fontSize: 16))),
                    Switch(value: on, onChanged: _toggle),
                  ]),
                  Text(on ? 'Müştərilər linkdən mallara baxıb sifariş verə bilər.' : 'Açın — mallarınız şəkil və qiymətlə bir linkdə görünəcək.',
                      style: TextStyle(color: Colors.grey.shade700)),
                  if (on && _link != null) ...[
                    const SizedBox(height: 8),
                    SelectableText(_link!, style: const TextStyle(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 6),
                    Wrap(spacing: 8, children: [
                      OutlinedButton.icon(
                        icon: const Icon(Icons.copy, size: 18),
                        label: const Text('Kopyala'),
                        onPressed: () {
                          Clipboard.setData(ClipboardData(text: _link!));
                          ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Kopyalandı')));
                        },
                      ),
                      FilledButton.icon(
                        style: FilledButton.styleFrom(backgroundColor: const Color(0xFF25D366)),
                        icon: const Icon(Icons.share, size: 18),
                        label: const Text('Paylaş'),
                        onPressed: () => launchUrl(
                            Uri.parse('https://wa.me/?text=${Uri.encodeComponent('${widget.session.me?.storeName ?? 'Mağazamız'} — mallarımıza baxın və sifariş verin: $_link')}'),
                            mode: LaunchMode.externalApplication),
                      ),
                    ]),
                  ],
                ]),
              ),
            ),
          FutureBuilder<List<Map<String, dynamic>>>(
            future: _orders,
            builder: (context, snap) {
              if (snap.hasError) return ErrorBanner(errorMessage(snap.error!));
              if (!snap.hasData) return const Padding(padding: EdgeInsets.all(32), child: Center(child: CircularProgressIndicator()));
              final list = snap.data!;
              if (list.isEmpty) return const EmptyState(title: 'Sifariş yoxdur', text: 'Kataloq linkini müştərilərlə paylaşın.');
              return Column(children: [
                for (final o in list)
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(14),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Row(children: [
                          Expanded(child: Text('${o['number']} · ${o['customerName']}', style: const TextStyle(fontWeight: FontWeight.w800))),
                          Text(_status[o['status']] ?? '', style: TextStyle(
                              fontWeight: FontWeight.w700, color: o['status'] == 'NEW' ? Colors.orange.shade800 : Colors.grey.shade700)),
                        ]),
                        Text('${o['customerPhone']} · ${formatDateTime(DateTime.parse('${o['createdAt']}').toLocal())}',
                            style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
                        if (o['address'] != null) Text('📍 ${o['address']}'),
                        if (o['note'] != null) Text('“${o['note']}”', style: const TextStyle(fontStyle: FontStyle.italic)),
                        const Divider(),
                        for (final i in (o['items'] as List).cast<Map<String, dynamic>>())
                          Row(children: [
                            Expanded(child: Text('${i['name']} × ${i['quantity']}')),
                            Text(formatMoney(_n(i['lineTotal']))),
                          ]),
                        Align(alignment: Alignment.centerRight,
                            child: Text('Cəmi ${formatMoney(_n(o['total']))}', style: const TextStyle(fontWeight: FontWeight.w800))),
                        if (o['status'] == 'NEW' || o['status'] == 'CONFIRMED')
                          Wrap(spacing: 6, children: [
                            if (o['status'] == 'NEW') OutlinedButton(onPressed: () => _setStatus(o, 'CONFIRMED'), child: const Text('Təsdiqlə')),
                            FilledButton(onPressed: () => _setStatus(o, 'DONE'), child: const Text('Satıldı')),
                            IconButton(
                              tooltip: 'WhatsApp',
                              icon: const Icon(Icons.chat, color: Color(0xFF25D366)),
                              onPressed: () {
                                final uri = waUri('${o['customerPhone']}', 'Salam, ${o['customerName']}! ${o['number']} nömrəli sifarişiniz qəbul edildi.');
                                if (uri != null) launchUrl(uri, mode: LaunchMode.externalApplication);
                              },
                            ),
                            TextButton(onPressed: () => _setStatus(o, 'CANCELLED'), child: const Text('Ləğv et')),
                          ]),
                      ]),
                    ),
                  ),
              ]);
            },
          ),
        ]),
      ),
    );
  }
}
