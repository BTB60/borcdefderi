# HesabPro Kassa — Microsoft Store-a yerləşdirmə

Windows proqramı (`desktop/`) hazırda **NSIS quraşdırıcı** (`.exe`) kimi paylanır. `.exe` imzasız
olduğu üçün Windows SmartScreen və antiviruslar "naməlum naşir" xəbərdarlığı göstərir.

**Həll:** tətbiqi Microsoft Store-a MSIX/APPX paketi kimi qoymaq. Store tətbiqi **Microsoft özü imzalayır**,
ona görə SmartScreen və antivirus xəbərdarlığı olmur, yeniləmələri də Store özü paylayır.

> **Qiymət qeydi:** Store-da yerləşdirmək üçün **Microsoft Partner Center fərdi hesabı** lazımdır —
> birdəfəlik ~19 USD (~32 AZN) qeydiyyat haqqı. Təkrarlanan və ya tətbiq başına ödəniş **yoxdur**.
> Bu, hər il 200–600 AZN olan code-signing sertifikatından xeyli ucuzdur, çünki imzanı Microsoft edir.

Kodda hər şey hazırdır (bax: `desktop/package.json` → `build.appx` və `scripts.dist:store`;
`desktop/main.js:419` — Store versiyasında öz-özünə yeniləmə söndürülüb). Qalan yalnız Partner Center-dən
**3 dəyəri** almaq və paketi yığıb yükləməkdir.

---

## 1. Partner Center hesabı aç və tətbiq adını rezerv et

1. <https://partner.microsoft.com/dashboard> ünvanında **fərdi** developer hesabı aç (birdəfəlik ~19 USD).
2. **Apps and games → New product → MSIX or PWA app** seç.
3. Tətbiq adını rezerv et, məsələn **HesabPro Kassa**. (Ad artıq tutulubsa, başqa variant seç.)

## 2. Identity (kimlik) dəyərlərini götür

Rezerv etdikdən sonra tətbiqin səhifəsində **Product management → Product identity** bölməsinə keç.
Orada üç dəyər var:

| Partner Center-dəki ad | `package.json` → `build.appx` sahəsi |
|---|---|
| **Package/Identity/Name** (məs. `12345HesabPro.HesabProKassa`) | `identityName` |
| **Package/Identity/Publisher** (məs. `CN=ABCD1234-...`) | `publisher` |
| **Package/Properties/PublisherDisplayName** | `publisherDisplayName` |

## 3. Dəyərləri `desktop/package.json`-a yaz

`build.appx` blokundakı yer tutucuları real dəyərlərlə əvəz et:

```jsonc
"appx": {
  "applicationId": "HesabProKassa",
  "displayName": "HesabPro Kassa",
  "identityName": "12345HesabPro.HesabProKassa",      // ← Partner Center: Identity/Name
  "publisher": "CN=ABCD1234-5678-...",                 // ← Partner Center: Identity/Publisher
  "publisherDisplayName": "HesabPro",                  // ← Partner Center: PublisherDisplayName
  ...
}
```

> `publisher` default olaraq `CN=00000000-0000-0000-0000-000000000000`-dır — bu sadəcə yer tutucudur,
> submission-dan əvvəl mütləq dəyişdirilməlidir.

## 4. Paketi yığ

`desktop/` qovluğunda:

```powershell
cd desktop
npm install
npm run dist:store
```

Nəticə: `desktop/dist/HesabPro-Kassa-Store.appx`.

> **Tələblər:**
> - **Node.js LTS** (18, 20 və ya 22) — çox yeni versiyalar (məs. Node 25) `electron-builder` 26 ilə uyğun
>   olmaya bilər və yığım `makeappx` addımında `spawn UNKNOWN` xətası verir. Problem yaranarsa, Node LTS-ə keç.
> - **Windows 10/11 SDK** (`makeappx.exe` üçün) — Visual Studio ilə, ya da ayrıca "Windows SDK" kimi quraşdırılır.
> - **Developer Mode** açıq olsun (Parametrlər → Məxfilik və təhlükəsizlik → Tərtibatçılar üçün) —
>   `electron-builder` imza alətlərini açarkən symbolic link yaratmağa ehtiyac duyur.

## 5. Paketi yüklə və göndər

1. Partner Center-də tətbiqin **Packages** bölməsinə `.appx` faylını sürüklə.
2. **Store listings** (açıqlama, ekran şəkilləri), **Properties** (kateqoriya), **Age ratings** (anket) doldur.
3. **Submit** — Microsoft tətbiqi imzalayır və sınaqdan keçirir (adətən bir neçə saat–bir gün).
4. Təsdiqdən sonra tətbiq Store-da görünür; istifadəçilər xəbərdarlıqsız quraşdırır.

## 6. Yeniləmələr

Yeni versiya üçün: `desktop/package.json`-da `version`-u artır → `npm run dist:store` → yeni `.appx`-i
Partner Center-ə yüklə → Submit. Store istifadəçilərə özü çatdırır (kodda əlavə iş lazım deyil).

---

## Qeydlər

- **İki kanal paralel qala bilər:** `npm run dist` (NSIS `.exe`, birbaşa yükləmə üçün) və
  `npm run dist:store` (Store üçün `.appx`). İkisi eyni koddan yığılır.
- **Lokal sınaq (istəyə görə):** imzasız `.appx` birbaşa quraşdırılmır; sınamaq üçün `publisher`-ə uyğun
  öz-imzalı sertifikat yaratmaq lazımdır. Store üçün buna ehtiyac yoxdur — Microsoft imzalayır.
- **Identity dəyərlərini commit etmək:** `publisher` GUID-i gizli deyil, repo-da saxlamaq təhlükəsizdir.
