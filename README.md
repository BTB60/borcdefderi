# BORCDEFTERI

Çoxmərtəbəli (multi-tenant) borc və mühasibat idarəetmə platforması — Azərbaycandakı müstəqil mağazalar üçün.

Hər mağaza öz təcrid olunmuş iş sahəsinə malikdir: müştəri borcları, şirkət alacaqları, şirkət borcları, nağd/kart ödənişləri, tam maliyyə tarixçəsi, PDF/Excel hesabatları və işçi icazələri.

## Repozitoriya strukturu

| Qovluq | Məzmun | Texnologiya |
|---|---|---|
| `backend/` | REST API, JWT, tenant izolyasiyası, borc/ödəniş/abunəlik məntiqi, audit | Java 17, Spring Boot 3.4, Spring Security, Flyway, PostgreSQL |
| `web/` | Veb tətbiqi (mağaza paneli + Super Admin paneli), Azərbaycan dili | Next.js 15, React 19, TypeScript, Tailwind CSS |
| `mobile/` | Android/iOS tətbiqi (eyni REST API) | Flutter 3.47, Dart 3.13 |
| `docs/` | API, deployment, backup sənədləri | |
| `render.yaml` | Render Blueprint (backend) | |
| `.github/workflows/ci.yml` | CI: backend testləri + web build + Flutter analyze/test | |

## Status

Hazırdır və yoxlanılıb:

- **Backend** — 57 inteqrasiya testi real PostgreSQL üzərində keçir (`mvn test`).
  - Qeydiyyat, giriş (BCrypt, JWT), rol və abunəlik qapısı, sorğu limiti (rate limit)
  - Tenant izolyasiyası (başqa mağazanın qeydi 404, mağaza ID-si istəkdən qəbul olunmur, dayandırılmış mağaza)
  - Müştəri borcu, şirkət alacağı, şirkət borcu (üç ayrı bölmə), qismən/tam ödəniş, overpayment qadağası, versiya konflikti
  - Nağd, kart, köçürmə (M10/bank) və digər ödənişlər; ödəniş çeki (PDF); gündəlik/aylıq ödəniş hesabatı (Excel/PDF); maliyyə jurnalının yalnız əlavə etmə (append-only) olması (DB trigger ilə)
  - Abunəlik: Aylıq / 6 aylıq / İllik paket, M10 ödəniş məlumatı, təsdiq sorğusu (istinad nömrəsi + çek, bazada saxlanılır), Super Admin təsdiqi, PENDING → ACTIVE / REJECTED → EXPIRED, bitmə bildirişi, sayğac
  - İşçi icazələri, yalnız sahibə aid əməliyyatlar, işçinin deaktivləşdirilməsi
  - Panel rəqəmləri PostgreSQL-dən hesablanır; Excel və PDF eksportu (Azərbaycan hərfləri üçün DejaVu şrifti)
- **Web** — `npm run build`, `npm run lint`, `tsc --noEmit` keçir. Səhifələr: giriş, qeydiyyat, panel, borc bölmələri (axtarış, filtr, ödəniş, tarixçə, redaktə, eksport), ödənişlər, müştəri/şirkət siyahısı, abunəlik, işçilər, Super Admin paneli (mağazalar, sorğu təsdiqi, planlar, gəlir, istifadəçilər, audit).
- **Mobil (Flutter)** — `flutter analyze` xətasız, 19 unit test keçir (API client, sessiya, modellər, formatlama, sayğac, WhatsApp). Android release APK build alınır. Ekranlar: giriş/qeydiyyat, panel, üç borc bölməsi (axtarış, filtr, yeni borc, tarixçə), nağd/kart ödəniş, ödənişlər, abunəlik, bildirişlər. Bax: [mobile/README.md](mobile/README.md).
- **API canlı yoxlaması** — yerli PostgreSQL ilə backend işə salınıb və curl ilə qeydiyyat → abunəlik təsdiqi → müştəri → borc → qismən ödəniş → overpayment rədd → panel → Excel/PDF axını yoxlanılıb.

Hələ edilməyib (açıq qalan işlər):

- **Flutter** — tətbiq cihazda / emulatorda işə salınmayıb və canlı API-yə qarşı sınanmayıb. iOS build yalnız macOS + Xcode ilə mümkündür. Bəzi funksiyalar yalnız vebdədir (bax: `mobile/README.md`).
- **Canlı deployment** — Render, Neon və Vercel-də heç bir yerləşdirmə edilməyib. Konfiqurasiyalar hazırdır, lakin istifadəçinin öz hesabları və sirləri ilə icra olunmalıdır.
- **Brauzer ilə tam UI sınağı** — veb tətbiq yalnız build/lint/type-check ilə yoxlanılıb.
- **Dayandırma/yeniləmə bildirişləri** — hal-hazırda tətbiq daxilində bildirişdir (`/api/notifications`); e-poçt və push yoxdur.
- **Qəbz faylları** — yerli disk üzərində saxlanılır. Render-də disk müvəqqəti olduğu üçün davamlı disk və ya obyekt yaddaşı (S3 uyğun) tələb olunur.

## Yerli işə salma

Tələblər: JDK 17+, Maven 3.9+, Node.js 20+, PostgreSQL 14+ (və ya yalnız testlər üçün heç nə — testlər öz PostgreSQL-ini başladır).

### 1. Backend

```bash
cd backend
export JWT_SECRET="$(openssl rand -base64 48)"           # ən azı 32 simvol
export DATABASE_URL="jdbc:postgresql://localhost:5432/borcdefteri"
export DATABASE_USERNAME=borcdefteri
export DATABASE_PASSWORD=...
export ADMIN_EMAIL=admin@sizin-domen.az                  # ilk Super Admin (ixtiyari)
export ADMIN_PASSWORD=...                                # ən azı 12 simvol
mvn spring-boot:run
```

Testlər (real PostgreSQL, Docker tələb olunmur):

```bash
cd backend && mvn verify
```

### 2. Web

```bash
cd web
cp .env.example .env.local      # NEXT_PUBLIC_API_URL=http://localhost:8080
npm ci
npm run dev                     # http://localhost:3000
```

Backend-in `CORS_ALLOWED_ORIGINS` dəyərinə `http://localhost:3000` əlavə edildiyinə əmin olun.

## Təhlükəsizlik xülasəsi

- JWT (HS256), şifrələr BCrypt (cost 12). Token müddəti `JWT_EXPIRATION_MINUTES` ilə (standart 720 dəqiqə).
- Mağaza kontekstı **həmişə** verilənlər bazasında istifadəçinin üzvlüyündən təyin olunur; istəkdə `storeId` qəbul edilmir.
- Hər sorğuda: istifadəçi aktivdir, üzvlük aktivdir, mağaza `ACTIVE`-dir, abunəlik aktivdir (biznes modulları üçün), icazə var.
- Credential endpoint-lərində IP üzrə dəqiqəlik limit (`AUTH_RATE_LIMIT_PER_MINUTE`).
- Yükləmələr: yalnız PNG/JPG/PDF (magic bytes ilə), maksimum 5 MB, təsadüfi server adları, yol-keçid qorunması, yalnız icazəli API ilə yüklənmə.
- Maliyyə jurnalı (`financial_transactions`), ödənişlər (`payments`) və audit (`audit_logs`) üçün DB trigger-ləri UPDATE/DELETE-i rədd edir.
- Super Admin hesabı kodda yoxdur; `ADMIN_EMAIL`/`ADMIN_PASSWORD` ilə ilk işə salınmada yaradılır.
- Sirlər repozitoriyaya daxil edilmir (`.gitignore`, `.env.example` yalnız dəyişən adlarını göstərir).

## Sənədlər

- [API referansı](docs/API.md)
- [Deployment (Neon, Render, Vercel)](docs/DEPLOYMENT.md)
- [Verilənlər bazası ehtiyat nüsxəsi və bərpa](docs/BACKUP.md)
