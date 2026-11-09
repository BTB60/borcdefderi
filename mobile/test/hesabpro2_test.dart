import 'package:borcdefteri_mobile/models/models.dart';
import 'package:borcdefteri_mobile/whatsapp.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const vars = TemplateVars(store: 'Test Mağaza', name: 'Aynur', total: 1000, paid: 300, remaining: 700, lastPayment: 300);

  test('balance template carries total, paid and remaining', () {
    final t = templateText('balance', vars);
    expect(t, contains('Aynur'));
    expect(t, contains('Ümumi borc: 1'));
    expect(t, contains('Ödənilmiş: 300'));
    expect(t, contains('Qalıq borc: 700'));
  });

  test('every template has a label and produces text', () {
    for (final key in templateLabels.keys) {
      expect(templateText(key, vars), startsWith('Salam, Aynur.'));
    }
  });

  test('customer code, contact and overdue info are read from the API', () {
    final p = Party.fromJson({
      'id': 'x',
      'name': 'Aynur',
      'phone': '+994501234567',
      'openBalance': 700,
      'openDebtCount': 1,
      'code': 'HP-000007',
      'createdAt': '2026-11-01T10:00:00Z',
      'lastContactAt': null,
      'lastActivityAt': '2026-11-09T10:00:00Z',
      'overdueDebts': 2,
    });
    expect(p.code, 'HP-000007');
    expect(p.overdueDebts, 2);
    expect(p.lastContactAt, isNull);
    expect(p.lastActivityAt, isNotNull);
  });
}
