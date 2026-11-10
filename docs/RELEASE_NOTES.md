## NetPulse v1.1.0

### Yeni özellikler
- **Hızlı Ayarlar kutucuğu:** Bildirim panelinden tek dokunuşla DNS tünelini aç/kapat. Kutucuk canlı ağ skorunu da gösterir (ör. `92/100 • Mükemmel`).
- **DNS profilleri:** Hız (Cloudflare + Google), Güvenlik (Quad9), Reklam engelleme (AdGuard) ve Özel IP. Tünel açıkken kesintisiz geçiş yapılır.
- **Gecikme grafiği:** Son 30 saniyenin ping, jitter ve paket kaybı grafiği.
- **Hücre bilgisi kartı:** Operatör (MCC-MNC), eNodeB/gNodeB, PCI, TAC, band ve frekans. LTE ile 5G NR SA/NSA ayrı gösterilir.

### Arayüz
- Dairesel skor göstergesi; renk kaliteye göre yeşil, kehribar veya kırmızı olur.
- "Ağı Optimize Et" butonu ve tünel anahtarında titreşim geri bildirimi.
- Optimizasyon tamamlanınca bildirim (snackbar) gösterilir.
- Material You dinamik renkler (açık ve koyu tema).

### Platform
- `targetSdk` / `compileSdk` 36 yapıldı. Android 15–17 için tam edge-to-edge (`WindowInsets.safeDrawing`) ve predictive back desteği eklendi.
- Android 14+ için `specialUse` tipinde foreground service.
- Üreticiye özel kod yok; yalnızca açık Android (AOSP) API'leri kullanılıyor.
- Araç zinciri güncellendi: AGP 8.11, Kotlin 2.2, Compose BOM 2025.06.

### Kurulum
`app-debug.apk` dosyasını indirip açın. Daha önce NetPulse kuruluysa yeni sürüm eskisinin üzerine kurulur.
