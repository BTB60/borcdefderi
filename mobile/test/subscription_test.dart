import 'package:borcdefteri_mobile/models/models.dart';
import 'dart:ui' show Color;

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

  test('Admin-designed plan: period options, colours, features', () {
    final p = Plan.fromJson({
      'code': 'P1', 'name': 'Biznes', 'price': 25, 'durationMonths': 1, 'description': 'Böyük mağazalar',
      'features': ['PDF hesabat'], 'maxEmployees': 3, 'maxCustomers': null, 'recommended': true,
      'options': [
        {'period': 'MONTHLY', 'months': 1, 'days': null, 'price': 25},
        {'period': 'QUARTERLY', 'months': 3, 'days': null, 'price': 70},
        {'period': 'CUSTOM', 'months': null, 'days': 45, 'price': 35.5},
      ],
      'primaryColor': '#7C3AED', 'secondaryColor': '#D6AD60', 'backgroundColor': '#FFFFFF',
      'textColor': '#101C35', 'buttonColor': '#7C3AED',
    });
    expect(p.periods.length, 3);
    expect(p.optionFor('QUARTERLY').price, 70);
    expect(p.optionFor('YEARLY').period, 'MONTHLY', reason: 'unknown period falls back to the first');
    expect(p.optionFor('CUSTOM').label, '45 gün');
    expect(p.optionFor('QUARTERLY').label, '3 aylıq');
    expect(hexColor(p.primaryColor, const Color(0xFF000000)), const Color(0xFF7C3AED));
    expect(hexColor('red', const Color(0xFF000000)), const Color(0xFF000000));
    expect(p.features, ['PDF hesabat']);
    expect(p.recommended, isTrue);

    final legacy = Plan.fromJson({'code': 'MONTHLY', 'name': 'Aylıq', 'price': 19, 'durationMonths': 1});
    expect(legacy.periods.single.price, 19);
  });

  test('WhatsApp template and payment request parsing', () {
    final text = fillWhatsappTemplate(null, store: 'Market', plan: 'Biznes', period: '3 aylıq', amount: '70,00 ₼');
    expect(text, contains('Mağaza adı: Market'));
    expect(text, contains('Seçilmiş paket: Biznes'));
    expect(text, contains('Ödəniş məbləği: 70,00 ₼'));
    expect(fillWhatsappTemplate('Paket: [Paket adı]', store: '', plan: 'X', period: '', amount: ''), 'Paket: X');

    final r = PaymentRequest.fromJson({
      'id': '1', 'planCode': 'P1', 'planName': 'Biznes', 'status': 'ACTIVE', 'amount': 70,
      'periodCode': 'QUARTERLY', 'periodMonths': 3, 'requestedAt': '2026-11-09T10:00:00Z',
      'startDate': '2026-11-09', 'endDate': '2027-02-08', 'paymentMethod': 'CARD',
    });
    expect(r.requestStatus, 'APPROVED');
    expect(r.statusLabel, 'Təsdiqlənib');
    expect(r.periodText, '3 aylıq');

    final c = Contact.fromJson({'cardNumber': '4169738812345678', 'cardHolder': 'N M', 'whatsappTemplate': 'X'});
    expect(c.cardNumber, '4169738812345678');
    expect(c.whatsappTemplate, 'X');
  });
}
