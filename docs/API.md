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
| GET | `/api/public/plans` | Aktiv paketlər (admin panelində yaradılır): `name, description, features[], options[{period: MONTHLY/QUARTERLY/SEMI_ANNUAL/YEARLY/CUSTOM, months, days, price}], maxEmployees, maxCustomers, recommended, primaryColor, secondaryColor, backgroundColor, textColor, buttonColor`. Köhnə `price`/`durationMonths` sahələri qalır (ilk müddət) |
| GET | `/api/public/contact` | Ödəniş və əlaqə parametrləri: `phone` (WhatsApp), `whatsapp` (`994…` — `wa.me` üçün), `m10Account, m10Recipient, paymentNote`, `cardNumber, cardHolder, cardBank, cardNote` (**kart yalnız daxil olmuş istifadəçiyə**), `extraPhone, extraContact, whatsappTemplate, paymentInstructions, afterPaymentText` |
| GET | `/api/subscriptions/current` | `{active, status, planCode, planName, startDate, endDate, daysRemaining, renewalReminder, expiresAt}` — `expiresAt`: girişin bitdiyi dəqiq an (bitmə tarixindən sonrakı günün 00:00, Bakı vaxtı), sayğac üçün |
| GET | `/api/subscriptions/history` | “Ödəniş sorğularım”: hər sorğuda `requestStatus` (`PENDING` Yoxlanılır / `APPROVED` Təsdiqlənib / `REJECTED` Rədd edilib), `periodCode, periodMonths, periodDays, paymentMethod, payerName, declaredAmount, paymentDate, note` |
| POST | `/api/subscriptions/request` | “Ödəniş etdim” (faylsız): `{planCode, period?, paymentMethod? (M10/CARD), payerName?, paidAmount?, paymentDate?, note?, payerReference?}`. `PENDING` sorğu yaradır və ya gözləyəni yeniləyir. Yalnız sahib |
| POST | `/api/subscriptions` | Eyni, `multipart/form-data` + `receipt` (PNG/JPG/PDF ≤5 MB, məzmunu yoxlanılır, bazada saxlanılır). **Məbləğ paketin qiymətindən serverdə hesablanır**; `paidAmount` yalnız adminə göstərilir |
| GET | `/api/subscriptions/{id}/receipt` | Qəbz faylı (yalnız öz mağazası) |

Abunəlik həyat dövrü: `PENDING` → admin təsdiqi → `ACTIVE` (dövr: bu gün və ya cari bitmə tarixinin ertəsi — qalan aktiv müddət itmir; standart müddətlər təqvim ayı, xüsusi müddət gün ilə; təsdiq zamanı dayandırılmış mağaza aktivləşir və mağazaya bildiriş gedir) · rədd → `REJECTED` · bitmə tarixindən sonra `EXPIRED` (hər gün 00:05 Asia/Baku-da və həm də hər sorğuda yoxlanılır).

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
| POST | `/api/admin/stores/{id}/subscription` | `{planCode, period?}` — çek yoxlanıldıqdan sonra mağazanı birbaşa aktiv edir (gözləyən sorğu varsa ondan istifadə olunur). Aktiv abunəlik varsa yeni dövr cari bitmədən sonra başlayır |
| PUT | `/api/admin/settings/contact` | Bütün ödəniş və əlaqə parametrləri (yuxarıdakı `contact` sahələri). Boş dəyər silinir |
| POST | `/api/admin/stores/{id}/extend` | `{days: 1–366}` — aktiv abunəliyi uzadır |
| GET | `/api/admin/subscriptions?status=&storeId=` | Ödəniş sorğuları / tarixçə: mağaza, sahibin adı/e-poçtu/telefonu, paket, müddət, məbləğ, bildirilən məbləğ, üsul, tarix, çek, baxan admin |
| GET | `/api/admin/subscriptions/{id}/receipt` | Qəbz |
| POST | `/api/admin/subscriptions/{id}/approve` | `PENDING` → `ACTIVE` bir tranzaksiyada (sətir kilidlənir; ikinci təsdiq `409`) |
| POST | `/api/admin/subscriptions/{id}/reject` | `{reason}` |
| GET | `/api/admin/plans?archived=` | Paketlər (arxiv daxil olmaqla) |
| POST | `/api/admin/plans` · PUT `/api/admin/plans/{id}` | `{name, description, features[], priceMonthly, priceQuarterly, priceSemiAnnual, priceYearly, customDays, customPrice, maxEmployees, maxCustomers, active, recommended, primaryColor…buttonColor (#RRGGBB)}`. Köhnə forma `{name, price, durationMonths, active}` da qəbul olunur |
| DELETE | `/api/admin/plans/{id}` | İstifadə olunmayıbsa silir (`DELETED`), istifadə olunubsa arxivləşdirir (`ARCHIVED`) |
| POST | `/api/admin/plans/{id}/restore` · PUT `/api/admin/plans/order` | Arxivdən bərpa · sıralama `{ids: [...]}` |
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

## Parametrlər (profil) — bütün rollar

| Metod | Yol | Qeyd |
|---|---|---|
| GET | `/api/profile` | Öz profili (mağaza sahibi üçün mağaza məlumatı da) |
| PUT | `/api/profile` | `{fullName, phone?, email?, currentPassword?}` — e-poçt dəyişirsə cari şifrə tələb olunur |
| PUT | `/api/profile/store` | `{name, phone?, address?}` — yalnız mağaza sahibi |
| PUT | `/api/profile/password` | `{currentPassword, newPassword}` (≥8, admin ≥12) → yeni `{token, …}`. Əvvəl verilmiş tokenlər etibarsız olur (digər cihazlarda çıxış) |
| POST | `/api/profile/avatar` | `multipart file` — PNG/JPG ≤3 MB; 256×256 PNG-yə çevrilir (EXIF silinir) |
| GET / DELETE | `/api/profile/avatar` | Öz profil şəkli (başqasının şəkli verilmir) |

Abunəlik tələb olunmur. Şifrə yoxlanan sorğular (`PUT /api/profile`, `PUT /api/profile/password`) giriş kimi rate limit-ə tabedir. `/api/auth/me` cavabında `phone`, `hasAvatar`, `avatarVersion` var.

## Şifrəni unutdum

| Metod | Yol | Qeyd |
|---|---|---|
| POST | `/api/auth/forgot-password` | Açıq. `{email}` → həmişə eyni cavab `{emailEnabled}` (hesabın olub-olmadığı bilinmir). SMTP varsa 30 dəqiqəlik birdəfəlik link göndərilir; hər halda admin üçün sorğu yaranır |
| POST | `/api/auth/reset-password` | Açıq. `{token, newPassword}` — e-poçt linki ilə. Bazada yalnız tokenin SHA-256-sı saxlanılır; istifadə olunmuş/vaxtı keçmiş → `400 INVALID_RESET_LINK` |
| GET | `/api/admin/password-requests` | Açıq "şifrəni unutdum" sorğuları |
| POST | `/api/admin/password-requests/{id}/dismiss` | Sorğunu bağla |
| POST | `/api/admin/users/{id}/reset-password` | Müvəqqəti şifrə `{temporaryPassword, email, fullName, phone}` (yalnız bir dəfə göstərilir). Platforma adminlərinə tətbiq olunmur |
| POST | `/api/employees/{memberId}/reset-password` | Mağaza sahibi öz işçisinə müvəqqəti şifrə verir |

Müvəqqəti şifrə ilə girişdən sonra `me.mustChangePassword = true`; `PUT /api/profile/password` edilənə qədər biznes endpointləri `403 PASSWORD_CHANGE_REQUIRED` qaytarır. İstənilən şifrə sıfırlaması köhnə tokenləri etibarsız edir.
