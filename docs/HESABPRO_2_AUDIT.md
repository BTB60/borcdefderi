# HesabPro 2.0 — audit və iş hesabatı

Tarix: 10.11.2026 · Baza: `main` (`cecae2d`)

Statuslar: **Tam işləyir** · **Qismən işləyir** · **İşləmir** · **Mövcud deyil**.
"Tam işləyir" yalnız avtomatik testi və ya canlı yoxlaması olan funksiyalara yazılır.

## Mərhələ 1 — mövcud vəziyyət (dəyişiklikdən əvvəl)

| Modul | Status | Qeyd |
|---|---|---|
| Qeydiyyat, giriş, JWT, BCrypt | Tam işləyir | AuthSecurityTest |
| Rollar: SUPER_ADMIN, STORE_OWNER, EMPLOYEE (+4 icazə) | Tam işləyir | EmployeePermissionTest, server səviyyəsində yoxlanır |
| Mağazalararası izolyasiya | Tam işləyir | TenantIsolationTest |
| Müştəri / şirkət siyahısı, əlavə, redaktə | Tam işləyir | web + mobil |
| Borc əlavə et / Borc ödə (müştəri hesabı) | Tam işləyir | PartyAccountTest (cecae2d) |
| Borc artır, tarixçə (dəyişdirilməz jurnal) | Tam işləyir | DebtIncreaseAndProfileTest |
| Qismən ödəniş, artıq ödəniş qadağası, eyni anda iki ödəniş | Tam işləyir | DebtPaymentTest |
| Müştəri profili (cəmi, borclar, ödənişlər) | Qismən işləyir | müştəri kodu, qeydlər, son əlaqə, PDF yox idi |
| Dashboard | Qismən işləyir | kartlar kliklənmirdi; "Toplam borcum", "Ödədiyim", aktiv/bitmiş/gecikmiş sayı yox idi; ödənilən (çıxan) pullar daxil olanlarla birlikdə sayılırdı |
| Bu gün yazılan borclar siyahısı | Mövcud deyil | |
| Müştəri kodu (HP-000001) | Mövcud deyil | |
| Müştəri qeydləri | Mövcud deyil | |
| Müştəri şəxsi kabineti (link) | Mövcud deyil | |
| Gecikmiş borc (son tarix) | Qismən işləyir | son tarix var, gecikmə günləri və siyahı yox idi |
| WhatsApp | Qismən işləyir | hazır mətnlə açılır; redaktə edilə bilən şablonlar yox idi |
| Zəng et düyməsi | Mövcud deyil | |
| PDF: ödəniş çeki, bölmə hesabatı | Tam işləyir | ReportExportTest |
| PDF: müştəri borc çıxarışı | Mövcud deyil | |
| Excel: borclar (status filtri ilə aktiv/bağlanmış), ödənişlər, gündəlik/aylıq | Tam işləyir | ProductionHardeningTest |
| Excel: müştərilər, şirkətlər | Mövcud deyil | |
| İşçi əməliyyatları jurnalı (sahib üçün) | Qismən işləyir | audit_logs yazılır, amma baxmaq üçün ekran yox idi |
| Abunəlik, M10 sorğusu, çek, Super Admin təsdiqi, limitlər | Tam işləyir | SubscriptionPaymentFlowTest |
| Super Admin paneli | Tam işləyir | web |
| Bildiriş səsi (mobil) | Qismən işləyir | yalnız tətbiq açıq olanda |
| **Məhsul əlavə et** | Mövcud deyil | Sistemdə məhsul/anbar modulu yoxdur — tapşırıqda "mövcud" kimi yazılıb, amma kodda yoxdur |
| Dark mode | Mövcud deyil | |

## Mərhələ 2 — çatışmayanlar və bu işdə görülənlər

Aşağıdakı bölmə iş getdikcə yenilənir.

### Mərhələ 3 — Dashboard kartları və siyahılar
| İş | Status | Sübut |
|---|---|---|
| Bənövşəyi "Toplam borcum" + "+ ƏLAVƏ ET" (yalnız mağazanın şirkətlərə borcu) | Tam işləyir | HesabPro2Test, brauzer |
| Firuzəyi "Ödədiyim" (yalnız mağazanın etdiyi ödənişlər) | Tam işləyir | HesabPro2Test |
| Narıncı "Aktiv borclar", yaşıl "Bitmiş borc" | Tam işləyir | HesabPro2Test |
| Müştəri borcu, Şirkətlər bizə borcludur, Bu gün yazılan, Gecikmiş, Aktiv müştərilər, Alınan ödənişlər | Tam işləyir | HesabPro2Test |
| Hər kart kliklənir; kartdakı rəqəm = siyahının cəmi (eyni SQL) | Tam işləyir | 10 siyahı üçün test, brauzer |
| Axtarış: ad, telefon, müştəri kodu, çek nömrəsi; tarix filtrləri; müştəri/şirkət filtri; səhifələmə | Tam işləyir | test + brauzer |
| Köhnə panel: "Nağd/Kart/Köçürmə (cəmi)" şirkətə ödənişi də "alınan" sayırdı | Düzəldildi | HesabPro2Test |

### Mərhələ 4–6 — Müştəri kartı, tarixçə, qeydlər, kabinet
| İş | Status |
|---|---|
| Unikal müştəri kodu HP-000001… (köhnə müştərilərə miqrasiyada verilir, təkrarlanmır) | Tam işləyir (test + canlı miqrasiya) |
| Kart: kod, status (Aktiv / Gecikmiş / Borcsuz), qeydiyyat, son əməliyyat, son əlaqə | Tam işləyir |
| Düymələr: WhatsApp, Zəng et, Borc əlavə et, Ödəniş qəbul et, PDF hesabat, Redaktə | Tam işləyir (web brauzerdə; mobil analiz+test) |
| WhatsApp şablonları (5), redaktə, WhatsApp Web alternativi, "göndərilmiş sayılmır" xəbərdarlığı | Tam işləyir |
| Tarixçə: yazıldı / artırıldı / dəyişdirildi / ödəniş, çek, qeyd, işçi, qalıq | Tam işləyir (dəyişdirilməz jurnal) |
| Daxili qeydlər (dəyişdirilə/silinə bilməz, başqa mağaza görmür) | Tam işləyir |
| Müştəri kabineti: təsadüfi 256-bit token, yalnız hash saxlanır, müddət, ləğv, PDF | Tam işləyir (test + brauzer) |
| Gecikmiş borc: son tarix, gecikmə günləri, kart/siyahı xəbərdarlığı | Tam işləyir |

### Mərhələ 7–8 — Hesabatlar, işçilər
| İş | Status |
|---|---|
| Müştəri PDF çıxarışı (loqo, mağaza, kod, cəmlər, əməliyyatlar) | Tam işləyir (fayl yaranır; vizual olaraq açılıb baxılmayıb) |
| Excel: müştərilər, şirkətlər (yeni); borclar aktiv/bağlanmış, ödənişlər gündəlik/aylıq (mövcud) | Tam işləyir |
| "Hesabatlar" səhifəsi (web) | Tam işləyir |
| İşçi fəaliyyəti: işçi üzrə yazılan borc, qəbul edilən ödəniş, jurnal (yalnız sahib) | Tam işləyir |

### Testlər
- Backend: **103/103** (o cümlədən HesabPro2Test 7, PartyAccountTest 6, DebtIncreaseAndProfileTest 8)
- Flutter: analyze 0 problem, **29/29** test
- Web: tsc, lint, production build — xətasız
- Brauzer: yerli server + PostgreSQL ilə panel, siyahı, müştəri kartı, kabinet linki yoxlanılıb

### Qalanlar (edilməyib)
- **Məhsul əlavə et** — sistemdə məhsul modulu yoxdur; ayrıca layihədir.
- **Dark mode** — edilməyib.
- **Profil şəkli müştəri üçün** — baş hərflər göstərilir, şəkil yükləmə yoxdur.
- **Abunəlik paketinə görə PDF/Excel/WhatsApp icazəsi** — edilməyib.
- **Oxunmuş/oxunmamış bildirişlər** — edilməyib.
- APK real telefonda yoxlanmayıb.
