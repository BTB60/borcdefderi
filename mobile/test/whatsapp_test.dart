import 'package:borcdefteri_mobile/whatsapp.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Azerbaijani numbers become wa.me digits', () {
    expect(waNumber('050 123 45 67'), '994501234567');
    expect(waNumber('+994 (55) 123-45-67'), '994551234567');
    expect(waNumber('701234567'), '994701234567');
    expect(waNumber('00994501234567'), '994501234567');
    expect(waNumber('12345'), isNull);
    expect(waNumber(null), isNull);
  });

  test('messages and links carry the amounts', () {
    final uri = waUri('0501234567', paymentConfirmationText('Market', 'Leyla', 20, 30))!;
    expect(uri.host, 'wa.me');
    expect(uri.path, '/994501234567');
    expect(uri.queryParameters['text'], contains('Qalıq borcunuz'));
    expect(paymentConfirmationText('Market', 'Leyla', 50, 0), contains('tam ödənilib'));
    expect(debtReminderText('Market', 'Leyla', 12.5, DateTime(2026, 12, 1)), contains('01.12.2026'));
  });

  test('receipt link travels inside the WhatsApp message', () {
    const link = 'https://api.example.az/api/public/receipts/abc/token123';
    final confirmation = paymentConfirmationText('Market', 'Leyla', 20, 30, link);
    expect(confirmation, contains('Ödəniş çeki: $link'));
    expect(paymentConfirmationText('Market', 'Leyla', 20, 30), isNot(contains('çeki:')));
    final uri = waUri('050 123 45 67', receiptMessageText('Market', 'Leyla', 20, link))!;
    expect(uri.queryParameters['text'], endsWith(link));
  });
}
