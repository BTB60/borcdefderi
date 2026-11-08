import 'package:borcdefteri_mobile/format.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('money has two decimals and the manat sign', () {
    expect(formatMoney(0), endsWith('₼'));
    expect(formatMoney(9), contains('9,00'));
    expect(formatMoney(1234.5), contains('1'));
    expect(formatMoney(1234.5), endsWith('₼'));
  });

  test('dates are dd.MM.yyyy and null shows a dash', () {
    expect(formatDate(DateTime(2026, 3, 5)), '05.03.2026');
    expect(formatDate(null), '—');
  });

  test('friendly error replaces known codes only', () {
    expect(friendlyError('SUBSCRIPTION_REQUIRED', 'x'), contains('abunəlik'));
    expect(friendlyError('UNKNOWN', 'Serverdən mesaj'), 'Serverdən mesaj');
  });
}
