import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

import '../models/models.dart';
import '../state/session.dart';
import '../theme.dart';
import 'common.dart';

/// HesabPro's own support line and e-mail: shown even when the admin has not filled in the contact settings.
const supportPhone = '050 798 81 77';
const supportEmail = 'info@hesabpro.cloud';
const _supportWhatsapp = '994507988177';

/// "Əlaqə": HesabPro support contacts managed by the platform admin (Platforma idarəetməsi → Ödəniş və əlaqə).
class ContactScreen extends StatefulWidget {
  const ContactScreen({super.key, required this.session});

  final Session session;

  @override
  State<ContactScreen> createState() => _ContactScreenState();
}

class _ContactScreenState extends State<ContactScreen> {
  late Future<Contact> _contact;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _contact = widget.session.api.get('/api/public/contact').then((j) => Contact.fromJson(j as Map<String, dynamic>));
  }

  Future<void> _refresh() async {
    setState(_load);
    await _contact.catchError((_) => const Contact());
  }

  Future<void> _open(Uri uri) async {
    final ok = await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (!ok && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Tətbiq açıla bilmədi')));
    }
  }

  Uri _tel(String phone) => Uri(scheme: 'tel', path: phone.replaceAll(RegExp(r'[^+\d]'), ''));

  String _supportText() {
    final me = widget.session.me;
    final who = [me?.storeName, me?.fullName].whereType<String>().where((s) => s.trim().isNotEmpty).join(' · ');
    return 'Salam, HesabPro dəstək xidməti.${who.isEmpty ? '' : '\n$who'}\nSualım / problemim: ';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Əlaqə')),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<Contact>(
          future: _contact,
          builder: (context, snap) {
            if (snap.hasError) {
              return ListView(padding: const EdgeInsets.all(16), children: [ErrorBanner(errorMessage(snap.error!))]);
            }
            if (!snap.hasData) return const Center(child: CircularProgressIndicator());
            final c = snap.data!;
            // The admin's WhatsApp number wins; otherwise HesabPro's own support line.
            final phone = c.phone ?? supportPhone;
            final waNumber = c.whatsapp ?? (c.phone == null ? _supportWhatsapp : null);
            final wa = waNumber == null ? null : Uri.https('wa.me', '/$waNumber', {'text': _supportText()});
            return ListView(padding: const EdgeInsets.all(16), children: [
              const Text('Dəstək', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: navy)),
              const SizedBox(height: 4),
              Text('Sualınız, təklifiniz və ya problem olduqda bizimlə əlaqə saxlayın.',
                  style: TextStyle(color: Colors.grey.shade700)),
              const SizedBox(height: 12),
              ...[
                if (wa != null)
                  Card(
                    child: ListTile(
                      leading: Icon(Icons.chat, color: Colors.green.shade600),
                      title: const Text('WhatsApp dəstək'),
                      subtitle: Text(phone),
                      trailing: const Icon(Icons.open_in_new),
                      onTap: () => _open(wa),
                    ),
                  ),
                _PhoneTile(label: 'Zəng et', phone: phone, onCall: () => _open(_tel(phone))),
                Card(
                  child: ListTile(
                    leading: const Icon(Icons.email_outlined, color: navy),
                    title: const Text('E-mail'),
                    subtitle: const Text(supportEmail),
                    onTap: () => _open(Uri(scheme: 'mailto', path: supportEmail, query: 'subject=HesabPro dəstək')),
                    trailing: IconButton(
                      tooltip: 'Kopyala',
                      icon: const Icon(Icons.copy, size: 20),
                      onPressed: () {
                        Clipboard.setData(const ClipboardData(text: supportEmail));
                        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('E-mail kopyalandı')));
                      },
                    ),
                  ),
                ),
                if (c.extraPhone != null && c.extraPhone != phone)
                  _PhoneTile(label: 'Əlavə nömrə', phone: c.extraPhone!, onCall: () => _open(_tel(c.extraPhone!))),
                if (c.extraContact != null)
                  Card(
                    child: ListTile(
                      leading: const Icon(Icons.info_outline, color: navy),
                      title: const Text('Digər əlaqə'),
                      subtitle: SelectableText(c.extraContact!),
                    ),
                  ),
              ],
              const SizedBox(height: 20),
              const Text('Abunəlik və ödəniş', style: TextStyle(fontWeight: FontWeight.w700, color: navy)),
              const SizedBox(height: 4),
              Text('Abunəliyi uzatmaq və ödəniş etmək üçün: Daha → Abunəlik.', style: TextStyle(color: Colors.grey.shade700)),
            ]);
          },
        ),
      ),
    );
  }
}

class _PhoneTile extends StatelessWidget {
  const _PhoneTile({required this.label, required this.phone, required this.onCall});

  final String label;
  final String phone;
  final VoidCallback onCall;

  @override
  Widget build(BuildContext context) => Card(
        child: ListTile(
          leading: const Icon(Icons.call, color: navy),
          title: Text(label),
          subtitle: Text(phone),
          onTap: onCall,
          trailing: IconButton(
            tooltip: 'Kopyala',
            icon: const Icon(Icons.copy, size: 20),
            onPressed: () {
              Clipboard.setData(ClipboardData(text: phone));
              ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Nömrə kopyalandı')));
            },
          ),
        ),
      );
}
