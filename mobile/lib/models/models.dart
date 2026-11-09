import 'dart:ui' show Color;

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
/// Payment & contact settings managed by the platform admin (card details only when signed in).
class Contact {
  /// WhatsApp number as entered by the admin.
  final String? phone;

  /// WhatsApp number in international digits for wa.me links.
  final String? whatsapp;
  final String? m10Account;
  final String? m10Recipient;

  /// M10 payment instructions.
  final String? paymentNote;
  final String? cardNumber;
  final String? cardHolder;
  final String? cardBank;
  final String? cardNote;
  final String? extraPhone;
  final String? extraContact;
  final String? whatsappTemplate;
  final String? paymentInstructions;
  final String? afterPaymentText;

  const Contact({
    this.phone,
    this.whatsapp,
    this.m10Account,
    this.m10Recipient,
    this.paymentNote,
    this.cardNumber,
    this.cardHolder,
    this.cardBank,
    this.cardNote,
    this.extraPhone,
    this.extraContact,
    this.whatsappTemplate,
    this.paymentInstructions,
    this.afterPaymentText,
  });

  factory Contact.fromJson(Map<String, dynamic> j) => Contact(
        phone: _str(j['phone']),
        whatsapp: _str(j['whatsapp']),
        m10Account: _str(j['m10Account']),
        m10Recipient: _str(j['m10Recipient']),
        paymentNote: _str(j['paymentNote']),
        cardNumber: _str(j['cardNumber']),
        cardHolder: _str(j['cardHolder']),
        cardBank: _str(j['cardBank']),
        cardNote: _str(j['cardNote']),
        extraPhone: _str(j['extraPhone']),
        extraContact: _str(j['extraContact']),
        whatsappTemplate: _str(j['whatsappTemplate']),
        paymentInstructions: _str(j['paymentInstructions']),
        afterPaymentText: _str(j['afterPaymentText']),
      );
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
  final String? phone;
  final bool hasAvatar;

  /// Changes whenever the profile picture changes (cache key for the downloaded image).
  final int? avatarVersion;

  /// Logged in with a temporary password: a new password must be set before using the app.
  final bool mustChangePassword;

  const Me({
    required this.userId,
    required this.email,
    required this.fullName,
    required this.platformRole,
    required this.role,
    required this.storeName,
    required this.permissions,
    required this.subscription,
    this.phone,
    this.hasAvatar = false,
    this.avatarVersion,
    this.mustChangePassword = false,
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
        phone: _str(j['phone']),
        hasAvatar: j['hasAvatar'] as bool? ?? false,
        avatarVersion: (j['avatarVersion'] as num?)?.toInt(),
        mustChangePassword: j['mustChangePassword'] as bool? ?? false,
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
  final String? note;
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
    this.note,
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
        note: _str(j['note']),
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
  final String? note;
  final double openBalance;
  final int openDebtCount;

  const Party({required this.id, required this.name, required this.phone, required this.openBalance, this.note, this.openDebtCount = 0});

  factory Party.fromJson(Map<String, dynamic> j) => Party(
        id: j['id'] as String,
        name: j['name'] as String,
        phone: _str(j['phone']),
        note: _str(j['note']),
        openBalance: _num(j['openBalance']),
        openDebtCount: _num(j['openDebtCount']).toInt(),
      );
}

class DashboardSummary {
  final double customerDebts;
  final double companyReceivables;
  final double companyPayables;
  final double cashReceived;
  final double cardReceived;
  final double otherReceived;
  final double todayTotal;
  final double monthTotal;

  const DashboardSummary({
    required this.customerDebts,
    required this.companyReceivables,
    required this.companyPayables,
    required this.cashReceived,
    required this.cardReceived,
    this.otherReceived = 0,
    required this.todayTotal,
    required this.monthTotal,
  });

  factory DashboardSummary.fromJson(Map<String, dynamic> j) => DashboardSummary(
        customerDebts: _num(j['customerDebts']),
        companyReceivables: _num(j['companyReceivables']),
        companyPayables: _num(j['companyPayables']),
        cashReceived: _num(j['cashReceived']),
        cardReceived: _num(j['cardReceived']),
        otherReceived: _num(j['otherReceived']),
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

/// One purchasable period of a package: calendar months, or a number of days for CUSTOM.
class PeriodOption {
  final String period;
  final int? months;
  final int? days;
  final double price;

  const PeriodOption({required this.period, this.months, this.days, required this.price});

  static const _labels = {
    'MONTHLY': '1 aylıq',
    'QUARTERLY': '3 aylıq',
    'SEMI_ANNUAL': '6 aylıq',
    'YEARLY': 'İllik',
  };

  /// "1 aylıq", "3 aylıq", "İllik", "45 gün"
  String get label => days != null ? '$days gün' : (_labels[period] ?? '${months ?? 1} ay');

  /// "3 ay", "12 ay (1 il)", "45 gün"
  String get durationText => days != null ? '$days gün' : (months == 12 ? '12 ay (1 il)' : '${months ?? 1} ay');

  factory PeriodOption.fromJson(Map<String, dynamic> j) => PeriodOption(
        period: j['period'] as String,
        months: (j['months'] as num?)?.toInt(),
        days: (j['days'] as num?)?.toInt(),
        price: _num(j['price']),
      );
}

/// Color from "#RRGGBB" (falls back to [fallback] when invalid).
Color hexColor(String? hex, Color fallback) {
  final h = (hex ?? '').replaceFirst('#', '');
  final v = h.length == 6 ? int.tryParse(h, radix: 16) : null;
  return v == null ? fallback : Color(0xFF000000 | v);
}

/// A subscription package designed by the platform admin (prices per period, advantages, limits, colours).
class Plan {
  final String code;
  final String name;
  final double price;
  final int durationMonths;
  final String? description;
  final List<String> features;
  final int? maxEmployees;
  final int? maxCustomers;
  final bool recommended;
  final List<PeriodOption> options;
  final String primaryColor;
  final String secondaryColor;
  final String backgroundColor;
  final String textColor;
  final String buttonColor;

  const Plan({
    required this.code,
    required this.name,
    required this.price,
    required this.durationMonths,
    this.description,
    this.features = const [],
    this.maxEmployees,
    this.maxCustomers,
    this.recommended = false,
    this.options = const [],
    this.primaryColor = '#101C35',
    this.secondaryColor = '#D6AD60',
    this.backgroundColor = '#FFFFFF',
    this.textColor = '#101C35',
    this.buttonColor = '#101C35',
  });

  /// "Aylıq", "6 aylıq", "İllik" …
  String get periodLabel {
    if (durationMonths == 1) return 'Aylıq';
    if (durationMonths == 12) return 'İllik';
    if (durationMonths % 12 == 0) return '${durationMonths ~/ 12} illik';
    return '$durationMonths aylıq';
  }

  /// Offered periods; older servers send only price + durationMonths.
  List<PeriodOption> get periods => options.isNotEmpty
      ? options
      : [PeriodOption(period: 'LEGACY', months: durationMonths, price: price)];

  PeriodOption optionFor(String? period) =>
      periods.firstWhere((o) => o.period == period, orElse: () => periods.first);

  factory Plan.fromJson(Map<String, dynamic> j) => Plan(
        code: j['code'] as String,
        name: j['name'] as String,
        price: _num(j['price']),
        durationMonths: (j['durationMonths'] as num).toInt(),
        description: _str(j['description']),
        features: ((j['features'] as List?) ?? const []).cast<String>(),
        maxEmployees: (j['maxEmployees'] as num?)?.toInt(),
        maxCustomers: (j['maxCustomers'] as num?)?.toInt(),
        recommended: j['recommended'] as bool? ?? false,
        options: ((j['options'] as List?) ?? const [])
            .map((e) => PeriodOption.fromJson(e as Map<String, dynamic>))
            .toList(),
        primaryColor: _str(j['primaryColor']) ?? '#101C35',
        secondaryColor: _str(j['secondaryColor']) ?? '#D6AD60',
        backgroundColor: _str(j['backgroundColor']) ?? '#FFFFFF',
        textColor: _str(j['textColor']) ?? '#101C35',
        buttonColor: _str(j['buttonColor']) ?? '#101C35',
      );
}

/// A store's payment request ("Ödəniş sorğularım").
class PaymentRequest {
  final String id;
  final String planCode;
  final String planName;
  final String status;

  /// PENDING / APPROVED / REJECTED
  final String requestStatus;
  final double amount;
  final String? periodCode;
  final int? periodMonths;
  final int? periodDays;
  final String? paymentMethod;
  final DateTime requestedAt;
  final DateTime? startDate;
  final DateTime? endDate;
  final String? rejectReason;

  const PaymentRequest({
    required this.id,
    required this.planCode,
    required this.planName,
    required this.status,
    required this.requestStatus,
    required this.amount,
    this.periodCode,
    this.periodMonths,
    this.periodDays,
    this.paymentMethod,
    required this.requestedAt,
    this.startDate,
    this.endDate,
    this.rejectReason,
  });

  static const statusLabels = {'PENDING': 'Yoxlanılır', 'APPROVED': 'Təsdiqlənib', 'REJECTED': 'Rədd edilib'};

  String get statusLabel => statusLabels[requestStatus] ?? requestStatus;

  String get periodText => periodDays != null
      ? '$periodDays gün'
      : PeriodOption(period: periodCode ?? '', months: periodMonths, price: 0).label;

  factory PaymentRequest.fromJson(Map<String, dynamic> j) {
    final status = j['status'] as String;
    return PaymentRequest(
      id: j['id'] as String,
      planCode: j['planCode'] as String,
      planName: j['planName'] as String,
      status: status,
      requestStatus: _str(j['requestStatus']) ??
          (status == 'ACTIVE' || status == 'EXPIRED' ? 'APPROVED' : status),
      amount: _num(j['amount']),
      periodCode: _str(j['periodCode']),
      periodMonths: (j['periodMonths'] as num?)?.toInt(),
      periodDays: (j['periodDays'] as num?)?.toInt(),
      paymentMethod: _str(j['paymentMethod']),
      requestedAt: DateTime.parse(j['requestedAt'] as String),
      startDate: _date(j['startDate']),
      endDate: _date(j['endDate']),
      rejectReason: _str(j['rejectReason']),
    );
  }
}

/// Default WhatsApp message; the admin can replace it (placeholders in square brackets).
const defaultWhatsappTemplate = 'Salam. HesabPro platformasında abunəlik paketi almaq istəyirəm.\n'
    'Mağaza adı: [Mağaza adı]\n'
    'Seçilmiş paket: [Paket adı]\n'
    'Müddət: [Müddət]\n'
    'Ödəniş məbləği: [Məbləğ]\n'
    'Zəhmət olmasa, ödəniş və aktivləşdirmə barədə məlumat verin.';

String fillWhatsappTemplate(String? template, {required String store, required String plan, required String period, required String amount}) {
  final t = (template == null || template.trim().isEmpty) ? defaultWhatsappTemplate : template;
  return t
      .replaceAll('[Mağaza adı]', store.isEmpty ? '—' : store)
      .replaceAll('[Paket adı]', plan)
      .replaceAll('[Müddət]', period)
      .replaceAll('[Məbləğ]', amount);
}

/// The signed-in user's own profile ("Parametrlər").
class Profile {
  final String email;
  final String fullName;
  final String? phone;
  final String platformRole;
  final String? role;
  final String? storeName;
  final String? storePhone;
  final String? storeAddress;
  final bool hasAvatar;
  final int? avatarVersion;

  const Profile({
    required this.email,
    required this.fullName,
    this.phone,
    required this.platformRole,
    this.role,
    this.storeName,
    this.storePhone,
    this.storeAddress,
    this.hasAvatar = false,
    this.avatarVersion,
  });

  bool get isOwner => role == 'STORE_OWNER';
  bool get isSuperAdmin => platformRole == 'SUPER_ADMIN';

  factory Profile.fromJson(Map<String, dynamic> j) => Profile(
        email: j['email'] as String,
        fullName: j['fullName'] as String,
        phone: _str(j['phone']),
        platformRole: j['platformRole'] as String? ?? 'USER',
        role: _str(j['role']),
        storeName: _str(j['storeName']),
        storePhone: _str(j['storePhone']),
        storeAddress: _str(j['storeAddress']),
        hasAvatar: j['hasAvatar'] as bool? ?? false,
        avatarVersion: (j['avatarVersion'] as num?)?.toInt(),
      );
}
