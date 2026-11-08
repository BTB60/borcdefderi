import 'package:borcdefteri_mobile/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('SubscriptionSummary parses expiresAt for the countdown', () {
    final s = SubscriptionSummary.fromJson({
      'active': true, 'status': 'ACTIVE', 'planName': '6 aylıq', 'endDate': '2027-05-08',
      'daysRemaining': 180, 'renewalReminder': false, 'expiresAt': '2027-05-09T00:00:00+04:00',
    });
    expect(s.expiresAt!.toUtc(), DateTime.utc(2027, 5, 8, 20));
  });

  test('Countdown is green with time left and red when fewer than 7 days or expired', () {
    final end = DateTime.utc(2027, 1, 31);
    final far = Countdown.of(end, DateTime.utc(2027, 1, 1, 12, 30, 15))!;
    expect(far.days, 29);
    expect(far.text, '29 gün 11:29:45');
    expect(far.warning, isFalse);

    final near = Countdown.of(end, DateTime.utc(2027, 1, 26))!;
    expect(near.days, 5);
    expect(near.warning, isTrue);

    final over = Countdown.of(end, DateTime.utc(2027, 2, 1))!;
    expect(over.expired, isTrue);
    expect(over.text, 'Vaxtı bitib');
    expect(Countdown.of(null, DateTime.now()), isNull);
  });

  test('Plan period labels and contact parsing', () {
    Plan p(int m) => Plan(code: 'X', name: 'X', price: 1, durationMonths: m);
    expect(p(1).periodLabel, 'Aylıq');
    expect(p(6).periodLabel, '6 aylıq');
    expect(p(12).periodLabel, 'İllik');
    final c = Contact.fromJson({'phone': '+994 50 123 45 67', 'whatsapp': '994501234567'});
    expect(c.whatsapp, '994501234567');
    expect(const Contact().phone, isNull);
  });
}
