# NetPilot: Wi-Fi + cellular on a non-rooted Android phone, with no server

> **Verdict, up front.** True bandwidth bonding of Wi-Fi and cellular **for arbitrary device traffic** is
> **not achievable** on a stock, non-rooted Android phone without a cooperating remote endpoint.
> This is a property of how IP and TCP work and how Android isolates apps. It is not an API gap a clever app can fill.
>
> What *is* achievable, and what this project ships:
>
> 1. **Network monitoring and diagnosis** of every Wi-Fi and cellular network at once (not just the default).
> 2. **Failover and network selection for this app's own traffic**, with hysteresis.
> 3. **Application-layer multipath download.** This app fetches different byte ranges of one file over Wi-Fi and
>    cellular at the same time, from any ordinary HTTP server that supports `Range`. That is real aggregate
>    throughput, but only for that one kind of traffic and only inside this app.
>
> Everything in the UI, notification and docs is labelled **failover / network management, not bonding**.

---

## 1. Feasibility assessment

### 1.1 Why device-wide bonding without a server is impossible

Bonding means one flow (one TCP connection, one QUIC connection, one video stream) gets more than one link's
worth of throughput. That needs **both ends** to understand that packets arrive over two paths:

| Requirement | Why a phone-only app cannot meet it |
|---|---|
| **Same flow over two source IPs.** Wi-Fi and cellular have different public IPs (usually behind different NATs or CGNAT). | A plain TCP peer sees packets from a second IP as a different 4-tuple and resets them. Only MPTCP, multipath QUIC or a tunnel endpoint can re-assemble them. All of these live on the **remote** side. |
| **Re-ordering and re-assembly.** Two links have different RTTs and jitter, so packets arrive out of order. | Something downstream of both links must buffer and re-order. With no server, the only place both links meet is the far-end host, which we don't control. |
| **Interception of other apps' traffic.** | The only unprivileged hook is `VpnService`. It can choose *which* link each flow uses (§2.4), but it still can't split one flow across links without a far-end aggregator. |
| **Kernel multipath (MPTCP).** | Typical Android device kernels don't expose MPTCP to apps. Even where the kernel has it, adding per-interface subflow endpoints needs `CAP_NET_ADMIN` (netlink path-manager config), which apps don't have. Android's per-network fwmark routing is managed by `netd`, and the remote server must also speak MPTCP. Apple can use MPTCP for Siri and Maps only because it runs both ends. |

### 1.2 Technique-by-technique matrix

| Technique | Server needed? | Root/OS/carrier needed? | Single-flow aggregation? | Verdict for this project |
|---|---|---|---|---|
| `ConnectivityManager` monitoring + `NetworkCallback` | No | No | n/a | **Shipped** (`NetworkMonitor`) |
| `requestNetwork(TRANSPORT_CELLULAR)` to hold cellular up alongside Wi-Fi | No | No | n/a | **Shipped**. It makes both paths usable at once. |
| `bindProcessToNetwork` / `Network.openConnection` / `Network.socketFactory` | No | No | No (one link per socket) | **Shipped**. It steers this app only. |
| App-layer **HTTP Range striping** over both networks | No (any Range-capable server) | No | **Yes, for that download** | **Shipped** (`MultipathDownloader`) |
| `VpnService` + userspace TCP/IP stack, **per-flow** load balancing | No | No | **No.** Each flow is pinned to one link; many parallel flows aggregate. | **Designed, not built** (§2.4). High complexity and policy risk. |
| MPTCP (RFC 8684) | Yes, an MPTCP-capable peer | Kernel support + `CAP_NET_ADMIN` | Yes | **Not feasible** on stock non-rooted devices |
| Multipath QUIC (IETF multipath extension) | Yes, a supporting peer | No (userspace) | Yes | **Not feasible without a server.** It's an option only for traffic to endpoints you run. |
| Bonding tunnel (Speedify/OpenMPTCProuter style) | Yes, an aggregation server | No | Yes | **Excluded** by the no-server constraint |
| 3GPP **ATSSS** (5G Rel-16+ steering/switching/splitting) or LWA/LWIP | Carrier core (UPF/N3IWF) | Carrier + modem/OS | Yes | **Not app-controllable.** It's the standards path to true bonding. |
| OEM features (e.g. Samsung's former Download Booster) | No | System privileges | Range-split downloads | **Not available** to third-party apps. NetPilot's split downloader is the in-app equivalent. |

### 1.3 Prerequisites for the parts that do aggregate

The in-app multipath download only helps when all of these hold:

- The server answers `Range` requests with `206 Partial Content` and a full `Content-Range`.
- The object has a **strong ETag or Last-Modified**, so `If-Range` can detect a mid-download change.
- The URL isn't **bound to one client IP**. Some CDN signed URLs and anti-hotlinking setups reject the second IP.
- Both links are validated and have comparable throughput. With a 10:1 throughput ratio the gain is under 10 %.
- The user accepts cellular data use (metered).

---

## 2. Architecture

```
┌──────────────────────────────── NetPilot process (UID-scoped) ───────────────────────────────┐
│                                                                                              │
│  MainActivity ─┐                ┌───────── FailoverController (coroutine loop) ─────────┐    │
│  (status, mode,│  StateFlow     │                                                       │    │
│   diagnose,    │◄───UiState─────┤  NetworkMonitor ──► OsLink map ──┐                    │    │
│   split test)  │                │   • registerNetworkCallback(INTERNET, incl. VPN)      │    │
│                │                │   • registerDefaultNetworkCallback (system default)   │    │
│  NetPilotService (FGS,          │   • requestNetwork(CELLULAR) ← "keep cellular up"     │    │
│   specialUse) keeps loop alive  │                                  ▼                    │    │
│                                 │  Prober (per Network) ──► ProbeResult                 │    │
│                                 │   DNS → TCP:443 → TLS/HTTPS 204 → HTTP 204 (portal)   │    │
│                                 │                                  ▼                    │    │
│                                 │  :core  LinkSnapshot ─► FailoverPolicy ─► Decision    │    │
│                                 │         (pure Kotlin)  LinkScorer, Diagnoser          │    │
│                                 │                                  ▼                    │    │
│                                 │  NetworkSelector.bind(network) = bindProcessToNetwork │    │
│                                 └───────────────────────────────────────────────────────┘    │
│                                                                                              │
│  MultipathDownloader ── ChunkScheduler (:core) ── worker × N per Network ── Range GETs ──►   │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
        │ Wi-Fi Network (netId A)                         │ Cellular Network (netId B)
        ▼                                                 ▼
   Home router / AP → ISP                       Carrier RAN → PGW/UPF (CGNAT)
                     \______________ Internet: ordinary servers, unmodified _____________/
```

### 2.1 Modules

| Module | Contents | Why separate |
|---|---|---|
| `:core` (Kotlin/JVM) | `Model`, `LinkScorer`, `FailoverPolicy`, `Diagnoser`, `ChunkScheduler` | All decisions are pure functions of snapshots, so they're unit-tested on a JVM with no emulator. No `android.*` imports. |
| `:app` (Android) | `NetworkMonitor`, `Prober`, `NetworkSelector`, `FailoverController`, `MultipathDownloader`, `NetPilotService`, `MainActivity` | Thin adapters from Android APIs to `:core` types |

### 2.2 Monitoring

- **One callback for all internet networks.** `NET_CAPABILITY_NOT_VPN` is removed so VPNs are visible for diagnosis.
  Wi-Fi and cellular are tracked at the same time, not only the default network.
- **Callbacks, not polling.** `getNetworkCapabilities()` called from inside a callback is racy. State is built only
  from `onAvailable`, `onCapabilitiesChanged`, `onLinkPropertiesChanged` and `onLost`.
- **Holding cellular up.** Without an outstanding cellular request, Android lingers and then tears down cellular
  soon after Wi-Fi validates. Then there is no second path to fail over to, and re-attach takes seconds.
  `requestNetwork(TRANSPORT_CELLULAR)` keeps it up. The cost is battery and the probe traffic.
- **OS signals used:** `VALIDATED`, `CAPTIVE_PORTAL`, `NOT_METERED`, `signalStrength`, `linkDownstreamBandwidthKbps`
  (a coarse OS estimate, never treated as a measurement), and `LinkProperties.privateDnsServerName`.

### 2.3 Steering and failover (this app's traffic only)

- `bindProcessToNetwork(n)` routes **new** sockets and DNS for this process via `n`. Existing sockets stay on their
  old network. App code must reconnect after a switch; for OkHttp, call `connectionPool.evictAll()`.
- **Modes:** `AUTO`, `PREFER_WIFI`, `PREFER_CELLULAR`, `WIFI_ONLY`, `CELLULAR_ONLY`. VPN networks are reported, never selected.
- **Hysteresis (FailoverPolicy):**
  - *Fail over fast:* the bound link is abandoned after `failThreshold` (2) consecutive failed evaluations, or
    immediately on `onLost`. The membership-change trigger re-evaluates with cached probes.
  - *Fail back slowly:* a non-current link must pass `recoverThreshold` (3) consecutive probes, **and**
    `minDwellMs` (30 s) must have elapsed. This stops a flapping AP from causing reconnect storms.
  - *AUTO:* switches voluntarily only if the candidate's score beats the current one by `switchMargin` (15).
  - While the bound link is failing, the probe interval drops from 15 s to 3 s.
  - If no allowed link is usable, the app **unbinds** and follows the system default instead of a dead network.
- **Other apps are never affected.** No public API lets a normal app change another app's routing or the system
  default network. The UI says so, and offers `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` so the user can change
  the system network themselves.

### 2.4 Optional Phase 3: `VpnService` per-flow load balancing (designed, not built)

This is the only unprivileged way to influence **other apps'** traffic. It's described here so the trade-off is
explicit. It is **not** bonding.

- A local `VpnService` captures packets from a TUN fd. A userspace TCP/IP stack (lwIP or gVisor netstack via a
  tun2socks-style bridge) **terminates** each TCP flow locally. NetPilot then opens a new upstream socket for the
  flow, calls `protect()` and `Network.bindSocket()` to pin it to Wi-Fi or cellular, and relays bytes. UDP and QUIC
  get a per-5-tuple NAT table.
- Scheduling is per flow: weighted round-robin or least-loaded per link. Many parallel flows (a browser fetching
  assets, a download manager with segments) aggregate. **A single flow never exceeds one link's capacity.**
- Known breakages and costs:
  - Services that bind a session to the client IP (banking, some SSO, anti-fraud) can log users out when two flows
    of one session leave from different IPs.
  - Only one VPN can be active, so this conflicts with corporate or always-on VPNs.
  - Per-packet userspace processing costs CPU and battery.
  - MTU/MSS clamping, IPv6, and DNS interception must all be handled.
  - Google Play's VpnService policy requires VPN to be the app's core function, with a declaration.
- **Security posture:** this is an L4 relay, with no TLS interception and no CA installation. Even so, the app
  becomes a privileged choke point for all device traffic, so it needs a threat model, fuzzing of the packet
  parser, and no logging of payloads.

### 2.5 Security and privacy design

- **Least privilege.** Normal permissions only, plus runtime `POST_NOTIFICATIONS`. No location (SSID isn't needed),
  no `QUERY_ALL_PACKAGES`, no `BIND_VPN_SERVICE` in Phases 1–2, and `allowBackup=false`.
- **Cleartext is denied by default** (`network_security_config.xml`). The only exception is the captive-portal probe
  host, which has to be plain HTTP because portals intercept HTTP, not HTTPS.
- **Probe integrity.** The *health* decision uses the **HTTPS** 204 probe with normal certificate validation.
  A portal or MITM can forge the HTTP probe but can't make HTTPS succeed. A TLS failure is reported as
  `TLS_FAIL` (possible interception or a wrong clock) and never "fixed" by relaxing validation.
- **Split-download integrity.** Every range carries `If-Range`, and `Content-Range` and length are checked per chunk.
  An optional end-to-end SHA-256 check is available. Content is never stitched from two object versions.
- **No user data leaves the device** beyond the probes to `www.google.com` and `connectivitycheck.gstatic.com`.
  Both endpoints can be configured in `Prober` if you'd rather use your own.

---

## 3. Implementation plan

| Phase | Work | Exit criteria |
|---|---|---|
| **0. Skeleton** | Gradle multi-module (`:core` JVM, `:app` Android), manifest, network security config, CI running `:core:test` + `:app:lintDebug` + `:app:assembleDebug` | Clean build; core tests green |
| **1. Monitor and diagnose** | `NetworkMonitor` (all networks + system default + cellular hold), `Prober`, `Diagnoser`, status UI | Both transports listed with correct validated/metered/signal values; each induced fault in §7 produces the expected finding code |
| **2. Steer and fail over** | `FailoverPolicy` + `LinkScorer` (unit-tested), `NetworkSelector`, `FailoverController` loop, membership-change trigger, degraded fast re-probe, foreground service, mode selector | Acceptance criteria A1–A6 (§8) |
| **3. In-app aggregation** | `ChunkScheduler` (unit-tested), `MultipathDownloader` with `If-Range`, Content-Range checks and SHA-256; split-download test UI | A7–A8 |
| **4. Hardening** | Battery and data budget (configurable intervals, pause probes when screen is off and nothing is bound), OEM battery-optimisation guidance, R8 release build, signed APK | A9–A12 |
| **5. (Optional) VpnService per-flow LB** | Only if per-flow balancing for other apps is worth the policy and security cost (§2.4). Separate threat model and review. | Separate spec |

---

## 4. Source map

```
android/netpilot/
├── settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew, gradle/wrapper/
├── core/                                   # pure Kotlin, JVM unit tests
│   └── src/main/kotlin/dev/netpilot/core/
│       ├── Model.kt            LinkSnapshot, ProbeResult, Mode, Transport, DeviceState
│       ├── LinkScorer.kt       0..100 ranking; isUsable()
│       ├── FailoverPolicy.kt   hysteresis state machine -> Decision
│       ├── Diagnoser.kt        DNS→TCP→TLS→HTTP fault localisation + device findings
│       └── ChunkScheduler.kt   thread-safe range work queue (requeue on failure / link loss)
│   └── src/test/kotlin/...     FailoverPolicyTest, DiagnoserTest, ChunkSchedulerTest
└── app/
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/xml/network_security_config.xml
        └── kotlin/dev/netpilot/app/
            ├── NetworkMonitor.kt        NetworkCallback + cellular hold + OsLink→LinkSnapshot
            ├── Prober.kt                per-Network DNS/TCP/TLS/HTTP probes
            ├── NetworkSelector.kt       bindProcessToNetwork wrapper
            ├── FailoverController.kt    monitor→probe→policy→bind loop; UiState
            ├── MultipathDownloader.kt   Range-striped download across Networks
            ├── NetPilotService.kt       specialUse foreground service
            ├── NetPilotApp.kt           shared controller
            └── MainActivity.kt          status / mode / diagnose / split test
```

Core API in use (abridged from the sources):

```kotlin
// Hold cellular up while Wi-Fi is default, so a second path exists.
cm.requestNetwork(
    NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
    cellularHold)

// Probe a specific network regardless of the default/bound one.
val addrs = network.getAllByName("www.google.com")
network.socketFactory.createSocket().use { it.connect(InetSocketAddress(addrs[0], 443), 4000) }
val code = (network.openConnection(URL("https://www.google.com/generate_204")) as HttpURLConnection).responseCode

// Decide and steer this process.
when (val d = policy.evaluate(mode, snapshots)) {
    is Decision.Switch -> cm.bindProcessToNetwork(networkFor(d.toId))
    is Decision.NoUsableLink -> cm.bindProcessToNetwork(null)
    is Decision.Stay -> Unit
}

// Aggregate one download over both networks (any Range-capable server).
MultipathDownloader().download(url, mapOf(wifiId to wifi, cellId to cell), File(cacheDir, "f.bin"), expectedSha256)
```

---

## 5. Build, sign, install

### 5.1 Prerequisites
- Android Studio Ladybug (2024.2) or newer, **or** JDK 17+ and the Android SDK command-line tools.
- SDK Platform 35 and Build-Tools 35.x (`sdkmanager "platforms;android-35" "build-tools;35.0.0"`).
- A device running Android 10+ (API 29) with USB debugging enabled, a SIM/eSIM with mobile data, and Wi-Fi.

### 5.2 Project setup
```bash
cd android/netpilot
echo "sdk.dir=$ANDROID_HOME" > local.properties    # Android Studio writes this automatically
./gradlew :core:test                               # pure-JVM logic tests (no device)
./gradlew :app:lintDebug :app:assembleDebug        # debug APK -> app/build/outputs/apk/debug/app-debug.apk
```
In Android Studio, use **File → Open → `android/netpilot`**, let Gradle sync, then **Run ▶** on a connected device.

Supply-chain hardening (recommended): add `distributionSha256Sum=` for `gradle-8.14.3-bin.zip` to
`gradle/wrapper/gradle-wrapper.properties`, using the checksum published on gradle.org. Enable Gradle dependency
verification with `./gradlew --write-verification-metadata sha256 help`, and validate the wrapper jar in CI with
`gradle/actions/wrapper-validation`.

### 5.3 Release signing
```bash
keytool -genkeypair -v -keystore netpilot-release.jks -alias netpilot \
        -keyalg RSA -keysize 4096 -validity 10000          # store OUTSIDE the repo / in a secrets vault
cat > keystore.properties <<'EOF'                          # git-ignored
storeFile=/secure/path/netpilot-release.jks
storePassword=…
keyAlias=netpilot
keyPassword=…
EOF
./gradlew :app:assembleRelease                              # R8-minified, signed if keystore.properties exists
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```
For Play distribution, build an AAB (`./gradlew :app:bundleRelease`) and use Play App Signing. Keep the upload key
separate from the app-signing key.

### 5.4 Install and observe
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.netpilot.app/.MainActivity
adb logcat -s NetPilot NetworkMonitor                     # decisions and failover events
adb shell dumpsys connectivity | grep -A3 "NetworkAgentInfo"   # cross-check what the OS sees
```

---

## 6. APK delivery: limitations and considerations

| Topic | Limitation / consideration |
|---|---|
| **Scope of effect** | Steering and aggregation apply **only to NetPilot's own traffic**. Installing it doesn't make YouTube, browsers or other apps faster or more resilient. State this on the store listing; anything else is misleading. |
| **Play policy: foreground service** | `specialUse` FGS (Android 14+) needs a Play Console declaration and justification, which may be rejected for a "monitor" use case. Alternatives: run only while visible, or use `connectedDevice`/`dataSync` where they fit. `dataSync` is time-limited on Android 15. Sideloaded builds aren't affected. |
| **Play policy: VpnService** | Phase 5 would trigger the VpnService declaration and review requirements. |
| **Sideloading** | Users must allow "Install unknown apps" for the installer. Play Protect may warn about unrecognised developers. Distribute with a stable signing key, publish the SHA-256 cert fingerprint, and never ship debug-signed builds. |
| **OEM battery management** | Samsung, Xiaomi, Oppo and Huawei may kill the service despite the FGS. Guide users to set battery to *Unrestricted* and allow autostart. `Diagnoser` flags `BG_RESTRICTED`. |
| **Data Saver / metered data** | Data Saver can block background cellular use. Holding cellular and probing it uses data (about 15 MB/day at the 15 s default). Make intervals user-configurable and show usage. |
| **Device/OS variance** | `signalStrength` and `linkDownstreamBandwidthKbps` are often unspecified or coarse. Dual-SIM devices expose only the active data SIM. Some carriers block tethering-like dual use or zero-rate only one path. |
| **Captive portals** | NetPilot detects portals but can't sign in. Android's own portal flow handles that. |
| **Min SDK 29** | Android 10+. Earlier versions lack `Settings.Panel` and `signalStrength`, and have weaker per-network APIs. |

---

## 7. Test matrix

**Devices / configurations** (minimum set):

| ID | Device class | OS | Why |
|---|---|---|---|
| D1 | Pixel (stock) | Android 15/16 | Reference AOSP connectivity stack, latest FGS rules |
| D2 | Samsung Galaxy (One UI) | Android 14+ | Aggressive battery management, OEM connectivity tweaks |
| D3 | Xiaomi/Redmi (HyperOS) | Android 13+ | Autostart restrictions, background killing |
| D4 | Any device at minSdk | Android 10 | API floor (`signalStrength`, `Settings.Panel`) |
| D5 | Dual-SIM device | Android 13+ | Data-SIM switching, `onLost`/`onAvailable` churn |
| D6 | IPv6-only carrier (464XLAT/CLAT) | any | Probes and split download over IPv6 |

**Scenarios** (run on D1 to D3 at minimum, plus D4 to D6 where relevant):

| # | Scenario | How to induce | Expected |
|---|---|---|---|
| T1 | Wi-Fi + cellular healthy | Normal | Both listed and validated; PREFER_WIFI binds Wi-Fi; no ERROR findings |
| T2 | Wi-Fi disassociates | Turn AP off / walk out of range | `onLost` → switch to cellular within **2 s** (cellular held); event log says FAILOVER |
| T3 | Wi-Fi up, upstream dead | Unplug the router's WAN | Probe fails `TCP_FAIL`/`DNS_FAIL`; failover within **45 s** at defaults |
| T4 | Wi-Fi recovers | Re-plug WAN | No fail-back until 3 good probes **and** 30 s dwell; then a single switch back |
| T5 | Flapping Wi-Fi | Script AP WAN on/off every 10 s for 5 min | ≤ 1 voluntary switch per dwell window; no oscillation |
| T6 | Captive portal | Hotel or test portal (e.g. `nodogsplash`) | `CAPTIVE_PORTAL`; portal link never bound |
| T7 | Broken DNS | Router DNS → blackhole | `DNS_FAIL` on Wi-Fi only; cellular unaffected |
| T8 | TLS interception | Transparent mitmproxy with untrusted CA | `TLS_FAIL`; link treated unusable; validation never relaxed |
| T9 | High latency / loss | `tc qdisc add dev br-lan root netem delay 800ms loss 5%` | `HIGH_LATENCY`; AUTO prefers cellular only if score margin ≥ 15 |
| T10 | Strict Private DNS misconfigured | Settings → Private DNS → `bad.invalid` | `PRIVATE_DNS` finding |
| T11 | VPN active | Any VPN app | `VPN_ACTIVE` info; VPN never selected; app still binds a physical network |
| T12 | Mode WIFI_ONLY with Wi-Fi down | Select mode, kill Wi-Fi | `NoUsableLink`; process **unbound**; cellular never bound |
| T13 | Airplane mode toggle | Quick settings | `AIRPLANE_MODE`; recovery when off; no crash |
| T14 | Data Saver on, app backgrounded | Settings → Data Saver | `DATA_SAVER` warning; behaviour documented |
| T15 | Doze / background | `adb shell dumpsys deviceidle force-idle` | FGS keeps the loop alive (D1); OEM differences recorded (D2/D3) |
| T16 | Split download, both links | 200 MB file on a Range-capable CDN | SHA-256 matches; bytes on both links; throughput ≥ 1.3× best single link when links are within 2:1 |
| T17 | Split download, link lost mid-transfer | Kill Wi-Fi at 50 % | Completes on cellular; requeued ranges fetched; hash matches |
| T18 | Split download, no Range support | Server without `Accept-Ranges` | Fails fast with `NotRangeCapable`; no partial file presented as complete |
| T19 | Object changes mid-download | Replace the file on a test server during download | `If-Range` → 200 → abort; no mixed file |
| T20 | Dual-SIM data switch | Change data SIM | Old cellular `onLost`, new one `onAvailable`; failover if bound |
| T21 | Stop | Tap Stop | `boundNetworkForProcess == null`; callbacks unregistered; cellular hold released (`dumpsys connectivity` shows no NetPilot request) |
| T22 | Overnight soak | 8 h screen-off, PREFER_WIFI | No crash/ANR; data use ≤ budget; battery impact recorded |

---

## 8. Acceptance criteria

| ID | Criterion | Verified by |
|---|---|---|
| A1 | The app **never claims bandwidth bonding**. UI, notification, store text and docs say "failover / network management" and "this app's traffic only". | Review, T1 |
| A2 | Both Wi-Fi and cellular are monitored at the same time, with validated/metered/signal state matching `dumpsys connectivity`. | T1 |
| A3 | Link loss of the bound network → this app's new connections use the other network within **≤ 2 s** with "keep cellular up" on, or **≤ 10 s** with it off. | T2 |
| A4 | Upstream failure on an associated link → failover within **≤ 45 s** at default settings. | T3 |
| A5 | Hysteresis: no fail-back before 3 consecutive good probes **and** 30 s dwell; no oscillation under T5. | T4, T5, `FailoverPolicyTest` |
| A6 | `*_ONLY` modes never bind the other transport. With no usable link, the process is unbound. | T12, unit tests |
| A7 | Each fault in T6 to T11 and T13 to T14 produces exactly the documented finding code with a remediation. | T6–T14, `DiagnoserTest` |
| A8 | Split download: correct SHA-256 in every run, survives the loss of one link, refuses non-Range or changed objects, and reaches ≥ 1.3× the best single link when links are within 2:1. | T16–T19, `ChunkSchedulerTest` |
| A9 | Stop fully releases resources: no binding, no callbacks, no cellular request. | T21 |
| A10 | Permissions are exactly those in the manifest. No location, VPN or package-visibility permission. Cleartext only to the portal-probe host. | Manifest review, `aapt dump permissions` |
| A11 | `:core:test` passes. `:app:lintDebug` has no errors. The release APK verifies with `apksigner`. | CI |
| A12 | 8 h soak: no crash or ANR. Probe data ≤ 20 MB/day at defaults. | T22 |
