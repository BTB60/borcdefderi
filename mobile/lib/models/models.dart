double _num(Object? v) => v == null ? 0 : (v as num).toDouble();

String? _str(Object? v) => v as String?;

DateTime? _date(Object? v) => v == null ? null : DateTime.parse(v as String);

class SubscriptionSummary {
  final bool active;
  final String status;
  final String? planName;
  final DateTime? endDate;
  final int daysRemaining;
  final bool renewalReminder;

  /// Exact moment access ends (drives the countdown). Null when the store never had a subscription.
  final DateTime? expiresAt;

  const SubscriptionSummary({
    required this.active,
    required this.status,
    required this.planName,
    required this.endDate,
    required this.daysRemaining,
    required this.renewalReminder,
    this.expiresAt,
  });

  factory SubscriptionSummary.fromJson(Map<String, dynamic> j) => SubscriptionSummary(
        active: j['active'] as bool? ?? false,
        status: j['status'] as String? ?? 'NONE',
        planName: _str(j['planName']),
        endDate: _date(j['endDate']),
        daysRemaining: (j['daysRemaining'] as num?)?.toInt() ?? 0,
        renewalReminder: j['renewalReminder'] as bool? ?? false,
        expiresAt: _date(j['expiresAt']),
      );
}

/// Admin contact shown on "Abunə ol". [whatsapp] holds digits only.
class Contact {
  final String? phone;
  final String? whatsapp;

  const Contact({this.phone, this.whatsapp});

  factory Contact.fromJson(Map<String, dynamic> j) =>
      Contact(phone: _str(j['phone']), whatsapp: _str(j['whatsapp']));
}

/// Days/hours/minutes/seconds left until [expiresAt]. [warning] = expired or fewer than [warnDays] days left.
class Countdown {
  static const warnDays = 7;

  final bool expired;
  final int days;
  final int hours;
  final int minutes;
  final int seconds;

  const Countdown({required this.expired, required this.days, required this.hours, required this.minutes, required this.seconds});

  bool get warning => expired || days < warnDays;

  static Countdown? of(DateTime? expiresAt, DateTime now) {
    if (expiresAt == null) return null;
    final left = expiresAt.difference(now);
    if (left.inSeconds <= 0) {
      return const Countdown(expired: true, days: 0, hours: 0, minutes: 0, seconds: 0);
    }
    final total = left.inSeconds;
    return Countdown(
      expired: false,
      days: total ~/ 86400,
      hours: (total % 86400) ~/ 3600,
      minutes: (total % 3600) ~/ 60,
      seconds: total % 60,
    );
  }

  String get text {
    if (expired) return 'Vaxtı bitib';
    String two(int n) => n.toString().padLeft(2, '0');
    return '$days gün ${two(hours)}:${two(minutes)}:${two(seconds)}';
  }
}

class Me {
  final String userId;
  final String email;
  final String fullName;
  final String platformRole;
  final String? role;
  final String? storeName;
  final List<String> permissions;
  final SubscriptionSummary? subscription;

  const Me({
    required this.userId,
    required this.email,
    required this.fullName,
    required this.platformRole,
    required this.role,
    required this.storeName,
    required this.permissions,
    required this.subscription,
  });

  bool get isOwner => role == 'STORE_OWNER';
  bool get isSuperAdmin => platformRole == 'SUPER_ADMIN';
  bool can(String permission) => isOwner || permissions.contains(permission);

  factory Me.fromJson(Map<String, dynamic> j) => Me(
        userId: j['userId'] as String,
        email: j['email'] as String,
        fullName: j['fullName'] as String,
        platformRole: j['platformRole'] as String? ?? 'USER',
        role: _str(j['role']),
        storeName: _str(j['storeName']),
        permissions: ((j['permissions'] as List?) ?? const []).cast<String>(),
        subscription: j['subscription'] == null
            ? null
            : SubscriptionSummary.fromJson(j['subscription'] as Map<String, dynamic>),
      );
}

class Debt {
  final String id;
  final String debtType;
  final String partyId;
  final String partyName;
  final String? partyPhone;
  final double amount;
  final double paidAmount;
  final double remainingAmount;
  final String status;
  final DateTime debtDate;
  final DateTime? dueDate;
  final String? description;
  final int version;

  const Debt({
    required this.id,
    required this.debtType,
    required this.partyId,
    required this.partyName,
    required this.partyPhone,
    required this.amount,
    required this.paidAmount,
    required this.remainingAmount,
    required this.status,
    required this.debtDate,
    required this.dueDate,
    required this.description,
    required this.version,
  });

  bool get isPaid => status == 'PAID';

  factory Debt.fromJson(Map<String, dynamic> j) => Debt(
        id: j['id'] as String,
        debtType: j['debtType'] as String,
        partyId: j['partyId'] as String,
        partyName: j['partyName'] as String? ?? '',
        partyPhone: _str(j['partyPhone']),
        amount: _num(j['amount']),
        paidAmount: _num(j['paidAmount']),
        remainingAmount: _num(j['remainingAmount']),
        status: j['status'] as String,
        debtDate: DateTime.parse(j['debtDate'] as String),
        dueDate: _date(j['dueDate']),
        description: _str(j['description']),
        version: (j['version'] as num?)?.toInt() ?? 0,
      );
}

class DebtTotals {
  final double amount;
  final double paid;
  final double remaining;
  const DebtTotals(this.amount, this.paid, this.remaining);

  factory DebtTotals.fromJson(Map<String, dynamic> j) => DebtTotals(_num(j['amount']), _num(j['paid']), _num(j['remaining']));
}

class DebtPage {
  final List<Debt> items;
  final int total;
  final DebtTotals totals;

  const DebtPage(this.items, this.total, this.totals);

  factory DebtPage.fromJson(Map<String, dynamic> j) => DebtPage(
        (j['items'] as List).map((e) => Debt.fromJson(e as Map<String, dynamic>)).toList(),
        (j['total'] as num).toInt(),
        DebtTotals.fromJson(j['totals'] as Map<String, dynamic>),
      );
}

class Payment {
  final String id;
  final String debtType;
  final String partyName;
  final double amount;
  final String paymentMethod;
  final DateTime paidAt;
  final String? description;

  const Payment({
    required this.id,
    required this.debtType,
    required this.partyName,
    required this.amount,
    required this.paymentMethod,
    required this.paidAt,
    required this.description,
  });

  factory Payment.fromJson(Map<String, dynamic> j) => Payment(
        id: j['id'] as String,
        debtType: j['debtType'] as String,
        partyName: j['partyName'] as String? ?? '',
        amount: _num(j['amount']),
        paymentMethod: j['paymentMethod'] as String,
        paidAt: DateTime.parse(j['paidAt'] as String),
        description: _str(j['description']),
      );
}

class Party {
  final String id;
  final String name;
  final String? phone;
  final double openBalance;

  const Party({required this.id, required this.name, required this.phone, required this.openBalance});

  factory Party.fromJson(Map<String, dynamic> j) => Party(
        id: j['id'] as String,
        name: j['name'] as String,
        phone: _str(j['phone']),
        openBalance: _num(j['openBalance']),
      );
}

class DashboardSummary {
  final double customerDebts;
  final double companyReceivables;
  final double companyPayables;
  final double cashReceived;
  final double cardReceived;
  final double todayTotal;
  final double monthTotal;

  const DashboardSummary({
    required this.customerDebts,
    required this.companyReceivables,
    required this.companyPayables,
    required this.cashReceived,
    required this.cardReceived,
    required this.todayTotal,
    required this.monthTotal,
  });

  factory DashboardSummary.fromJson(Map<String, dynamic> j) => DashboardSummary(
        customerDebts: _num(j['customerDebts']),
        companyReceivables: _num(j['companyReceivables']),
        companyPayables: _num(j['companyPayables']),
        cashReceived: _num(j['cashReceived']),
        cardReceived: _num(j['cardReceived']),
        todayTotal: _num(j['todayTotal']),
        monthTotal: _num(j['monthTotal']),
      );
}

class AppNotification {
  final String severity;
  final String title;
  final String message;

  const AppNotification({required this.severity, required this.title, required this.message});

  factory AppNotification.fromJson(Map<String, dynamic> j) => AppNotification(
        severity: j['severity'] as String? ?? 'INFO',
        title: j['title'] as String? ?? '',
        message: j['message'] as String? ?? '',
      );
}

class Plan {
  final String code;
  final String name;
  final double price;
  final int durationMonths;

  const Plan({required this.code, required this.name, required this.price, required this.durationMonths});

  /// "Aylıq", "6 aylıq", "İllik" …
  String get periodLabel {
    if (durationMonths == 1) return 'Aylıq';
    if (durationMonths == 12) return 'İllik';
    if (durationMonths % 12 == 0) return '${durationMonths ~/ 12} illik';
    return '$durationMonths aylıq';
  }

  factory Plan.fromJson(Map<String, dynamic> j) => Plan(
        code: j['code'] as String,
        name: j['name'] as String,
        price: _num(j['price']),
        durationMonths: (j['durationMonths'] as num).toInt(),
      );
}
