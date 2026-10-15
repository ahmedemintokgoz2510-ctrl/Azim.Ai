# Azim

Kişisel gelişim ve disiplin uygulaması (Android, Kotlin + Jetpack Compose). Hedefini seç (ders, fizik, tıp, ruhsal gelişim, bağımlılık bırakma…), yapay zeka koçun her gün görev versin, görevlerini bitirmeden telefon kilitli kalsın.

## Özellikler

- Hesap oluşturma ve giriş (yerel, parola PBKDF2 ile özetlenir)
- Çoklu hedef: alan, seviye, günlük süre
- Gemini 3.5 Flash (`gemini-3.5-flash`) ile günlük görev üretimi; önceki günlerin başarısına göre ayarlanır
- Koç sohbeti
- Haftalık fotoğraf: fotoğraf + haftalık performans birlikte analiz edilir
- Seri (streak), haftalık grafik, toplam süre
- Başlangıç profili: şu anki durumun, ne olmak istediğin, seni engelleyenler. Hazır düzenin yoksa sadece ne olmak istediğini yaz, koç planı çıkarır
- Günde 3 koç mesajı (sabah / öğle / akşam, saatleri ayarlanır). Mesaja dokununca koçla sohbet açılır
- Koç tonu: Nazik / Net / Sert. Sert tonda koç mazeretlere itiraz eder, seni kendinle yüzleştirir (ör. "aynaya bak, bu halinden memnun musun?")
- En fazla 4 Gemini anahtarı: biri günlük 100 isteğe yaklaşınca ya da kota hatası verince sıradakine geçilir
- Disiplin kilidi
  - Kilit saati (varsayılan 20:00): bugünün görevleri bitene kadar yalnızca telefon, mesajlar ve seçtiğin uygulamalar açılır
  - Eğitim modu: "Eğitime geç" de, konu (görev ya da serbest) ve süre seç, istersen niyetini yaz. Süre boyunca mesajlar ve ek uygulamalar kapanır, yalnızca telefon açık kalır
  - Kapalı bir uygulamayı açmaya çalışırsan kilit ekranı seni geri alır ve "kaç kez kaçmaya çalıştın" sayılır
  - "Uygulamaları aç / eğitimi bırak" dersen önce koç öğüt verir; 10 saniye sonra "Yine de bırak" açılır. Karar sende, ama bırakırsan koç sohbete "neden bıraktın?" diye yazar
  - Süre dolunca not yazıp eğitimi bitirirsin; eğitim geçmişi İlerleme sekmesinde (tamamlandı / yarıda bıraktı / acil çıkış) görünür
  - Acil durum çıkışı: günde 0–2 hak, 10 dakika
  - Kilidi gevşeten ayar değişiklikleri 24 saat gecikmeli uygulanır
  - Telefon yeniden başlayınca servis otomatik açılır

## APK nasıl alınır

### Yol 1: GitHub Actions (bilgisayarda kurulum gerekmez)
1. Bu klasörü bir GitHub deposuna yükle (`main` dalı).
2. Actions sekmesinde "APK derle" akışını çalıştır.
3. Bitince çıktılardan `azim-apk` içindeki `app-debug.apk` dosyasını indir, telefona yükle.
4. İsteğe bağlı: depo Secrets bölümüne `GEMINI_API_KEY` eklersen anahtar uygulamaya varsayılan olarak gömülür. Gömülü anahtar APK'dan çıkarılabilir, bu yüzden APK'yı başkalarına verme. Anahtarı uygulamada Ayarlar'dan girmek daha güvenlidir.

### Yol 2: Android Studio
1. Klasörü Android Studio ile aç. Gradle sürümü sorarsa 8.7 seç.
2. Build > Build APK(s). Çıktı: `app/build/outputs/apk/debug/app-debug.apk`

## İlk kurulum (telefonda)

1. Hesap oluştur, hedefini ekle.
2. Ayarlar > Gemini API anahtarını yapıştır (aistudio.google.com/apikey).
3. Ayarlar > İzinler: Kullanım erişimi ve Üstte gösterme izinlerini ver (kilit bunlar olmadan çalışmaz). Pil kısıtlamasını da kaldır.
4. Bilinmeyen kaynaktan yükleme uyarısı çıkarsa izin ver.

## Bilinmesi gerekenler

- Çoklu anahtar: Gemini kotası proje başınadır, bu yüzden anahtarlar farklı Google projelerinden olmalı. Google'ın kullanım şartları kota sınırlarını aşmaya çalışmayı yasaklayabilir; bu riski sen üstlenirsin. Tek anahtarla da uygulama çalışır.
- Koç mesajları LockService çalışırken gelir. Servis öldürülürse (agresif pil yöneticileri) mesajlar gecikir; saatten sonraki 3 saat içinde servis açılırsa geç de olsa gönderilir.
- Sert ton her durumda hakaret, aşağılama ve utandırma içermez; kullanıcı umutsuzluk ya da kendine zarar belirtisi gösterirse koç sertliği bırakır ve destek önerir. Bağımlılık ve ruhsal hedeflerde nüks utandırma aracı yapılmaz.

- Bu bir öz-disiplin aracıdır. İzinleri kapatarak, kaldırarak ya da güvenli modda açarak aşılabilir. Üretici özel pil yöneticileri (Xiaomi, Huawei, Samsung vb.) servisi öldürürse uygulamayı "kısıtlanmamış" yap.
- Haftalık fotoğraf Gemini API üzerinden Google'a gönderilir. Ücretsiz katmanda Google içeriği ürün geliştirmede kullanabilir. Uygulama ilk gönderimde bunu sorar.
- Bağımlılık ve ruhsal konularda koç tıbbi tavsiye vermez; ciddi durumlarda uzmana başvurmak gerekir.
- Kod bir Android SDK ortamında derlenmeden yazıldı; ilk derlemede küçük bir hata çıkarsa hata mesajını bana ilet, düzelteyim.
- Play Store'a yüklenecekse "kullanım erişimi + üstte gösterme" ile uygulama engelleme politika incelemesi gerektirir; kişisel APK olarak kullanımda sorun yok.

## Yapı

```
app/src/main/java/com/azim/app/
  MainActivity.kt, AppViewModel.kt
  data/   Room veritabanı, tercihler, hesap güvenliği
  ai/     Gemini istemcisi, koç (görev üretimi, sohbet, fotoğraf analizi)
  lock/   kilit servisi, kilit ekranı, izinli uygulamalar, izinler
  ui/     tema ve ekranlar (Bugün, Koç, İlerleme, Ayarlar)
```
