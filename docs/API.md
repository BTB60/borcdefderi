# BORCDEFTERI REST API

Baza URL-i: `https://<api-host>` (yerli: `http://localhost:8080`). Bütün cavablar JSON-dur (qəbz və hesabat faylları istisna).

**Autentifikasiya:** `Authorization: Bearer <token>` başlığı. Token `POST /api/auth/login` və ya `register` cavabındadır.

**Tenant qaydası:** Mağaza ID-si heç vaxt sorğuya yazılmır. Server cari istifadəçinin üzvlüyündən mağazanı təyin edir. Başqa mağazanın qeydi `404` qaytarır.

**Xəta formatı:**

```json
{ "error": "PAYMENT_EXCEEDS_BALANCE", "message": "Ödəniş qalıq borcdan (100.00 AZN) böyük ola bilməz" }
```

Doğrulama xətalarında əlavə olaraq `"fields": { "amount": "..." }` gəlir.

Ümumi status kodları: `400` yanlış məlumat, `401` giriş tələb olunur, `402` aktiv abunəlik yoxdur (`SUBSCRIPTION_REQUIRED`), `403` icazə / dayandırılmış mağaza, `404` tapılmadı, `409` ziddiyyət, `413` fayl böyükdür, `429` limit aşılıb.

**Pul məbləğləri:** JSON-da ədəd kimi göndərilir, server `BigDecimal` (2 onluq) ilə işləyir. Göndərmə zamanı sətir (`"150.00"`) da qəbul olunur.

## Səhiyyə

| Metod | Yol | Qeyd |
|---|---|---|
| GET | `/api/health` | `{status, database, time}`. DB əlçatmazdırsa 503 |

## Autentifikasiya (açıq)

| Metod | Yol | Gövdə | Cavab |
|---|---|---|---|
| POST | `/api/auth/register` | `email, password (≥8), fullName, phone?, storeName, storePhone?, storeAddress?` | `201 {token, expiresInSeconds, me}` |
| POST | `/api/auth/login` | `email, password` | `{token, expiresInSeconds, me}` |
| GET | `/api/auth/me` | — | `me` (rol, icazələr, mağaza, abunəlik xülasəsi) |

Qeydiyyat yalnız mağaza sahibi yaradır; mağaza abunəlik təsdiqlənənə qədər biznes modullarına girə bilmir.

## Abunəlik (mağaza tərəfi)

| Metod | Yol | Qeyd |
|---|---|---|
| GET | `/api/public/plans` | Satışda olan paketlər: `MONTHLY` (Aylıq), `SEMI_ANNUAL` (6 aylıq), `YEARLY` (İllik). Qiymətləri admin dəyişir |
| GET | `/api/public/contact` | `{phone, whatsapp}` — “Abunə ol” basılanda göstərilən admin nömrəsi (`whatsapp` yalnız rəqəmlər, `wa.me` üçün) |
| GET | `/api/subscriptions/current` | `{active, status, planCode, planName, startDate, endDate, daysRemaining, renewalReminder, expiresAt}` — `expiresAt`: girişin bitdiyi dəqiq an (bitmə tarixindən sonrakı günün 00:00, Bakı vaxtı), sayğac üçün |
| GET | `/api/subscriptions/history` | Sorğu tarixçəsi |
| POST | `/api/subscriptions/request` | `{planCode}` — “Abunə ol”: qəbzsiz `PENDING` sorğu yaradır; gözləyən sorğu varsa onun paketini dəyişir. Yalnız sahib. Çek admininə WhatsApp ilə göndərilir |
| POST | `/api/subscriptions` | (istəyə bağlı) `multipart/form-data`: `planCode`, `payerReference?`, `receipt` (PNG/JPG/PDF ≤5 MB). Məbləğ plandan götürülür, client-dən qəbul olunmur. Yalnız sahib. Gözləyən sorğu varsa `409` |
| GET | `/api/subscriptions/{id}/receipt` | Qəbz faylı (yalnız öz mağazası) |

Abunəlik həyat dövrü: `PENDING` → admin təsdiqi → `ACTIVE` (dövr: bu gün və ya cari bitmə tarixinin ertəsi, plan müddəti qədər) · rədd → `REJECTED` · bitmə tarixindən sonra `EXPIRED` (hər gün 00:05 Asia/Baku-da və həm də hər sorğuda yoxlanılır).

## Müştərilər və şirkətlər

| Metod | Yol | İcazə |
|---|---|---|
| GET | `/api/customers?q=` | `CUSTOMER_DEBTS` |
| POST | `/api/customers` | `CUSTOMER_DEBTS` |
| PUT | `/api/customers/{id}` | `CUSTOMER_DEBTS` |
| GET | `/api/companies?q=` | `COMPANY_RECEIVABLES` və ya `COMPANY_PAYABLES` |
| POST / PUT | `/api/companies[/{id}]` | yuxarıdakı kimi |

Gövdə: `{ "name", "phone?", "note?" }`.

## Borclar

`type` dəyərləri: `CUSTOMER_DEBT`, `COMPANY_RECEIVABLE`, `COMPANY_PAYABLE`.

| Metod | Yol | Qeyd |
|---|---|---|
| GET | `/api/debts?type=&status=&q=&from=&to=&page=&size=` | `status`: `OPEN`, `PARTIAL`, `PAID`, `UNPAID`. Cavab: `{items, total, page, size, totals:{amount,paid,remaining}}` |
| GET | `/api/debts/{id}` | Tək borc |
| GET | `/api/debts/{id}/history` | Maliyyə jurnalı (yaradılma, dəyişiklik, ödənişlər, `balanceAfter`) |
| POST | `/api/debts` | `{debtType, partyId, amount, debtDate, dueDate?, description?, note?}` |
| PUT | `/api/debts/{id}` | `{amount, debtDate, dueDate?, description?, note?, version}`. `amount` ödənilmiş məbləğdən az ola bilməz; `version` uyğun gəlməsə `409 VERSION_CONFLICT` |

## Ödənişlər

| Metod | Yol | Qeyd |
|---|---|---|
| POST | `/api/debts/{debtId}/payments` | `{amount, paymentMethod: CASH|CARD, paidAt?, description?, note?}`. Bir DB tranzaksiyasında borc sətri kilidlənir. Qalıqdan çox → `409 PAYMENT_EXCEEDS_BALANCE`; tam ödənilmiş → `409 DEBT_ALREADY_PAID` |
| GET | `/api/payments?from=&to=&method=&debtId=&limit=` | İcazə verilən bölmələr üzrə ödənişlər |

## Panel və bildirişlər

| Metod | Yol | İcazə |
|---|---|---|
| GET | `/api/dashboard` | `REPORTS`. Cəmi borclar, alacaq/borc, nağd/kart, bu gün/bu ay, son 14 gün, son 6 ay, son əməliyyatlar, açıq balanslar |
| GET | `/api/notifications` | Abunəlik yenilənməsi, yaxınlaşan/vaxtı keçmiş borclar |

## Hesabatlar

| Metod | Yol | Qeyd |
|---|---|---|
| GET | `/api/reports/debts/export?type=&format=xlsx|pdf&status=&from=&to=` | Fayl yükləməsi (`Content-Disposition: attachment`) |

## İşçilər (yalnız mağaza sahibi)

| Metod | Yol | Qeyd |
|---|---|---|
| GET | `/api/employees` | Siyahı |
| POST | `/api/employees` | `{email, password (≥8), fullName, phone?, permissions: [...]}` |
| PUT | `/api/employees/{memberId}` | `{permissions: [...], active}` |

İcazələr: `CUSTOMER_DEBTS`, `COMPANY_RECEIVABLES`, `COMPANY_PAYABLES`, `REPORTS`. Sahib bütün icazələrə malikdir.

## Platforma administratoru (`/api/admin/**`, yalnız SUPER_ADMIN)

| Metod | Yol | Qeyd |
|---|---|---|
| GET | `/api/admin/stores?q=` | Mağazalar, sahib, aktiv plan, sayğac: `subscriptionState` (ACTIVE/EXPIRED/NONE), `expiresAt`, `daysRemaining`, `expiring` (bitib və ya ≤7 gün qalıb → admin paneldə qırmızı), gözləyən sorğu (`pendingPlanCode/Name`) |
| POST | `/api/admin/stores/{id}/suspend` · `/activate` | Dayandırma dərhal təsir edir |
| POST | `/api/admin/stores/{id}/subscription` | `{planCode}` — çek yoxlanıldıqdan sonra mağazanı birbaşa aktiv edir (gözləyən sorğu varsa ondan istifadə olunur). Aktiv abunəlik varsa yeni dövr cari bitmədən sonra başlayır |
| PUT | `/api/admin/settings/contact` | `{phone}` — “Abunə ol” üçün əlaqə nömrəsi |
| POST | `/api/admin/stores/{id}/extend` | `{days: 1–366}` — aktiv abunəliyi uzadır |
| GET | `/api/admin/subscriptions?status=PENDING` | Sorğular |
| GET | `/api/admin/subscriptions/{id}/receipt` | Qəbz |
| POST | `/api/admin/subscriptions/{id}/approve` | `PENDING` → `ACTIVE` |
| POST | `/api/admin/subscriptions/{id}/reject` | `{reason}` |
| GET | `/api/admin/plans` · PUT `/api/admin/plans/{id}` | `{name, price, durationMonths, active}` |
| GET | `/api/admin/revenue` | Ümumi/aylıq/illik gəlir, aktiv mağaza sayı, plan üzrə |
| GET | `/api/admin/users?q=` | İstifadəçilər |
| GET | `/api/admin/audit-logs?storeId=&limit=` | Audit jurnalı (yalnız əlavə etmə) |

## Ödəniş çeki və WhatsApp

| Metod | Yol | Qeyd |
|---|---|---|
| GET | `/api/payments/{id}/receipt` | Çek PDF (giriş tələb olunur, mağazaya bağlı) |
| GET | `/api/payments/{id}/share-link` | `{url}` — müştəriyə göndəriləcək çek linki (mağazaya bağlı) |
| GET | `/api/public/receipts/{id}/{token}` | **Girişsiz** — çek PDF brauzerdə açılır. `token` = HMAC-SHA256 (JWT_SECRET-dən törədilmiş açar); yanlış/saxta token → `404`. `JWT_SECRET` dəyişsə köhnə linklər etibarsız olur |

Ödəniş cavablarında (`POST /api/debts/{id}/payments`, `GET /api/payments`) `receiptUrl` sahəsi də qaytarılır.
