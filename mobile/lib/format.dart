import 'package:intl/intl.dart';

final _money = NumberFormat('#,##0.00', 'az');
final _day = DateFormat('dd.MM.yyyy');
final _dateTime = DateFormat('dd.MM.yyyy HH:mm');

String formatMoney(double value) => '${_money.format(value)} ₼';

String formatDate(DateTime? date) => date == null ? '—' : _day.format(date);

String formatDateTime(DateTime date) => _dateTime.format(date.toLocal());

const debtSectionTitles = {
  'CUSTOMER_DEBT': 'Müştəri borcları',
  'COMPANY_RECEIVABLE': 'Şirkət alacaqları',
  'COMPANY_PAYABLE': 'Şirkət borcları',
};

const debtStatusLabels = {
  'OPEN': 'Açıq',
  'PARTIAL': 'Qismən',
  'PAID': 'Ödənilib',
};

const methodLabels = {
  'CASH': 'Nağd',
  'CARD': 'Kart',
  'TRANSFER': 'Köçürmə',
  'OTHER': 'Digər',
};

const permissionLabels = {
  'CUSTOMER_DEBTS': 'Müştəri borcları',
  'COMPANY_RECEIVABLES': 'Şirkət alacaqları',
  'COMPANY_PAYABLES': 'Şirkət borcları',
  'REPORTS': 'Hesabatlar',
};

/// Maps a server error code to a friendly message when the server text is not enough.
String friendlyError(String code, String fallback) {
  switch (code) {
    case 'SUBSCRIPTION_REQUIRED':
      return 'Aktiv abunəlik yoxdur. Abunəlik bölməsindən aktivləşdirin.';
    case 'STORE_SUSPENDED':
      return 'Mağazanız dayandırılıb. Dəstək ilə əlaqə saxlayın.';
    case 'NETWORK':
      return fallback.isNotEmpty ? fallback : 'Serverlə əlaqə qurula bilmədi. İnternet bağlantınızı yoxlayın.';
    default:
      return fallback;
  }
}
