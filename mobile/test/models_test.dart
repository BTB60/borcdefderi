import 'package:borcdefteri_mobile/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Debt parses numeric money and status', () {
    final d = Debt.fromJson({
      'id': 'a', 'debtType': 'CUSTOMER_DEBT', 'partyId': 'p', 'partyName': 'Leyla', 'partyPhone': null,
      'amount': 150, 'paidAmount': 50.5, 'remainingAmount': 99.5, 'status': 'PARTIAL',
      'debtDate': '2026-11-01', 'dueDate': null, 'description': 'Mal', 'version': 2,
    });
    expect(d.remainingAmount, 99.5);
    expect(d.isPaid, isFalse);
    expect(d.dueDate, isNull);
    expect(d.version, 2);
  });

  test('Me derives owner status and permission checks', () {
    final owner = Me.fromJson({
      'userId': 'u', 'email': 'o@x.az', 'fullName': 'O', 'platformRole': 'USER', 'role': 'STORE_OWNER',
      'storeName': 'S', 'permissions': [], 'subscription': null,
    });
    expect(owner.isOwner, isTrue);
    expect(owner.can('COMPANY_PAYABLES'), isTrue);

    final cashier = Me.fromJson({
      'userId': 'u', 'email': 'c@x.az', 'fullName': 'C', 'platformRole': 'USER', 'role': 'EMPLOYEE',
      'storeName': 'S', 'permissions': ['CUSTOMER_DEBTS'],
      'subscription': {'active': true, 'status': 'ACTIVE', 'planName': 'Standart', 'endDate': '2026-12-31', 'daysRemaining': 30, 'renewalReminder': false},
    });
    expect(cashier.isOwner, isFalse);
    expect(cashier.can('CUSTOMER_DEBTS'), isTrue);
    expect(cashier.can('COMPANY_PAYABLES'), isFalse);
    expect(cashier.subscription!.daysRemaining, 30);
  });

  test('DebtPage parses totals', () {
    final page = DebtPage.fromJson({
      'items': [], 'total': 0, 'page': 0, 'size': 20, 'totals': {'amount': 0, 'paid': 0, 'remaining': 0},
    });
    expect(page.items, isEmpty);
    expect(page.totals.remaining, 0);
  });
}
