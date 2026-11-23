import 'package:intl/intl.dart';

final _money = NumberFormat('#,##0.00', 'az');
final _day = DateFormat('dd.MM.yyyy');
final _dateTime = DateFormat('dd.MM.yyyy HH:mm');

String formatMoney(double value) => '${_money.format(value)} ₼';

String formatDate(DateTime? date) => date == null ? '—' : _day.format(date);

String formatDateTime(DateTime date) => _dateTime.format(date.toLocal());

const debtSectionTitles = {
  'CUSTOMER_DEBT': 'Müştəri borcları',
  'COMPANY_RECEIVABLE': 'Tədarükçünün bizə borcu',
  'COMPANY_PAYABLE': 'Bizim tədarükçüyə borcumuz',
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
  'COMPANY_RECEIVABLES': 'Tədarükçünün bizə borcu',
  'COMPANY_PAYABLES': 'Bizim tədarükçüyə borcumuz',
  'REPORTS': 'Hesabatlar',
  'CASH': 'Kassa və bank',
  'EXPENSES': 'Xərclər və büdcə',
  'INVENTORY': 'Anbar',
  'SALES': 'Satış',
  'TILL_SETTINGS': 'Kassa parametrləri',
  'RETURN_APPROVE': 'Qaytarmanı təsdiq (menecer)',
};

/// Sections a package may include (every menu section except the subscription page), in menu order.
const planModuleLabels = {
  'CUSTOMER_DEBTS': 'Müştəri borcları',
  'COMPANY_RECEIVABLES': 'Tədarükçünün bizə borcu',
  'COMPANY_PAYABLES': 'Bizim tədarükçüyə borcumuz',
  'REPORTS': 'Panel, gəlir və mənfəət, hesabatlar',
  'SALES': 'Satış və kassa',
  'INVENTORY': 'Anbar, sayım, barkod',
  'CASH': 'Kassa və bank',
  'EXPENSES': 'Xərclər və büdcə',
  'POCKET': 'Cib pulu',
  'EMPLOYEES': 'İşçilər',
};

/// Employee's job (vəzifə).
const jobTitleLabels = {
  'CASHIER': 'Kassir',
  'STOREKEEPER': 'Anbardar',
  'ACCOUNTANT': 'Mühasib',
  'SELLER': 'Satıcı',
  'COURIER': 'Kuryer',
  'OTHER': 'Digər',
};

/// Permissions a job starts with; the owner can still tick / untick each one.
const jobTitlePermissions = {
  'CASHIER': ['SALES', 'CASH', 'CUSTOMER_DEBTS'],
  'STOREKEEPER': ['INVENTORY'],
  'ACCOUNTANT': ['REPORTS', 'CASH', 'EXPENSES', 'CUSTOMER_DEBTS', 'COMPANY_RECEIVABLES', 'COMPANY_PAYABLES'],
  'SELLER': ['SALES', 'CUSTOMER_DEBTS'],
  'COURIER': <String>[],
  'OTHER': <String>[],
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
