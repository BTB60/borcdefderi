import 'format.dart';

/// Azerbaijani phone → international digits for wa.me links ("050 123 45 67" → "994501234567"). Null if unusable.
String? waNumber(String? phone) {
  if (phone == null) return null;
  var d = phone.replaceAll(RegExp(r'\D'), '');
  if (d.startsWith('00')) d = d.substring(2);
  if (d.startsWith('994')) return d.length == 12 ? d : null;
  if (d.startsWith('0') && d.length == 10) return '994${d.substring(1)}';
  if (d.length == 9) return '994$d';
  return d.length >= 10 && d.length <= 15 ? d : null;
}

/// Click-to-chat link with a ready message; the store sends it from its own WhatsApp.
Uri? waUri(String? phone, String text) {
  final n = waNumber(phone);
  return n == null ? null : Uri.https('wa.me', '/$n', {'text': text});
}

String debtReminderText(String store, String party, double remaining, DateTime? due) {
  final dueText = due == null ? '' : ' Son ödəmə tarixi: ${formatDate(due)}.';
  return 'Hörmətli $party, "$store" mağazasına qalıq borcunuz ${formatMoney(remaining)} təşkil edir.$dueText Təşəkkür edirik!';
}

/// Payment confirmation; with [receiptUrl] the customer can open the PDF receipt (çek) straight from WhatsApp.
String paymentConfirmationText(String store, String party, double paid, double remaining, [String? receiptUrl]) {
  final rest = remaining > 0 ? 'Qalıq borcunuz: ${formatMoney(remaining)}.' : 'Borcunuz tam ödənilib.';
  final receipt = receiptUrl == null ? '' : '\nÖdəniş çeki: $receiptUrl';
  return 'Hörmətli $party, "$store" mağazasına ${formatMoney(paid)} ödənişiniz qəbul edildi. $rest Təşəkkür edirik!$receipt';
}

String receiptMessageText(String store, String party, double paid, String receiptUrl, {DateTime? paidAt}) =>
    'Hörmətli $party, "$store" mağazasına ${formatMoney(paid)}${paidAt == null ? '' : ' (${formatDateTime(paidAt)})'} ödənişinizin çeki: $receiptUrl';

/// Values that fill the customer card's WhatsApp templates.
class TemplateVars {
  const TemplateVars({required this.store, required this.name, required this.total, required this.paid, required this.remaining, this.lastPayment});

  final String store;
  final String name;
  final double total;
  final double paid;
  final double remaining;
  final double? lastPayment;
}

const templateLabels = {
  'reminder': 'Borc xatırlatması',
  'payment': 'Ödəniş təsdiqi',
  'balance': 'Qalıq borc məlumatı',
  'thanks': 'Təşəkkür mesajı',
  'custom': 'Fərdi mesaj',
};

/// Editable starting text of each template.
String templateText(String key, TemplateVars v) {
  switch (key) {
    case 'reminder':
      return 'Salam, ${v.name}.\n"${v.store}" mağazasına qalıq borcunuz ${formatMoney(v.remaining)} təşkil edir. '
          'Zəhmət olmasa, ödənişi yaxın vaxtda edin.\nTəşəkkür edirik!';
    case 'payment':
      final paid = v.lastPayment == null ? '' : '${formatMoney(v.lastPayment!)} məbləğində ';
      return 'Salam, ${v.name}.\n$paidödənişiniz qəbul edildi. Qalıq borcunuz: ${formatMoney(v.remaining)}.\nTəşəkkür edirik!';
    case 'balance':
      return 'Salam, ${v.name}.\nHesabPro sistemində borc məlumatınız:\nÜmumi borc: ${formatMoney(v.total)}\n'
          'Ödənilmiş: ${formatMoney(v.paid)}\nQalıq borc: ${formatMoney(v.remaining)}\n'
          'Əlavə məlumat üçün bizimlə əlaqə saxlaya bilərsiniz.\n${v.store}';
    case 'thanks':
      return 'Salam, ${v.name}.\nBizi seçdiyiniz üçün təşəkkür edirik! Hər zaman xidmətinizdəyik.\n${v.store}';
    default:
      return 'Salam, ${v.name}.\n';
  }
}
