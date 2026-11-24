# HesabPro Kart (müştəri tətbiqi)

Müştərinin HesabPro işlədən bütün mağazalardakı bonus kartları bir yerdə: animasiyalı salamlama, mağazanın kart
dizaynı, toxunanda çevrilən kart (arxasında kassa üçün barkod), balans, faiz və bonus tarixçəsi.

Giriş:
- **SMS kodu** — platformada SMS provayderi (`SMS_*`, bax `docs/DEPLOYMENT.md`) qoşulanda telefon nömrəsi ilə.
- **Kart nömrəsi + aktivləşdirmə kodu** — mağaza kartı verəndə göstərilən 6 rəqəmli kod (Bonus kartları → kart).

Server: `/api/public/customer-app/*` (`CustomerAppService`). Müştəri tokeni mağaza girişində işləmir.

```bash
cd customer_app
flutter pub get
flutter run --dart-define=API_URL=https://borcdefderi.onrender.com
flutter build apk --release --dart-define=API_URL=https://borcdefderi.onrender.com
```

CI (`.github/workflows/customer-app.yml`) main-ə hər birləşmədə APK-nı yığıb `kart-latest` buraxılışına qoyur.
