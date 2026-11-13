# HesabPro mobil tətbiqi (Flutter)

Bu sənəd mobil tətbiqin hazırkı funksiyalarını və iOS build addımlarını təsvir edir.
Kod: `mobile/` (Flutter, Dart ^3.13). Versiya: `mobile/pubspec.yaml` → `version`.

## Server

- Production API: `https://borcdefderi.onrender.com` (Render, `main` branch-dan avtomatik deploy).
- Build zamanı ötürülür: `--dart-define=API_URL=https://borcdefderi.onrender.com`
  (verilməsə `http://10.0.2.2:8080` — yalnız Android emulyator üçün).
- Web: `https://hesabpro.cloud` (şifrə bərpa linkləri buraya gedir: `/sifre-berpa`).

## Ekranlar və funksiyalar

Aşağı panel (icazəyə görə): **Panel** (sahib və ya REPORTS), **Borclar**, **Ödənişlər**, **Daha**.

| Bölmə | Fayl | Nə edir |
|---|---|---|
| Giriş / qeydiyyat / şifrə | `auth_screens.dart`, `password_screens.dart` | Giriş, qeydiyyat, "Şifrəni unutdum" (e-poçt linki), məcburi şifrə dəyişmə |
| Panel | `dashboard_screen.dart`, `finance_cards.dart` | Sürətli düymələr (Satış, Anbar, Kassa, Xərclər); kartlar: **Toplam borc** (bütün açıq borclar), Ödədiyim, Aktiv/Bitmiş borclar, Müştəri borcu, Şirkətlər bizə borcludur, Bu gün yazılan, Gecikmiş, Aktiv müştərilər, Alınan ödənişlər, Şirkətlərə borcum, Şirkət borcu yaz. Hər kart öz siyahısını açır (axtarış, tarix, müştəri/şirkət filtri) |
| Borclar | `debts_screen.dart`, `debt_edit_sheet.dart`, `debt_increase_sheet.dart`, `account_sheet.dart`, `payment_edit_sheet.dart` | Müştəri borcu, şirkət alacağı/borcu; borc yaz, artır, redaktə, ödə; WhatsApp xatırlatma; tarixçədə ödənişə toxunanda sahib onu **düzəldə** və ya **ləğv edə** bilər |
| Ödənişlər | `payments_screen.dart`, `payment_edit_sheet.dart` | Ödəniş siyahısı, çek (PDF) paylaşma; ləğv edilmiş ödənişlər üstündən xətt çəkilmiş görünür və cəmə daxil olmur; yalnız mağaza sahibi ödənişə toxunub **düzəldə** (məbləğ, üsul, tarix, təsvir, qeyd) və ya səbəb yazıb **ləğv edə** bilər — borc qalığı və kassa serverdə avtomatik düzəlir |
| Müştərilər / Şirkətlər | `parties_screen.dart`, `party_profile_screen.dart` | Kart: tarixçə, qeydlər, çıxarış, kabinet linki, WhatsApp şablonları |
| Satış | `sales_screen.dart`, `barcode_scanner.dart` | Səbət, endirim, nağd/kart/nisyə, kamera ilə ardıcıl barkod skanı, çek, ləğv |
| Anbar | `inventory_screen.dart` | Məhsullar **şəkillə**; yazdıqca axtarış; skan → məhsul kartı (böyük şəkil, qiymət, qalıq); giriş/çıxış/düzəliş; yeni məhsul (barkod skanı + kamera/qalereyadan şəkil); şəkil dəyişmə; yuxarıda **Sayım** düyməsi |
| Anbar sayımı | `stock_count_screen.dart` | Sayımların siyahısı (+/− nəticə); **Yeni sayım** (bütün məhsullar və ya kateqoriya); məhsula toxun və ya **skan et** → sayılan miqdar (ardıcıl skan); sistem / sayılan / fərq (miqdar və ₼); filtr: sayılmayanlar, fərqi olanlar; **Tamamla** → fərqlər qalığa yazılır, sayım bağlanır; **sayım hesabatı** PDF/Excel paylaşma |
| Kassa və bank | `cash_screen.dart` | Balans, mədaxil, məxaric, köçürmə, gün sonu |
| Xərclər | `expenses_screen.dart` | Xərc, təkrarlanan xərc, büdcə |
| İşçilər | `employees_screen.dart` | Sahib üçün: işçi əlavə et, icazələr, şifrə sıfırlama |
| Abunəlik | `subscription_screen.dart` | Paketlər, M10/kart ödənişi, "Ödəniş etdim" |
| Abunəlik bitib | `subscription_expired_screen.dart` | Abunəlik aktiv deyilsə əsas ekran əvəzinə açılır (server 402 `SUBSCRIPTION_REQUIRED` qaytaranda da); hər dəqiqə və tətbiq önə gələndə statusu yoxlayır, ödəniş təsdiqlənəndə özü açılır |
| Əlaqə | `contact_screen.dart` | Dəstək: WhatsApp, zəng, əlavə nömrə (platforma admininin `/api/public/contact` ayarlarından) |
| Profil, bildirişlər | `profile_screen.dart`, `notifications_screen.dart`, `state/notification_alerts.dart` | Profil şəkli, şəxsi/mağaza məlumatı, şifrə; bildiriş səsi |

İcazələr (`Me.can`): `CUSTOMER_DEBTS`, `COMPANY_RECEIVABLES`, `COMPANY_PAYABLES`, `REPORTS`, `SALES`, `INVENTORY`, `CASH`, `EXPENSES`. Mağaza sahibi hamısını görür.

## Android build (Windows)

```bash
cd mobile
flutter build apk --release --dart-define=API_URL=https://borcdefderi.onrender.com
# nəticə: build/app/outputs/flutter-apk/app-release.apk
```

OneDrive qovluğunda Gradle bəzən `not a regular file` xətası verir — onda `mobile/build` qovluğunu silib yenidən yığın.

## iOS build (macOS)

Tələblər: macOS + Xcode, CocoaPods, Flutter; quraşdırma/TestFlight üçün Apple Developer hesabı ($99/il).
Pulsuz Apple ID ilə USB-dən quraşdırmaq olar, amma tətbiq 7 gündən sonra işləmir.

- Bundle ID: `az.borcdefteri.borcdefteriMobile` (Xcode → Runner → Signing & Capabilities-də öz Team-inizi seçin; lazım olsa unikal ID verin).
- Minimum iOS: 15.0.
- `ios/Runner/Info.plist`: kamera (barkod, məhsul/profil şəkli) və qalereya icazə mətnləri var.

```bash
cd mobile
flutter pub get
cd ios && pod install && cd ..
# Telefona USB ilə (Developer Mode açıq, Xcode-da Team seçilmiş):
flutter run --release --dart-define=API_URL=https://borcdefderi.onrender.com
# TestFlight / App Store üçün:
flutter build ipa --release --dart-define=API_URL=https://borcdefderi.onrender.com
# sonra build/ios/archive/Runner.xcarchive-i Xcode Organizer-dən (və ya Transporter ilə) yükləyin
```
