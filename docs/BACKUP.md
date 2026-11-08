# Verilənlər bazası ehtiyat nüsxəsi və bərpa

Maliyyə məlumatı üçün iki səviyyəli plan tövsiyə olunur: **provayder səviyyəsində** (Neon point-in-time restore) və **əl ilə/planlı `pg_dump`** (köçürülə bilən arxiv).

## 1. Provayder səviyyəsi (Neon)

- Neon-un tarixçə saxlama (history retention) müddətini layihənin planına uyğun təyin edin.
- Vacib: Neon branch-larından (məs. `restore-test`) istifadə edərək ayrıca bərpa yoxlaması aparın — bu, real bərpanın işlədiyini sübut edən yeganə yoldur.

## 2. Planlı `pg_dump` (gündəlik)

PostgreSQL 14+ client alətləri tələb olunur. Bağlantı sətrini **mühit dəyişəni**ndə saxlayın, skriptə yazmayın:

```bash
export PGURL="postgresql://<user>:<password>@<neon-host>/borcdefteri?sslmode=require"
STAMP=$(date +%Y%m%d-%H%M)
pg_dump "$PGURL" --format=custom --no-owner --no-privileges \
  --file="borcdefteri-$STAMP.dump"
```

- `custom` formatı sıxılmış və `pg_restore` ilə seçmə bərpaya imkan verir.
- Arxivi şifrələyin (məs. `gpg --symmetric`) və ayrıca yerdə saxlayın (S3 uyğun bucket, başqa region).
- Saxlama siyasəti: gündəlik 14 gün, aylıq 12 ay (məsləhət).

Məsələn, cron (və ya GitHub Actions schedule ilə eyni əmr, sirlər GitHub Secrets-də):

```cron
15 2 * * *  /opt/borcdefteri/backup.sh >> /var/log/borcdefteri-backup.log 2>&1
```

## 3. Bərpa

```bash
# Boş və ya test verilənlər bazasına bərpa edin (istehsalata birbaşa YOX):
pg_restore --no-owner --no-privileges --dbname="$TEST_PGURL" borcdefteri-20260101-0200.dump
```

Bərpadan sonra yoxlayın:

```sql
SELECT COUNT(*) FROM users;
SELECT COUNT(*) FROM financial_transactions;
SELECT SUM(remaining_amount) FROM debts;   -- əvvəlki hesabatla müqayisə
SELECT MAX(created_at) FROM audit_logs;
```

Və Flyway vəziyyətini: `SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;`

## 4. Qəbz faylları

Qəbzlər verilənlər bazasında deyil, `UPLOAD_DIR`-dədir. Onları da ehtiyat nüsxəyə daxil edin:

- Persistent disk: diskin snapshot-u və ya `rsync` ilə gündəlik köçürmə.
- Obyekt yaddaşı tətbiq edildikdə: bucket versioning və lifecycle qaydası.

Verilənlər bazası ilə qəbz faylları arasında uyğunluq üçün `subscription_receipts.storage_name` sütununu fayl adları ilə müqayisə edin.

## 5. Bərpa testi (rüblük)

1. Son dump-u boş test bazasına bərpa edin.
2. Backend-i həmin bazaya `DATABASE_URL` ilə yönəldib `/api/health` yoxlayın.
3. Bir mağaza ilə giriş edib panel rəqəmlərini əvvəlki hesabatla müqayisə edin.
4. Nəticəni tarixləyib saxlayın.

## 6. Qeyd: audit və maliyyə cədvəlləri

`payments`, `financial_transactions` və `audit_logs` cədvəllərində UPDATE/DELETE DB trigger-ləri ilə bloklanır. Bərpa zamanı `pg_restore` yalnız INSERT edir, buna görə bu cədvəllər dəyişmədən bərpa olunur. Trigger-lər bərpa olunmuş sxemdə də qüvvədədir.
