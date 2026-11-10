# NetPulse — Galaxy A55 için hücresel ağ optimizasyonu

Android `VpnService` tabanlı **yalnızca-DNS** tüneli, gerçek zamanlı radyo (RSRP/RSRQ/SINR) ve RTT takibi.
Operatörün fiziksel hız sınırı aşılmaz. Uygulamanın yaptıkları: DNS gecikmesini azaltmak, MTU'yu ayarlamak
ve darboğazın radyoda mı (kapsama, girişim, yoğunluk) yoksa gecikme/kayıpta mı olduğunu göstermek.

## Aşama 1 — Mimari

### 1. Paketler, DI, thread yönetimi
```
com.netpulse.optimizer
├── core/di/AppContainer.kt        Elle yazılmış DI (tek process, ~6 düğüm; Hilt/kapt maliyeti yok)
├── domain/                        Saf Kotlin: modeller + SignalScorer (Android bağımlılığı yok)
├── data/
│   ├── connectivity/              ConnectivityMonitor: VPN dışındaki fiziksel ağ, MTU, bant genişliği
│   ├── diagnostics/               NetworkDiagnosticsHelper: TelephonyCallback + TCP RTT (Flow)
│   └── vpn/                       VpnStateRepository (StateFlow, servis ↔ UI)
├── vpn/
│   ├── LocalDnsVpnService.kt      TUN yaşam döngüsü, paket döngüsü, foreground bildirimi
│   ├── MtuResolver.kt             1400–1500 MTU seçimi
│   ├── packet/IpPacketCodec.kt    IPv4/UDP/TCP ayrıştırma, checksum, IP fragmentasyonu, TCP RST
│   └── dns/                       DnsMessage, TTL'li LRU DnsCache, hedge'li DnsUpstreamPool
├── optimizer/NetworkOptimizer.kt  "Optimize Et" iş akışı
└── ui/                            Compose MainScreen + MainViewModel (MVVM, StateFlow)
```
* **Coroutines:** Servis `SupervisorJob + Dispatchers.IO` kullanır. Her DNS sorgusu kendi coroutine'inde çalışır
  (eşzamanlı en fazla 128, `Semaphore`). TUN'a yazma tek bir writer coroutine üzerinden `Channel` ile yapılır.
  Bu sayede fd'ye yazmalar sıralı kalır, ek kilit gerekmez.
* **StateFlow:** `VpnStateRepository` (servis yazar), `ConnectivityMonitor.linkState` ve ViewModel'deki
  `combine(...).stateIn(WhileSubscribed(5s))`. Ekran kapanınca telefon callback'leri ve ping döngüsü durur, pil harcanmaz.

### 2. VpnService ve DNS yönlendirme
* TUN arayüzünün adresi `10.111.222.1/32`. Tünele **yalnızca** sanal çözücüler `10.111.222.2` ve `.3` yönlendirilir.
  Sistem DNS'i bu adreslere ayarlanır. Böylece tüm uygulamaların DNS sorguları tünele girer, diğer tüm trafik
  hiç dokunulmadan fiziksel ağdan akar. Ek gecikme ve pil maliyeti olmaz.
* `allowFamily(AF_INET6)` eklendi. Bu olmadan Android, VPN'de IPv6 yapılandırması bulunmadığı için IPv6 trafiğini engellerdi.
* UDP/53 sorgusu şu yolu izler: TTL'li önbellek (yaşlandırılmış TTL ile) → yoksa korumalı (`protect()`) soket havuzu.
  Her upstream (1.1.1.1, 1.0.0.1, 8.8.8.8) için kalıcı, bağlı bir UDP soketi vardır ve rastgele transaction-id ile
  çoğullanır. **Hedging:** önce en hızlı upstream sorgulanır. EWMA RTT'nin 1,5 katı içinde yanıt gelmezse sıradaki
  upstream de yarışa katılır. İlk geçerli yanıt kazanır. Hücresel paket kaybından kaynaklanan uzun gecikme kuyrukları böyle kesilir.
* TC (truncated) gelen yanıtlar TCP üzerinden yeniden alınır. MTU'yu aşan yanıtlar kesilmez, IP fragmentasyonu ile iletilir.
* Sanal çözücüye gelen TCP bağlantılarına (DNS-over-TCP, :853 DoT denemesi) anında RST dönülür. İstemci saniyelerce SYN zaman aşımı beklemez.
* Ağ değişince (Wi-Fi ↔ hücresel, yeniden bağlanma; 800 ms debounce) `setUnderlyingNetworks` çağrılır, soket havuzu
  yeniden kurulur ve önbellek temizlenir. Tazelemede önce yeni TUN kurulur, sonra eskisi kapatılır. DNS'te kesinti olmaz.
* Always-on VPN desteklenir (`SUPPORTS_ALWAYS_ON`). FGS tipi `specialUse` (Android 14+ zorunluluğu).

### 3. Sinyal ve RTT analizi
* `TelephonyCallback.SignalStrengthsListener` ve `DisplayInfoListener` olay tabanlıdır, polling yapılmaz.
  NR (SA veya NSA) değeri varsa `ssRsrp/ssRsrq/ssSinr`, yoksa LTE `rsrp/rsrq/rssnr` okunur.
  Samsung modemleri bazı değerleri boş bırakırsa `allCellInfo` yedek olarak kullanılır.
* Ağ türü: `NETWORK_TYPE_NR` → 5G SA. `OVERRIDE_NR_NSA/ADVANCED` → 5G. `OVERRIDE_LTE_CA/ADVANCED_PRO` → 4.5G.
* RTT: 2 saniyede bir 1.1.1.1:443 ve 8.8.8.8:443 adreslerine paralel TCP el sıkışması yapılır.
  SYN/SYN-ACK süresi ICMP ping'e eşdeğerdir ve root gerektirmez. Son 10 örnekten ortalama, jitter ve kayıp yüzdesi hesaplanır.
* `SignalScorer` 3GPP saha eşiklerini kullanır: RSRP −115…−85, SINR 0…20, RSRQ −18…−8, RTT 200…30 ms.
  Bunlar ağırlıklı ortalamayla 0–100 skora çevrilir ve kayıp cezası uygulanır.
  Sonuç Zayıf/Orta/Mükemmel olarak gösterilir. En kötü bileşen darboğaz olarak raporlanır.

### 4. CI/CD
`.github/workflows/build-apk.yml` her push'ta şunları yapar: Ubuntu, JDK 17, Android SDK, Gradle 8.11.1 →
`assembleDebug` → `app-debug.apk` dosyasını hem Artifact olarak hem de **Release** olarak yayınlar
(telefondan tek dokunuşla indirilir). Debug keystore önbellekte tutulur. Böylece her yeni APK eskisinin üzerine kurulur, kaldırma gerekmez.

## Telefona kurulum (≈2 dk)

1. Git ve GitHub CLI'yi kurup oturum açın (bir kez):
   ```bash
   winget install --id Git.Git -e; winget install --id GitHub.cli -e; gh auth login
   ```
2. Repoyu oluşturup gönderin. Workflow kendiliğinden başlar:
   ```bash
   git init -b main; git add .; git commit -m "NetPulse"; gh repo create netpulse --public --source . --push
   ```
3. Yaklaşık 3–4 dakika sonra telefonda `https://github.com/<kullanıcı-adınız>/netpulse/releases/latest` adresini açın.
   `app-debug.apk` dosyasına dokunun, **Bilinmeyen uygulamalara izin ver** seçeneğini açın, ardından **Yükle**'ye basın.

## Bilinen sınırlar
* **Ayarlar → Bağlantılar → Diğer ağ ayarları → Özel DNS** bir sunucu adına ayarlıysa (katı mod), Android DNS'i
  doğrudan DoT ile gönderir ve tünel devre dışı kalır. En iyi sonuç için Özel DNS'i **Kapalı** veya **Otomatik** yapın.
* Bant/RAT seçimi modem tarafından yapılır, uygulama bunu zorlayamaz. Zayıf bantta yalnızca operatör ağ ekranına yönlendirir.
* Tarayıcıların kendi "Güvenli DNS" (DoH) ayarı açıksa bu tarayıcılar tüneli kullanmaz.
