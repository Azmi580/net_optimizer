# Changelog

## [1.1.0]

### Eklenenler
- Hızlı Ayarlar kutucuğu (`DnsTileService`): tüneli aç/kapat, canlı skor (`92/100 • Mükemmel`).
- DNS profilleri: Hız (Cloudflare/Google), Güvenlik (Quad9), Reklam engelleme (AdGuard), Özel IP. Seçim kalıcıdır ve tünel açıkken anında uygulanır.
- Son 30 saniyenin ping/jitter/kayıp grafiği.
- `CellIdentityLte` / `CellIdentityNr` ile hücre bilgisi kartı: operatör, eNodeB/gNodeB, PCI, TAC, band.
- Dairesel skor göstergesi, haptik geri bildirim, optimizasyon snackbar'ı.

### Değişenler
- `compileSdk`/`targetSdk` 36. Edge-to-edge `WindowInsets.safeDrawing` ile yapıldı, predictive back açıldı.
- Ağ ayarları yönlendirmesi yalnızca AOSP intent'leriyle yapılıyor (operatör → kablosuz → ayarlar).
- AGP 8.11.1, Kotlin 2.2.0, Compose BOM 2025.06.01, Gradle 8.14.3.

## [1.0.0]
- İlk sürüm: yalnızca-DNS `VpnService` tüneli, TTL önbellek, hedged upstream havuzu, RSRP/RSRQ/SINR takibi, Compose arayüz, GitHub Actions ile APK.
