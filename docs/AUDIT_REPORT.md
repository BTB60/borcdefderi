# BORCDEFTERI — Audit və inkişaf hesabatı

Tarix: 09.11.2026 · Branch: `audit/production-hardening` · Baza: `main` (`c18eb8b`)

Statuslar: **PASS** — tam işləyir və test edilib · **FAIL** — işləmir · **WARNING** — işləyir, amma risk/məhdudiyyət var · **NOT TESTED** — yoxlanılmayıb.

Heç bir funksiya yalnız README-yə əsasən işlək hesab edilməyib. Hər status aşağıdakı testlərdən birinə əsaslanır.

## 1. Ümumi vəziyyət

Sistem lokal mühitdə uçdan-uca işləyir: qeydiyyat → paket seçimi → M10 ödəniş məlumatı → təsdiq sorğusu → Super Admin təsdiqi → mağaza aktiv → borc/ödəniş/çek/hesabat. Balans hesablamaları bazada (row lock + CHECK constraint + dəyişdirilməz maliyyə jurnalı) qorunur. Tenant izolyasiyası server tərəfində hər sorğuda yoxlanılır.

**Deploy edilməyib**: Render, Vercel və Neon hesablarına çıxışım yoxdur. Konfiqurasiya hazırdır (bölmə 9).

## 2. Test nəticələri (real icra olunub)

| Test | Nəticə |
|---|---|
| Backend build (`mvn package`) | PASS |
| Backend inteqrasiya testləri (real PostgreSQL, 9 sinif) | **PASS — 63/63** |
| Frontend lint (`next lint`) | PASS — 0 xəta |
| Frontend typecheck (`tsc --noEmit`) | PASS |
| Frontend production build (`next build`) | PASS — 12 səhifə |
| Flutter analyze | PASS — 0 problem |
| Flutter testləri | **PASS — 23/23** |
| Android release APK (`flutter build apk --release`) | PASS — 50.6 MB |
| Miqrasiya V2→V3 mövcud məlumatla (12 cədvəl, sətir sayı əvvəl/sonra eyni) | PASS |
| API uçdan-uca yoxlama (işləyən server, miqrasiya olunmuş baza) | **PASS — 16/16** |
| Brauzerdə yoxlama (admin: ayarlar, mağazalar, sayğac, aktivləşdirmə, tarixçə; sahib: abunə pəncərəsi) | PASS |
| iOS build | NOT TESTED — macOS + Xcode tələb edir |

## 3. Tapılan və düzəldilən xətalar

| # | Problem | Ciddilik | Status |
|---|---|---|---|
| 1 | Android release-də `INTERNET` icazəsi yox idi — Play Store versiyası serverə qoşula bilməzdi | Kritik | Düzəldildi |
| 2 | Render diski hər deploy-da silinir → abunəlik çekləri itirdi | Kritik | Düzəldildi: çeklər PostgreSQL-də (V3) |
| 3 | `X-Forwarded-For` saxtalaşdırılaraq rate limit keçilə və audit IP-si saxtalaşdırıla bilirdi | Yüksək | Düzəldildi (`forward-headers-strategy: native`) |
| 4 | Mobil tətbiq internet olmayanda istifadəçini hesabdan çıxarırdı; token bitəndə çıxış etmirdi | Yüksək | Düzəldildi (yalnız 401-də çıxış, oflayn ekranı) |
| 5 | Dashboard yalnız nağd/kart sayırdı (digər üsullar yox idi) | Orta | Düzəldildi: Köçürmə və Digər əlavə edildi, cəmlər hamısını sayır |
| 6 | Web-də yeni borcun tarixi UTC ilə doldurulurdu (Bakıda 00:00–04:00 arası "dünən") | Orta | Düzəldildi |
| 7 | "İ" hərfi ilə axtarış (İlham və s.) işləmirdi; `%` və `_` hər şeyə uyğun gəlirdi | Orta | Düzəldildi |
| 8 | 72 baytdan uzun şifrə (məs. "ə" ilə) → 500 xətası | Aşağı | Düzəldildi → 400 |
| 9 | İşçi icazələrində `null` → 500; `payerReference` > 120 simvol → 409 | Aşağı | Düzəldildi → 400 |
| 10 | Mövcud olmayan e-poçt üçün giriş daha sürətli cavab verirdi (hesab varlığını ifşa edir) | Aşağı | Düzəldildi |
| 11 | Admin gəlir statistikası və hesabat tarixləri server/baza saat qurşağında idi | Aşağı | Düzəldildi (Asia/Baku) |
| 12 | Vaxtı keçmiş abunəliyi uzatmaq keçmiş tarixə gün əlavə edirdi | Aşağı | Düzəldildi |
| 13 | Bildirişlərdə icazə filtri LIMIT-dən sonra tətbiq olunurdu | Aşağı | Düzəldildi |
| 14 | Yükləmə adlarında Azərbaycan hərfləri pozulurdu | Aşağı | Düzəldildi (RFC 5987) |
| 15 | Abunəlik bitəndə mağazaya bildiriş yox idi | Orta | Düzəldildi (tətbiqdaxili bildiriş) |
| 16 | Admin paneldə **“Təsdiqlə” düyməsi işləmirdi**: `window.confirm` bəzi brauzerlərdə/daxili pəncərələrdə bloklanır və dərhal `false` qaytarır. “Dayandır” düyməsində də eyni problem | Yüksək | Düzəldildi: saytın öz təsdiq pəncərəsi (`useConfirm`), brauzerdə yoxlanılıb |

## 4. Funksiyalar

### Borc hesablama sistemi
| Funksiya | Status | Sübut |
|---|---|---|
| Müştəri borcları (əlavə, siyahı, axtarış, filtr, redaktə) | PASS | DebtPaymentTest, smoke |
| Müştəridən ödəniş / qismən ödəniş | PASS | DebtPaymentTest, smoke |
| Artıq ödəniş qadağası, eyni anda iki ödəniş (row lock) | PASS | DebtPaymentTest |
| Firma borcları (ödənilməli) və firmadan alınacaqlar | PASS | TenantIsolationTest, ProductionHardeningTest |
| Nağd, kart, köçürmə (M10/bank), digər | PASS | ProductionHardeningTest, smoke |
| Borc tarixçəsi (dəyişdirilməz jurnal, DB trigger) | PASS | DebtPaymentTest |
| Gündəlik və aylıq hesabat (panel + Excel/PDF eksport) | PASS | ProductionHardeningTest, smoke |
| Borc bölmələrinin PDF/Excel çıxarışı | PASS | ReportExportTest, smoke |
| Ödəniş çeki (PDF, mağaza adı, qalıq borc) | PASS | ProductionHardeningTest, smoke, PDF baxılıb |
| WhatsApp borc xatırlatması və ödəniş bildirişi | **WARNING** | Hazır mətnlə WhatsApp açılır, mesajı mağaza özü göndərir. Avtomatik göndərmə WhatsApp Business API (pullu provayder) tələb edir |
| Ödəniş çekini WhatsApp ilə göndərmək | PASS | Mesajda çekin təhlükəsiz linki (HMAC, təxmin edilə bilməz, girişsiz açılır); telefonlarda PDF faylın özü də paylaşılır (web: Web Share, mobil: share_plus). ProductionHardeningTest + canlı yoxlama |

### Parametrlər (admin, sahib, işçi)
| Funksiya | Status |
|---|---|
| Şifrəni dəyişmək (cari şifrə ilə; digər cihazlarda avtomatik çıxış) | PASS (ProfileTest + canlı yoxlama) |
| Şəxsi məlumatlar (ad, telefon, e-poçt — e-poçt üçün şifrə tələb olunur) | PASS |
| Mağaza məlumatları (yalnız sahib) | PASS |
| Profil şəkli (PNG/JPG, 256×256, EXIF silinir, yalnız özünə görünür) | PASS. Mobil kamera/qalereya cihazda NOT TESTED |
| Deaktiv istifadəçinin köhnə tokeni rədd olunur | PASS |

### Abunəlik sistemi
| Funksiya | Status |
|---|---|
| Qeydiyyatdan sonra mağaza gözləmədə (aktiv abunəlik olmadan borc modulları bağlı) | PASS |
| Aylıq / 6 aylıq / İllik paket seçimi | PASS |
| M10 ödəniş məlumatı (admin "Ayarlar"-dan dəyişir) | PASS |
| "Ödəniş etdim" təsdiq sorğusu (M10 istinad nömrəsi + istəyə bağlı çek) | PASS (API/test). Web pəncərəsi brauzerdə açılıb, göndərmə düyməsi brauzerdə basılmayıb |
| Ödəniş təsdiqlənmədən aktivləşmə yoxdur | PASS |
| Super Admin təsdiqi / birbaşa aktivləşdirmə → avtomatik aktiv | PASS |
| Bitmiş abunəlik: borc/ödəniş modulları bağlı, bildiriş | PASS |
| Sayğac (sahib və admin), az qalan/bitən qırmızı | PASS |
| Bitmə bildirişi SMS/e-poçt ilə | **WARNING** — yalnız tətbiqdaxili; SMS/e-poçt provayderi yoxdur |

### Super Admin paneli
| Funksiya | Status |
|---|---|
| Bütün mağazalar, yeni qeydiyyatlar (7 gün) filtri | PASS |
| Mağazanı dayandırmaq / bərpa etmək | PASS |
| Paketləri idarə etmək (qiymət, müddət, satışda) | PASS |
| Ödənişləri təsdiq / rədd, M10 istinadı və çekə baxmaq | PASS |
| Abunəlik müddətini izləmək (sayğac, qırmızı xəbərdarlıq) | PASS |
| Gəlir və statistika | PASS |
| Mağaza fəaliyyət tarixçəsi (kim, nə vaxt, nə) | PASS |

### Multi-tenant təhlükəsizliyi
| Yoxlama | Status |
|---|---|
| Mağaza ID heç vaxt sorğudan götürülmür, istifadəçinin üzvlüyündən hesablanır | PASS |
| Başqa mağazanın borcu/ödənişi/çeki/qəbzi URL ilə açılmır (404) | PASS (TenantIsolationTest, smoke) |
| Super Admin və mağaza səlahiyyətləri ayrıdır (`/api/admin/**` + servis yoxlaması) | PASS |
| İşçi icazələri bölmə üzrə | PASS (EmployeePermissionTest) |
| Kritik əməliyyatlar audit log-da (dəyişdirilməz) | PASS |

### Flutter mobil tətbiq
| Funksiya | Status |
|---|---|
| Giriş/qeydiyyat, panel, müştəri və firma borcları, ödəniş (4 üsul), tarixçə, bildirişlər, abunəlik (M10 + təsdiq sorğusu), WhatsApp | PASS (kod, analyze, unit testlər). Yeni ekranlar cihazda **NOT TESTED** |
| Eyni API və baza | PASS |
| Android | PASS (release APK build). Cihazda NOT TESTED (emulyator arm64, kompüter x86_64) |
| iOS | NOT TESTED (macOS lazımdır) |
| Mobildə çek PDF-i və çek yükləmə | **WARNING** — yalnız web-də |

## 5. Təhlükəsizlik

| Mövzu | Status |
|---|---|
| BCrypt (cost 12), JWT (HS256, ≥32 simvol sirr, env-dən) | PASS |
| Sirlər repozitoriyada yoxdur (yoxlanılıb) | PASS |
| SQL injection: bütün dəyərlər parametrlə ötürülür | PASS |
| Fayl yükləmə: magic-bytes, 5 MB, birbaşa statik xidmət yoxdur | PASS |
| CORS yalnız icazəli domenlər | PASS |
| JWT brauzerdə `localStorage`-da; geri çağırma yoxdur (12 saat etibarlı) | WARNING — deaktiv istifadəçi/mağaza hər sorğuda bazadan yoxlanır, amma XSS olarsa token oğurlana bilər |
| Rate limit yalnız tək instans üçün (yaddaşda) | WARNING |
| `npm audit`: Next.js daxilindəki postcss (build zamanı) | WARNING — real risk aşağıdır, Next 16-ya keçid tələb edir |
| Şifrə bərpası (unutdum) | WARNING — funksiya yoxdur |
| Lokal test adminı `admin/admin123` | WARNING — yalnız bu kompüterdəki test bazasında; production-da ≥12 simvol tələb olunur |

## 6. Database

| Mövzu | Status |
|---|---|
| Cədvəllər, FK (tenant-a bağlı kompozit FK-lar), CHECK (balans = məbləğ − ödənilən, qalıq ≥ 0) | PASS |
| Index-lər (V3-də 3 əlavə: abunəlik sorğuları, audit vaxtı, jurnal→ödəniş) | PASS |
| Unique: e-poçt, istifadəçi başına bir mağaza, plan kodu | PASS |
| Miqrasiyalar V1–V3 yalnız əlavə edir, məlumat silmir (real məlumatla yoxlanılıb) | PASS |
| Transaction-lar: ödəniş/redaktə row lock, abunəlik sorğuları mağaza lock-u | PASS |
| Ehtiyat nüsxə strategiyası (`docs/BACKUP.md`, Neon PITR) | WARNING — sənədləşdirilib, icra edilməyib |
| Böyük/kiçik hərfdən asılı olmayan axtarış qeyri-ASCII hərflər üçün (`ə`, `İ`) bazanın locale-indən asılıdır | WARNING |

## 7. İşləməyən funksiyalar (FAIL)

Testlərdə **FAIL olan funksiya yoxdur**. Tam olmayan hissələr yuxarıda WARNING kimi göstərilib.

## 8. Dəyişdirilmiş/əlavə edilmiş əsas fayllar

- Backend: `V3__payment_methods_receipts_in_db.sql`, `PaymentReportService`, `ReceiptStorage`, `SubscriptionService`, `PlatformSettingsService`, `DashboardService`, `common/Search|Passwords|FileResponses`, `application.yml`, `ProductionHardeningTest` (+9 test)
- Web: `borclar/[type]`, `odenisler`, `abunelik`, `admin`, `dashboard`, `lib/whatsapp.ts`
- Mobil: `api_client`, `session`, `main`, `debts_screen`, `more_screen`, `payments_screen`, `dashboard_screen`, `whatsapp.dart`, AndroidManifest, `build.gradle.kts` (release imza)
- Deploy: `docs/DEPLOYMENT.md`, `README.md`, `.gitattributes`

## 9. Deploy vəziyyəti

| Hissə | Hazırlıq | Deploy |
|---|---|---|
| Backend → Render (`render.yaml`, Dockerfile, `/api/health`) | Hazır | Edilməyib |
| Baza → Neon (Flyway avtomatik) | Hazır | Edilməyib |
| Web → Vercel (`web/vercel.json`, Root Directory `web`) | Hazır | Edilməyib |
| Android APK | Build alınır | İmza açarı lazımdır |
| iOS | Kod hazır | macOS lazımdır |

## 10. Sizin etməli olduğunuz işlər

1. Bu branch-i yoxlayıb `main`-ə birləşdirin (PR).
2. **Neon**: baza yaradın, JDBC URL-i götürün.
3. **Render**: Blueprint ilə qoşun; `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `JWT_SECRET` (`openssl rand -base64 48`), `ADMIN_EMAIL`, `ADMIN_PASSWORD` (≥12 simvol, güclü), `CORS_ALLOWED_ORIGINS` (Vercel domeni) yazın.
4. **Vercel**: Root Directory `web`, `NEXT_PUBLIC_API_URL` = Render URL.
5. İlk girişdən sonra admin panelində **Ayarlar**: M10 hesabı, alan, telefon; **Paketlər**: qiymətlər.
6. **Android**: keystore yaradıb `mobile/android/key.properties` (repoya yazmayın), sonra `flutter build apk --release --dart-define=API_URL=https://<render-host>`.
7. **iOS**: Mac + Apple Developer hesabı ilə build.
8. İstəyə görə: avtomatik WhatsApp/SMS üçün provayder (WhatsApp Business API, SMS gateway), şifrə bərpası üçün e-poçt xidməti.
