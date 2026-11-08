# BORCDEFTERI — Flutter (Android / iOS)

Mağaza sahibi və işçilər üçün mobil tətbiq. Veb tətbiqlə eyni REST API-yə qoşulur; mağaza konteksti serverdə tokendən təyin olunur.

## Funksiyalar

- Giriş və mağaza qeydiyyatı (token cihazda `flutter_secure_storage` ilə saxlanılır)
- Panel: müştəri borcları, şirkət alacaqları, şirkət borcları, bu gün / bu ay, nağd və kart cəmi, son əməliyyatlar
- Üç borc bölməsi: axtarış, status filtri, qalıq/ödənilib cəmi, yeni borc, borc tarixçəsi
- Nağd / kart ödənişi (qalıqdan çox məbləğ həm tətbiqdə, həm serverdə rədd edilir)
- Ödənişlər siyahısı (üsul filtri, ad üzrə axtarış)
- Abunəlik məlumatı və planlar, bildirişlər (abunəlik yenilənməsi, yaxınlaşan/vaxtı keçmiş borclar)
- Bölmələr istifadəçinin icazələrinə görə göstərilir; server icazəni hər sorğuda yenidən yoxlayır

Mobil tətbiqdə olmayanlar (veb paneldə var): abunəlik qəbzinin yüklənməsi, müştəri/şirkət yaradılması və redaktəsi, borc redaktəsi, işçi idarəetməsi, Excel/PDF eksportu, Super Admin paneli.

## İşə salma

```bash
cd mobile
flutter pub get
flutter run --dart-define=API_URL=http://10.0.2.2:8080        # Android emulator → yerli backend
flutter run --dart-define=API_URL=https://<api-host>          # canlı API
```

`API_URL` verilməsə standart dəyər `http://10.0.2.2:8080`-dir (Android emulatorundan kompüterin localhost-u).

## Yoxlama

```bash
flutter analyze
flutter test
```

## Buraxılış

- Android: `flutter build appbundle --dart-define=API_URL=https://<api-host>`. Android SDK cmdline-tools, imzalama açarı (`key.properties`) tələb olunur.
- iOS: yalnız macOS + Xcode ilə: `flutter build ipa --dart-define=API_URL=https://<api-host>`.
- Release üçün API mütləq HTTPS olmalıdır (Android standart olaraq açıq HTTP trafikini bloklayır).
