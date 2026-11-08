# Deployment

Bu sənəd hazır konfiqurasiyaları izah edir. **Heç bir yerləşdirmə icra edilməyib.** Aşağıdakı addımları öz Neon, Render və Vercel hesablarınızla icra etməlisiniz.

## 1. Neon (PostgreSQL)

1. Neon-da layihə və verilənlər bazası yaradın (məs. `borcdefteri`).
2. Bağlantı sətrini götürün və **JDBC formatında** yazın:

   ```
   jdbc:postgresql://<neon-host>/borcdefteri?sslmode=require
   ```

3. İstifadəçi adı və şifrəni ayrıca `DATABASE_USERNAME` / `DATABASE_PASSWORD` kimi verin (URL-də saxlamayın).
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
   | `JWT_SECRET` | Ən azı 32 simvol. Məs: `openssl rand -base64 48` |
   | `ADMIN_EMAIL` / `ADMIN_PASSWORD` | İlk Super Admin (şifrə ≥12 simvol). Hesab yalnız ilk işə salınmada yaradılır |
   | `CORS_ALLOWED_ORIGINS` | Vercel URL-i, məs. `https://borcdefteri.vercel.app` (vergüllə bir neçə dəyər ola bilər) |

3. Health check: `/api/health` (`render.yaml`-da təyin olunub).
4. **Qəbz faylları:** `UPLOAD_DIR` standart olaraq `/app/uploads`-dır. Render-in konteyner diski yenidən deploy zamanı silinir. Ya **Persistent Disk** əlavə edib `UPLOAD_DIR`-i ona yönəldin (plan tələb edir), ya da qəbzləri obyekt yaddaşına köçürmək üçün kod əlavə edin. Bu açıq işdir.
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
   | `NEXT_PUBLIC_PAYMENT_INFO` | (istəyə bağlı) Abunəlik ödənişi üçün rekvizit mətni |

   `NEXT_PUBLIC_*` dəyişənləri build zamanı daxil edilir — dəyişəndən sonra yenidən deploy edin.
4. Vercel domeninizi Render-də `CORS_ALLOWED_ORIGINS`-ə əlavə edin.

## 4. Yerli yoxlama siyahısı (deploydan əvvəl)

```bash
cd backend && mvn verify          # 41 inteqrasiya testi
cd web && npm ci && npm run lint && npm run build && npx tsc --noEmit
```

CI (`.github/workflows/ci.yml`) eyni yoxlamaları hər push/PR-da çalışdırır.

## 5. Əməliyyat qeydləri

- **Sirlər:** Heç vaxt repozitoriyaya `.env` yazmayın. Render/Vercel dashboard-u sirlərin yeganə yeridir.
- **JWT sirrinin dəyişdirilməsi:** bütün aktiv sessiyalar etibarsız olur, istifadəçilər yenidən daxil olur.
- **Super Admin:** `ADMIN_*` dəyişənləri yalnız ilk yaratma üçündür; sonra onları silə və ya dəyişə bilərsiniz (mövcud admin dəyişmir).
- **Abunəlik müddəti:** hər gün 00:05 (Asia/Baku) bitmiş abunəliklər `EXPIRED` olur. Eyni zamanda hər sorğuda bitmə tarixi yoxlanılır.
- **Ölçü:** `DB_POOL_SIZE` (standart 10). Render Starter planında bir instans işləyir; `RateLimitFilter` yalnız tək instansda düzgün işləyir. Çoxlu instans üçün paylaşılan limit deposu (məs. Redis) lazımdır.
