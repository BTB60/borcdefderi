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

String paymentConfirmationText(String store, String party, double paid, double remaining) {
  final rest = remaining > 0 ? 'Qalıq borcunuz: ${formatMoney(remaining)}.' : 'Borcunuz tam ödənilib.';
  return 'Hörmətli $party, "$store" mağazasına ${formatMoney(paid)} ödənişiniz qəbul edildi. $rest Təşəkkür edirik!';
}
