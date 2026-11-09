# Deployment

Bu sənəd hazır konfiqurasiyaları izah edir. **Heç bir yerləşdirmə icra edilməyib.** Aşağıdakı addımları öz Neon, Render və Vercel hesablarınızla icra etməlisiniz.

## 1. Neon (PostgreSQL)

1. Neon-da layihə və verilənlər bazası yaradın (məs. `borcdefteri`).
2. Bağlantı sətrini götürün və **JDBC formatında** yazın:

   ```
   jdbc:postgresql://<neon-host>/borcdefteri?sslmode=require
   ```

3. İstifadəçi adı və şifrəni ayrıca `DATABASE_USERNAME` / `DATABASE_PASSWORD` kimi verin (URL-də saxlamayın).
   **Birbaşa host-u** (`-pooler` olmayan) istifadə edin: tətbiqin öz connection pool-u var, Flyway isə sessiya bağlantısı tələb edir.
   Neon `channel_binding=require` parametri Java sürücüsü üçün lazım deyil — `?sslmode=require` kifayətdir.
   Render servisi Neon ilə **eyni regionda** olmalıdır (`render.yaml`-da `region: ohio` = AWS us-east-2).
4. Flyway ilk işə salınmada cədvəlləri və planları özü yaradır (`backend/src/main/resources/db/migration`). Əl ilə SQL işlətmək lazım deyil.
5. Neon-da avtomatik ehtiyat nüsxə / point-in-time restore planınızı yoxlayın (bax: [BACKUP.md](BACKUP.md)).

## 2. Render (Spring Boot API)

Kök qovluqdakı `render.yaml` Blueprint-dir (servis: `borcdefteri-api`, `rootDir: backend`, Docker).

1. Render-də **New → Blueprint** seçib bu repozitoriyanı qoşun.
2. Aşağıdakı dəyişənləri dashboard-da doldurun (`sync: false` olanlar):

   | Dəyişən | Təsvir |
   |---|---|
   | `DATABASE_URL` | Neon JDBC URL (yuxarıya bax) |
   | `DATABASE_USERNAME` / `DATABASE_PASSWORD` | Neon istifadəçisi |
   | `JWT_SECRET` | **Render özü yaradır** (`generateValue: true`) — heç nə yazmağa ehtiyac yoxdur |
   | `ADMIN_EMAIL` / `ADMIN_PASSWORD` | İlk Super Admin (şifrə ≥12 simvol). Hesab yalnız ilk işə salınmada yaradılır |
   | `APP_WEB_URL` | Vercel URL-i (şifrə bərpa linkləri üçün), məs. `https://borcdefteri.vercel.app` |
   | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | **İstəyə bağlı** — "Şifrəni unutdum" e-poçtu üçün SMTP (Gmail app password, Brevo, Mailgun və s.). Boşdursa istifadəçilər şifrəni administrator vasitəsilə bərpa edir |
   | `CORS_ALLOWED_ORIGINS` | Vercel URL-i, məs. `https://borcdefderi.vercel.app`. Vergüllə bir neçə dəyər ola bilər; `*` nümunəsi də qəbul olunur — Vercel preview linkləri üçün: `https://borcdefderi.vercel.app,https://borcdefderi-*-btb60s-projects.vercel.app` |

3. Health check: `/api/health` (`render.yaml`-da təyin olunub).
4. **Qəbz faylları:** abunəlik çekləri **PostgreSQL-də** saxlanılır (`subscription_receipts.content`, V3 miqrasiyası), ona görə Render diskinin deploy zamanı silinməsi onlara təsir etmir. Persistent Disk lazım deyil. `UPLOAD_DIR` yalnız V3-dən əvvəl diskə yazılmış köhnə qəbzləri oxumaq üçün qalıb.
5. Deploydan sonra yoxlayın:

   ```bash
   curl https://<api-host>/api/health
   # {"status":"UP","database":"UP","time":"..."}
   ```

Dockerfile: `backend/Dockerfile` (multi-stage, JRE 17, qeyri-root istifadəçi, HEALTHCHECK).

## 3. Vercel (Next.js web)

1. Vercel-də **Add New → Project**, repozitoriyanı seçin.
2. **Root Directory:** `web`. Framework: Next.js (`web/vercel.json` artıq təyin edir).
3. Environment Variables:

   | Dəyişən | Dəyər |
   |---|---|
   | `NEXT_PUBLIC_API_URL` | Render API URL-i, məs. `https://borcdefteri-api.onrender.com` |

   `NEXT_PUBLIC_*` dəyişənləri build zamanı daxil edilir — dəyişəndən sonra yenidən deploy edin.
4. Vercel domeninizi Render-də `CORS_ALLOWED_ORIGINS`-ə əlavə edin.

## 4. İlk girişdən sonra (Super Admin)

1. `ADMIN_EMAIL` / `ADMIN_PASSWORD` ilə `/login`-dən daxil olun → **Platforma idarəetməsi**.
2. **Ödəniş və əlaqə**: M10 nömrəsi, hesab sahibi, bank kartı (nömrə, sahib, bank), WhatsApp nömrəsi, əlavə əlaqə, WhatsApp mesaj şablonu və ödəniş təlimatı yazın. Bu məlumatlar yalnız bazada saxlanılır (kodda və GitHub-da yoxdur).
3. **Abunəlik paketləri**: paket yaradın/redaktə edin — 1/3/6/12 aylıq və xüsusi müddət qiymətləri, üstünlüklər, işçi/müştəri limiti, rənglər (canlı önizləmə ilə). Köhnə paketlər (Aylıq / 6 aylıq / İllik) miqrasiyadan sonra olduğu kimi qalır.

## 5. Mobil tətbiq (Android APK / iOS)

```bash
cd mobile
flutter build apk --release --dart-define=API_URL=https://<render-api-host>
# nəticə: build/app/outputs/flutter-apk/app-release.apk
```

- **Play Store üçün imza:** `mobile/android/key.properties` faylı yaradın (repozitoriyaya YAZMAYIN, `.gitignore`-dadır):
  `storeFile=<keystore yolu>`, `storePassword=…`, `keyAlias=…`, `keyPassword=…`. Fayl yoxdursa release build debug açarı ilə imzalanır (yalnız test üçün).
- **iOS:** `flutter build ipa --dart-define=API_URL=…` yalnız macOS + Xcode + Apple Developer hesabı ilə mümkündür.
- `API_URL` HTTPS olmalıdır (Render URL-i HTTPS-dir).

## 6. Yerli yoxlama siyahısı (deploydan əvvəl)

```bash
cd backend && mvn verify          # 57 inteqrasiya testi (real PostgreSQL)
cd web && npm ci && npm run lint && npx tsc --noEmit && npm run build
cd mobile && flutter analyze && flutter test
```

CI (`.github/workflows/ci.yml`) eyni yoxlamaları hər push/PR-da çalışdırır.

## 7. Əməliyyat qeydləri

- **Sirlər:** Heç vaxt repozitoriyaya `.env` yazmayın. Render/Vercel dashboard-u sirlərin yeganə yeridir.
- **JWT sirrinin dəyişdirilməsi:** bütün aktiv sessiyalar etibarsız olur, istifadəçilər yenidən daxil olur.
- **Super Admin:** `ADMIN_*` dəyişənləri yalnız ilk yaratma üçündür; sonra onları silə və ya dəyişə bilərsiniz (mövcud admin dəyişmir).
- **Abunəlik müddəti:** hər gün 00:05 (Asia/Baku) bitmiş abunəliklər `EXPIRED` olur. Eyni zamanda hər sorğuda bitmə tarixi yoxlanılır.
- **Real IP:** `server.forward-headers-strategy: native` — Tomcat müştəri IP-sini `X-Forwarded-For`-un sağdan ilk etibarsız (daxili proxy olmayan) dəyərindən götürür; müştəri öz başlığı ilə IP-ni saxtalaşdıra bilmir (rate limit, audit).
- **Ölçü:** `DB_POOL_SIZE` (standart 10). Render Starter planında bir instans işləyir; `RateLimitFilter` yalnız tək instansda düzgün işləyir. Çoxlu instans üçün paylaşılan limit deposu (məs. Redis) lazımdır.
