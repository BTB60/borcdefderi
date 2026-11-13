# HesabPro Cloud ERP — audit və maliyyə arxitekturası

Branch: `feature/hesabpro-cloud-erp` · Baza: `main` (`97c9b4f`) · Tarix: 10.11.2026

## Mərhələ 1 — ERP baxımından audit

Əvvəlki audit: [HESABPRO_2_AUDIT.md](HESABPRO_2_AUDIT.md). ERP modulları üzrə vəziyyət:

| Modul | Status | Qeyd |
|---|---|---|
| Auth (JWT, BCrypt), rollar, store_id izolyasiyası, rate limit, audit log | Tam işləyir | 103 backend testi |
| Super Admin, mağazalar, abunəlik, M10 təsdiqi, paket limitləri (işçi, müştəri) | Tam işləyir | |
| Müştəri borcları, HP kodu, profil kartı, WhatsApp şablonları, kabinet linki | Tam işləyir | HesabPro 2.0 |
| Şirkət borcları / alacaqları (ayrı istiqamətlər) | Tam işləyir | ünvan, VÖEN sahələri yoxdur |
| Dashboard kartları və siyahılar | Qismən işləyir | kassa/bank, gəlir/xərc, anbar, satış göstəriciləri yoxdur (modullar yoxdur) |
| PDF / Excel (borclar, ödənişlər, çek, çıxarış, müştərilər, şirkətlər) | Tam işləyir | |
| Xərclər, təkrarlanan xərclər, büdcə | Mövcud deyil | Mərhələ 2/5 |
| Kassa, bank hesabları, köçürmələr | Mövcud deyil | Mərhələ 2/6 |
| Gəlir / xalis mənfəət | Mövcud deyil | Mərhələ 7 (satış və xərcdən sonra) |
| Anbar, məhsul, satış, qaytarma | Mövcud deyil | Mərhələ 8 |
| İşçi maaşı, avans, bonus, cərimə | Mövcud deyil | Mərhələ 9 (giriş hesabı olan işçilər var, maaş yoxdur) |
| CRM qrupları (yeni, daimi, VIP, borclu, passiv) | Mövcud deyil | Mərhələ 10 |
| Bildiriş mərkəzi (oxunub/oxunmayıb) | Qismən işləyir | bildirişlər var, oxunma statusu yoxdur |
| Fayl saxlama | Qismən işləyir | çeklər və profil şəkilləri PostgreSQL-də (davamlıdır, Render diskində deyil); xarici cloud storage yoxdur |
| Backup | Qismən işləyir | yalnız əl ilə `pg_dump` təlimatı (docs/BACKUP.md); avtomatik backup yoxdur |
| İki mərhələli giriş (2FA) | Mövcud deyil | |
| Dark mode | Mövcud deyil | |
| Deployment | Tam işləyir | Render (API, Docker) + Vercel (web) + PostgreSQL; staging mühiti yoxdur |

## Mərhələ 2 — maliyyə arxitekturası

### Əsas qayda: pul hərəkəti ≠ gəlir

Hər pul hərəkəti **bir kassa/bank hesabında** bir sətirdir (`cash_transactions`, dəyişdirilməz). Sətrin `kind` sahəsi onun
mühasibat mənasını deyir; mənfəət hesabatı yalnız gəlir/xərc növlərini sayır:

| kind | İstiqamət | Mənfəətə təsiri |
|---|---|---|
| `OPENING` (kassa açılışı) | daxil | yoxdur |
| `DEPOSIT` / `WITHDRAWAL` (sahibin pul qoyması / götürməsi) | daxil / çıxış | yoxdur |
| `TRANSFER_IN` / `TRANSFER_OUT` (kassalararası, bank ↔ kassa) | cüt sətir, eyni `transfer_id` | **yoxdur** (gəlir/xərc sayılmır) |
| `DEBT_COLLECTION` (müştəri / şirkət köhnə borcu ödədi) | daxil | **gəlir deyil** — debitor borcun azalması |
| `SUPPLIER_PAYMENT` (biz şirkətə borcumuzu ödədik) | çıxış | **xərc deyil** — kreditor borcun azalması |
| `EXPENSE` / `EXPENSE_REVERSAL` | çıxış / daxil | xərc (redaktə/silmədə əks sətir yazılır) |
| `SALE` (Mərhələ 8) | daxil | satış gəliri |
| `COUNT_ADJUSTMENT` (inventarizasiya fərqi) | ± | yoxdur (ayrıca göstərilir) |

- Balans = daxilolmalar − çıxışlar; heç vaxt saxlanmır, həmişə sətirlərdən hesablanır.
- Kreditlə satış (Mərhələ 8): satış gəliri satış anında; pul isə ödəniş gələndə `DEBT_COLLECTION` kimi daxil olur — iki dəfə sayılmır.
- Bütün pul əməliyyatları bir DB tranzaksiyasında; hesab sətri `FOR UPDATE` ilə kilidlənir, mənfi kassa qadağandır.
- Təkrar göndərmə (`clientRequestId`) eyni əməliyyatı ikinci dəfə yazmır.

### Ödənişlər → kassa
Borc ödənişi qeyd ediləndə avtomatik pul sətri yaranır: nağd → mağazanın əsas nağd kassası, kart/köçürmə/digər → əsas bank
hesabı (istəyə görə başqa hesab seçilə bilər). Hesablar ilk lazım olanda avtomatik yaradılır ("Əsas kassa", "Bank hesabı").
**Köhnə ödənişlər geriyə kassaya yazılmır** — kassanın real qalığı başlanğıcda "Kassa açılışı" ilə daxil edilir.

### Xərclər
- 15 standart kateqoriya (İcarə, İşçi maaşı, Elektrik, Su, Qaz, İnternet, Telefon, Yanacaq, Nəqliyyat, Reklam, Ofis xərcləri, Təmir,
  Avadanlıq, Vergilər, Digər) + mağazanın öz kateqoriyaları.
- Xərc ödəndiyi hesabdan `EXPENSE` sətri yazır. Redaktə: köhnə sətir `EXPENSE_REVERSAL` ilə geri qaytarılır, yenisi yazılır.
  Silmə: yumşaq silmə + əks sətir. Tarixçə audit log-dadır.
- Xərclər borclara və ödənişlərə qarışmır (ayrı cədvəl).

### Təkrarlanan xərclər
Plan (öhdəlik) kimi saxlanır: məbləğ, kateqoriya, tezlik (gündəlik/həftəlik/aylıq/illik), başlama/bitmə, növbəti tarix, aktivlik.
"Ödə" düyməsi faktiki xərc yaradır və növbəti tarixi irəli çəkir. `(recurring_id, period_date)` unikaldır → eyni dövr iki dəfə yaranmır.

### Büdcə
`(mağaza, kateqoriya, ay)` üzrə limit. Status: faktiki / limit, 80%-dən sonra "yaxınlaşır", 100%-dən sonra "keçilib".

### Yeni icazələr
`CASH` (kassa), `EXPENSES` (xərclər) — sahib hamısına malikdir; işçiyə ayrıca verilir.

## Sizin qərarınızı / ödənişli xidməti tələb edənlər

| Mövzu | Niyə | Təklif |
|---|---|---|
| Xarici cloud storage (fayllar) | İndi fayllar PostgreSQL-də saxlanır (davamlı, mağazaya görə ayrılır). Fayl çox olduqca baza böyüyür | Cloudflare R2 və ya AWS S3 — hesab və açar sizdən lazımdır |
| Avtomatik backup | Gündəlik/həftəlik backup üçün ayrıca yaddaş və planlaşdırıcı lazımdır | Neon/Render PostgreSQL-in öz backup imkanı (planınızdan asılıdır) + R2/S3-ə gündəlik `pg_dump` |
| WhatsApp avtomatik göndərmə | Rəsmi WhatsApp Business API ödənişlidir | Hazırda yalnız hazır mətnlə açılır |
| SMS ilə 2FA | SMS provayderi ödənişlidir | Pulsuz alternativ: Google Authenticator (TOTP) |
| Staging mühiti | Ayrı Render servisi + ayrı baza | Render-də ikinci (pulsuz) servis |

## Mərhələ 2 — hesabat (görülən iş)

### Database (V10, yalnız əlavə)
`cash_accounts`, `cash_transactions` (dəyişdirilməz, trigger), `expense_categories` (15 standart), `expenses`,
`expense_attachments`, `recurring_expenses`, `budgets`; `member_permissions`-a `CASH`, `EXPENSES`. Geri qaytarma: `db/rollback/V10__undo.sql`.

### API
| Metod | Yol | Nə edir |
|---|---|---|
| GET/POST/PUT | `/api/cash/accounts` | kassa / bank / kart hesabları (yaratmaq və dəyişmək yalnız sahib) |
| GET | `/api/cash/movements` | hərəkətlər (hesab, tarix, növ filtri) |
| POST | `/api/cash/opening` · `/deposit` · `/withdraw` · `/transfer` · `/count` | açılış, mədaxil, məxaric, köçürmə, sayım |
| GET | `/api/cash/day-report?date=` | gün sonu: açılış, daxil, çıxış (növ üzrə), bağlanış |
| GET/POST | `/api/expense-categories` | kateqoriyalar |
| GET/POST/PUT/DELETE | `/api/expenses` | xərclər (redaktə/silmə kassada əks sətirlə) |
| GET | `/api/expenses/summary?groupBy=day|month|year|category` | xərc analizi |
| POST/GET | `/api/expenses/{id}/attachments` | qəbz şəkli / sənəd (PNG, JPG, PDF, ≤5 MB, məzmun yoxlanır) |
| GET/POST/PUT | `/api/recurring-expenses`, POST `/{id}/pay` | təkrarlanan xərc, dövrü ödə |
| GET/PUT/DELETE | `/api/budgets` | aylıq büdcə və status (OK / NEAR / OVER) |

Mövcud borc ödənişləri indi avtomatik kassaya düşür (`DEBT_COLLECTION` / `SUPPLIER_PAYMENT`); API dəyişmədi, yalnız istəyə bağlı `cashAccountId` əlavə olundu.

### Testlər
Backend **110/110** (yeni `FinanceCoreTest` 7 ssenari): borc ödənişinin kassaya düşməsi və mənfəətə sayılmaması; açılış,
mədaxil, məxaric, kifayət etməyən qalıq; köçürmə (ikiqat basma bir dəfə yazılır, cəm balansı dəyişmir); sayım fərqi; gün
sonu; xərc yaratma / redaktə / silmə (əks sətirlər, tarixçə qalır); xülasə və fayl yoxlaması; təkrarlanan xərc (eyni dövr
ikinci dəfə ödənmir); büdcə NEAR / OVER; icazələr və mağaza izolyasiyası.

### Qalan (növbəti mərhələlər)
- Kassa, xərclər, büdcə üçün **web və mobil ekranlar** (Mərhələ 3, 5, 6).
- Köhnə ödənişlər kassaya geriyə yazılmayıb (qəsdən): sahib "Kassa açılışı" ilə real qalığı daxil etməlidir.
- Avtomatik ödəniş kassanı mənfiyə sala bilər (açılış daxil edilməyibsə); əl ilə məxaric və köçürmə sala bilməz.

## Mərhələ 3, 5, 6 (birinci hissə) — Kassa, Xərclər ekranları və dashboard pul kartları

### Görülən iş
- **API:** `GET /api/cash/overview` — nağd / bank / kart balansı, bu gün və bu ay daxil olan / çıxan pul (köçürmə, açılış,
  sayım daxil deyil), bu günün / ayın / keçən ayın xərci, büdcə xəbərdarlıqları, ödənilməli təkrarlanan xərclər.
  İcazəsi olmayan istifadəçiyə bu rəqəmlər qaytarılmır.
- **Web `/kassa`:** balans kartları, hesablar (yarat, redaktə, əsas et, deaktiv), açılış, mədaxil, məxaric, köçürmə,
  sayım, hərəkətlər (hesab / növ / tarix filtri), gün sonu hesabatı.
- **Web `/xercler`:** xərc siyahısı (axtarış, kateqoriya, tarix, səhifələmə, CSV/Excel), əlavə / redaktə / sil (təsdiqlə),
  qəbz faylı, yeni kateqoriya; təkrarlanan xərclər (yarat, redaktə, ödə); büdcə (limit, irəliləyiş, xəbərdarlıq);
  analiz (kateqoriya, ay, gün üzrə).
- **Web dashboard:** kassa balansı, bank və kart balansı, bu gün / bu ay pul hərəkəti, bu günün və ayın xərci (keçən ayla
  müqayisə), büdcə və təkrarlanan xərc xəbərdarlıqları — hər kart müvafiq səhifəni açır. Menyuya “Kassa və bank”, “Xərclər”.
- **Mobil:** “Daha” → “Kassa və bank” (balanslar, hesablar, hərəkətlər, mədaxil / məxaric / köçürmə / sayım / açılış,
  gün sonu), “Xərclər” (ay seçimi, xərc əlavə / redaktə / sil, qəbz şəkli kamera və ya qalereyadan, təkrarlanan xərci ödə,
  büdcə limiti və irəliləyişi).

### Testlər
- Backend **111/111** (FinanceCoreTest-ə dashboard icmalı testi əlavə olundu).
- Web: tsc, lint, production build — xətasız. Flutter: analyze 0, test 29/29, release APK yığılır.
- Yerli serverdə real ssenari: açılış 500 + müştəri ödənişi 50 − elektrik xərci 35 → kassa 515; bugünkü daxilolma 50
  (açılış sayılmır); büdcə 35/40 → “yaxınlaşır”; vaxtı keçmiş internet → “ödənilməli”. Brauzerdə kassa və xərclər səhifəsi.

### Qalan
- Mobil dashboard-da pul kartları hələ yoxdur (kassa və xərclər ayrıca ekranlardadır).
- Xərclərin PDF/Excel serverdə hazırlanan hesabatı (indi CSV brauzerdə) — Mərhələ 11.
- Bu branch hələ production-a birləşdirilməyib; APK Desktop-a qoyulmayıb (canlı server bu API-ləri tanımır).

## Mərhələ 7–8 — Anbar, satış, gəlir və mənfəət

### Database (V11, əlavə)
`product_categories`, `products` (kod P-000001, barkod, alış/satış qiyməti, qalıq, minimum stok, şəkil), `inventory_movements`
(dəyişdirilməz), `sales` (S-000001, nağd/kart/borc hissələri, maya dəyəri), `sale_items`, `sale_returns`,
`cash_transactions.sale_id`; icazələr `INVENTORY`, `SALES`. `debts.amount >= 0` (tam qaytarılan borclu satış üçün).

### Mühasibat qaydası
- Satış gəliri satış günü bir dəfə sayılır (kreditlə də). Ödənilən hissə kassaya `SALE`, ödənilməyən hissə müştəri hesabına borc.
- Sonradan borcun ödənməsi `DEBT_COLLECTION` — gəlir deyil.
- Qaytarma: mal anbara qayıdır; əvvəlcə satışın ödənilməmiş borcu azalır, qalanı kassadan `SALE_REFUND`.
- Maya dəyəri = satış anındakı alış qiyməti × miqdar (anbara girişdə orta qiymət yenilənir).
- Xalis nəticə = xalis satış − maya dəyəri − xərclər. Kassa balansı mənfəət kimi göstərilmir.

### API
`/api/products` (CRUD, `?q=&stock=LOW|OUT`, `/summary`, `/by-code/{barkod}`, `/{id}/image`), `/api/product-categories`,
`/api/inventory/movements` (RECEIPT, ISSUE, ADJUSTMENT; COUNT yalnız köhnə tətbiqlər üçün), `/api/stock-counts` (anbar sayımı: başla, sətir say, tamamla → COUNT hərəkətləri, sil, `/{id}/export?format=xlsx|pdf` sayım hesabatı), `/api/sales` (yarat, siyahı, detal), `/api/sales/{id}/returns`,
`/api/sales/{id}/cancel`, `/api/sales/{id}/receipt` (PDF çek), `/api/finance/pnl`, `/api/finance/monthly`.

### UI
- Web: **Anbar**, **Satış** (barkod/axtarış, səbət, endirim, nağd/kart/borc, çek, siyahı, qaytarma, ləğv), **Gəlir və mənfəət**
  (P&L, pul axını ayrıca, əvvəlki dövrlə müqayisə, 12 aylıq dinamika); dashboard-a bu ayın satışı, xalis nəticə, anbar məhsulları və dəyəri.
- Mobil: “Daha” → **Satış** (yeni satış, çeki paylaş, ləğv), **Anbar** (məhsul, giriş/çıxış/sayım, az qalanlar).

### Testlər
Backend **117/117** (`InventorySalesTest` 6). Yerli real ssenari: nağd satış 16,30; borcla satış 35 (15 kart + 20 borc);
2 ədəd qaytarma → borc 7 azaldı, pul qaytarılmadı; P&L: xalis satış 44,30, maya 30,00, ümumi mənfəət 14,30, xalis −20,70
(35 xərclə) — əl ilə hesabla eynidir; anbar dəyəri 49,20; az qalan xəbərdarlığı. Brauzerdə barkodla səbətə əlavə.

### Qalan
- Mobil: qismən qaytarma (veb-də var), məhsul şəkli və barkod skaneri kamerası yoxdur.
- Satış/anbar Excel hesabatı serverdə hazırlanmır (Mərhələ 11).
- Maaş (Mərhələ 9), CRM qrupları və bildiriş mərkəzi (10), avtomatik backup, 2FA, dark mode hələ edilməyib.
