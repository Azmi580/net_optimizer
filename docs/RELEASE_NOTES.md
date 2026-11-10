## NetPulse v1.2.0

### Arka planda kesintisiz ve sıfır yük
- **Tünel arka planda çalışmaya devam ediyor:** Uygulamayı son uygulamalar ekranından kaydırıp kapatsanız bile DNS tüneli bağımsız bir servis olarak çalışmaya devam eder (`onTaskRemoved`, `stopWithTask=false`, `START_STICKY`).
- **Boştayken CPU uyandırması yok:** Paket döngüsü artık her 500 ms'de bir uyanmıyor. Yeni bir DNS paketi gelene ya da tünel kapatılana kadar çekirdekte bekliyor.
- **Ölçümler arka planda tamamen duruyor:** Uygulama görünmez olduğu anda (`ON_STOP`) sinyal dinleyicileri, hücre sorgusu ve ping döngüsü kapanır; uygulama öne gelince (`ON_START`) hemen yeniden başlar. Son ölçümler ekranda kalır.
- **Sayaçlar sadece biri izlerken güncelleniyor:** Tünel sayaçları yalnızca arayüz veya Hızlı Ayarlar kutucuğu açıkken yenilenir.

### Arayüz
- **Yeni skor göstergesi:** Degrade renkli yay, neon parıltı, parlayan uç noktası ve onluk çentikler. Skor değişince yay kaldığı yerden yumuşakça ilerler.
- **Canlı gecikme grafiği:** Yumuşak eğriler, altında degrade dolgu. Yeni ölçüm gelince grafik sola doğru akar, dikey ölçek ani sıçramadan uyum sağlar.
- **"Ağı Optimize Et":** Hap şeklinde, degrade ve parıltılı buton. Dokununca dalga efekti yayılır; çalışırken soluklaşır ve nabız halkaları gösterir. Alttaki büyük "DNS Tüneli" paneli kaldırıldı, tünel anahtarı Güvenli DNS kartına taşındı.
- **Tipografi:** Yeni yazı ölçeği. Rakamlar sabit genişlikte (tabular), böylece ping ve skor değişirken yazılar kaymıyor.
- **Koyu tema:** Saf siyah (#000000) taban, lacivert/füme yüzey katmanları ve yarı saydam cam kartlar.

### Kurulum
`app-debug.apk` dosyasını indirip açın. Yeni sürüm eskisinin üzerine kurulur.
