# Phase 6: Security, privacy and performance audit, plus "Wi-Fi + mobile data together"

NetPilot 0.3.0. This phase does two things:

1. **Audit** the whole app (security, privacy, performance) and fix what was found.
2. Add what you asked for: Wi-Fi and mobile data **working together**, and a **speed test**.

Evidence labels: **[V]** verified here (test, harness or device run) · **[S]** public source · **[M]** estimate or model.

---

## Part A: Wi-Fi + mobile data together

### A1. What is possible without a server (the honest version)

| Kind of traffic | Faster with both networks? | How NetPilot does it |
|---|---|---|
| **One file downloaded in NetPilot** | **Yes.** Different parts of the file come over each network at once. | **Fast download** (Connection tab, or share a link to NetPilot) |
| **Many connections at once** (web pages load from 20–100 connections; app updates; several apps) | **Yes, in total.** Each connection goes over Wi-Fi *or* mobile data, so the totals add up. | **Use both together** (Home, experimental) |
| **One single connection** (one video stream, a video call, one big download in another app) | **No.** One connection always uses one network. | Not possible without a server in the middle (Speedify-style). See Phase 4. |

This is called **per-connection load balancing**. It's real, it needs no server, and it's what NetPilot now does.
It is not "bonding" and the app never says it is.

### A2. How "Use both together" works

```
 app ─▶ TUN (VPN) ─▶ NetPilot FlowEngine ─┬─▶ socket bound to Wi-Fi ─────▶ server A
                                         └─▶ socket bound to mobile data ─▶ server B
        (each app connection ends in NetPilot and is re-opened on the chosen network)
```

- **Choosing the network** (`SpreadPolicy`, [V] 5 unit tests): each new *server address* is given to one network by
  smooth weighted round-robin, weighted by the last speed test (or Android's own estimate). With Wi-Fi at 2× the speed
  of mobile data, Wi-Fi gets 2 of every 3 new servers.
- **Sticky per server for 10 minutes**: all connections to one server use the same network, so sites that tie a login
  to your IP address (banks, some shops) keep working.
- **Failover per connection**: if a connection fails on one network, it's retried once on the other.
- **Local devices stay local**: the VPN only takes public internet addresses (46 IPv4 routes + `2000::/3`). Printers,
  casting, your router and multicast discovery keep going straight to Wi-Fi ([V] `RoutesTest`, and on the device:
  `10.0.2.2` reached directly with spread mode on).
- **Data honesty**: NetPilot tells Android which networks carry the traffic (`setUnderlyingNetworks`), so the VPN counts as
  **metered** whenever mobile data is used, and apps hold back big automatic downloads just as on mobile data.
- **Ad blocking still applies**: every DNS lookup (UDP port 53 to any server) is still checked by the filter ([V] device).
- **Not forwarded**: ICMP (ping), IP fragments and IPv6 extension headers. The confirmation dialog says ping won't work.
- **Turning it on** shows a plain explanation first (faster for many connections, not for one; uses mobile data and
  battery) and only then switches it on ([V] Robolectric `usingBothAsksFirst`).

### A3. The engine (`FlowEngine`, new, about 460 lines, pure Kotlin)

A user-space TCP/UDP forwarder: it acts as the server for the app's TCP connection and as the client towards the real
server. It's deliberately small: in-order delivery (the TUN link is local and doesn't reorder), cumulative ACKs, window
scaling, go-back retransmission on a 300 ms–4 s timer, half-close, RST handling. It never reads or changes the data.

**Tested against a real TCP stack** ([V]): a Linux TUN device in the test container, with the Linux kernel playing the
"app" and curl driving traffic through the engine to local servers.

| Test (Linux kernel as the app) | Result |
|---|---|
| 200 MB download, SHA-256 compared | **Identical**, 219 MB/s |
| 100 MB random upload, SHA-256 compared by the server | **Identical** |
| 100 parallel downloads to 9 servers | **100/100** complete; split across two simulated networks in a 2:1 ratio |
| Keep-alive: 3 requests on one connection | Pass |
| Connection to a closed port | Refused in **3 ms** (not a timeout) |
| UDP echo (14 B and 8 KB) | Pass |
| Apps abandoning downloads mid-transfer | All sessions cleaned up (0 left) |
| Engine CPU cost | **≈1.8–2.0 CPU-seconds per GB** on one server core, including the test bridge's I/O |

Unit tests ([V]): IPv6 handshake/echo/close with checksum verification on every packet, refused connect → RST, stray
segment → RST, and **100,000 random packets** into the engine without an exception.

**On Android** (Android 11 VM, one wired network, [V]):

| Check | Result |
|---|---|
| 50 MB download through the engine | Complete |
| 20 MB download, SHA-256 | **Identical** |
| 20 MB upload, SHA-256 | **Identical** |
| UDP echo | Pass |
| Closed port | Refused in 0.2 s |
| Local network (10.0.2.2) with spread on | Reached directly, outside the VPN |
| Ads still blocked (`doubleclick.net`) | Blocked |
| Crashes | 0 |

**Speed on the VM, measured, and the main open question.** The same 50 MB download ran at **150 Mbps** without
the engine and **14–30 Mbps per download** through it (5 runs; about 30 Mbps in total with 4 at once). The VM's CPU is
emulated in software, and during the test 2.25 of its 4 cores were idle, so one forwarding thread was the limit. On a
real server core the engine forwards about 4 Gbit/s ([V], above). A phone sits somewhere between the two, and **this
has not been measured on a real phone.** If it turns out to be the bottleneck on your phone, *Use both together* would
make single downloads slower, not faster. That is why it is off by default and labelled experimental, and why the
speed test and the live per-network figures on Home are there to check it.

### A4. What was not verified (and why)

- **Two real networks at once on a phone.** The VM has one network, so the Wi-Fi/mobile split was verified with two
  simulated networks in the Linux harness and in unit tests, **not** with real Wi-Fi + 4G. Please test on your phone:
  run the speed test, turn on *Use both together*, and watch the Wi-Fi/mobile split on Home.
- **Phone CPU cost.** On the VM's emulated CPU, the engine is much slower than direct traffic (above). A modern phone
  CPU is many times faster than the emulator [M], but on a slow phone the engine itself could limit speed. If downloads
  get slower with it on, turn it off: that's why it's labelled experimental.
- **The speed test against Cloudflare** couldn't be run here: this sandbox blocks `speed.cloudflare.com`. The
  measurement logic is unit-tested (warm-up handling, median ping, result storage); the network calls are not.

### A5. Fast download

- Paste a link in **Connection › Fast download**, or **share** a link to NetPilot from any app (it's placed in the box;
  nothing starts until you tap *Download*, [V] Robolectric).
- Downloads different 1 MB parts over each network (3 at a time per network), with **end-game mode** from Phase 4 so a
  slow network never makes it slower than the fast one alone ([M] model in `ChunkSchedulerTest`).
- Integrity: every part is requested with `If-Range`, so a file that changes on the server mid-download is rejected
  rather than stitched together. Saved to **Downloads › NetPilot** via MediaStore (no storage permission).
- Servers that don't allow partial downloads: falls back to a normal download over one network and says so.
- Runs as a foreground service with progress and *Cancel* in the notification; file names are sanitised
  ([V] `fileNamesAreSafe`, e.g. `%2F..%2Fetc%2Fpasswd` → `_.._etc_passwd`).

### A6. Speed test

- Measures **Wi-Fi alone, mobile data alone, then both at once**: download (4 streams, 7 s), upload (2 streams, 6 s),
  and response time (median of 5 small requests on a warm connection). The first second of each transfer is ignored,
  because TCP starts slowly ([V] `SpeedTest`).
- Shows the gain of *both at once* over the faster network alone, for many connections at once.
- Mobile data is capped per step (40 MB down, 15 MB up, 40 MB for "both"), about **100 MB at most**, and NetPilot
  asks before using it.
- Results are kept on the phone (last 10) and set the Wi-Fi/mobile weights for *Use both together*.
- Server: `speed.cloudflare.com` over HTTPS. Cloudflare sees your IP address, as any website would. Only when you tap.

---

## Part B: Security audit

### B1. Threat model

| Who | Can | Main defences |
|---|---|---|
| Hostile Wi-Fi / on-path attacker | See and change plain traffic, block DoH, spoof DNS | DoH with system CAs only; answers must match the question; plain-DNS fallback is visible and can be turned off |
| Another app on the phone | Send intents to exported components, open many connections | Only the launcher activity is exported to apps; resource caps in the engine |
| A compromised or broken block list | Block too much, or too little | HTTPS + system CAs, size cap, minimum size, shrink guard, never-block set, user allow wins (Phase 5) |
| A malicious download server | Send huge, changing or odd files | HTTPS only, free-space check, If-Range consistency, sanitised names, saved via MediaStore |
| A malicious shared link | Start downloads | Shared links only fill the box; the user taps *Download* |

### B2. Findings

| # | Severity | Finding | Status |
|---|---|---|---|
| F1 | **Medium** | DNS answers weren't checked against the question, so a wrong or spoofed reply could be cached under another name. | **Fixed**: `Dns.answers()` requires QR=1 and the same name (case-insensitive), type and class, for both DoH and plain DNS ([V] `answersOnlyItsOwnQuestion`). |
| F2 | **Medium** | Plain-DNS fallback accepted replies from any address, matched by ID only. | **Fixed**: the socket is connected to the server it asked, so the OS drops replies from anyone else. |
| F3 | **Medium** | Silent downgrade: blocking DoH makes lookups fall back to plain DNS, shown only inside the app. | **Mitigated**: a notification (at most hourly) explains it and how to switch fallback off. Fallback stays on by default because hotel and café sign-in pages need it. |
| F4 | **Medium** (new code) | The forwarding engine could be exhausted by one app opening thousands of connections (threads and buffers). | **Fixed**: 768 TCP / 256 UDP sessions max, 256 KB thread stacks, 256 KB upload + 512 KB in-flight per connection, **64 MB total** buffer budget. Beyond the cap, new connections get RST. |
| F5 | Low | The service woke the CPU every second even with the screen off. | **Fixed**: every 5 s when no NetPilot screen is open; engine timers 1 s when idle (100 ms only with open connections). |
| F6 | Low | In spread mode Android would think traffic was all on Wi-Fi (unmetered). | **Fixed**: `setUnderlyingNetworks` makes it metered when mobile data is used. |
| F7 | Low | A full tunnel would capture LAN and multicast traffic (casting, printers). | **Fixed by design**: only public address ranges are routed. |
| F8 | Low | Spread mode needed Android to have "validated" a network; where Android's check servers are blocked, everything would fail. | **Fixed**: falls back to connected, unvalidated networks (captive portals excluded). Found on the VM. |
| F9 | Low | In spread mode, Home's live speed and Wi-Fi total would count forwarded traffic twice (once on the tunnel, once on the real network). | **Fixed**: the bytes NetPilot forwarded are subtracted. |
| F10 | Info | The APK is signed with a throwaway debug key. | **Release signing added**: set `NETPILOT_KEYSTORE`, `NETPILOT_KEY_ALIAS`, `NETPILOT_KEYSTORE_PASS`; passwords are read by `apksigner` from the environment, never on a command line. Keep the key: updates must be signed with it. |
| F11 | Info | Blocklists are stored as 64-bit hashes; a collision could block an unrelated name. | Accepted: with ~1 million entries the chance for a given name is about 1 in 18 trillion [M]. A deliberate collision can only get the attacker's own name blocked. |
| F12 | Info | In spread mode Android's data-usage screen credits all forwarded traffic to NetPilot. | Documented (inherent to any VPN that forwards traffic). |

### B3. Controls re-checked (no change needed)

| Control | Status |
|---|---|
| Exported components | Launcher activity (shared links only fill a text box), VPN service (`BIND_VPN_SERVICE`, system only), Quick Settings tile (`BIND_QUICK_SETTINGS_TILE`). Fast download service not exported. |
| PendingIntents | All `FLAG_IMMUTABLE`. |
| Permissions | Internet, network state, change network state, foreground service (+ system-exempted, + data sync for Fast download), notifications. **No** location, contacts, storage, phone state, usage access or package-list access. |
| TLS | Cleartext denied except the captive-portal probe host; **system CAs only**, so a user-installed certificate can't intercept NetPilot. |
| Backups | `allowBackup=false`; cloud backup and device transfer exclude everything. |
| Debuggable | No (`run-as` refused on the device). |
| Logs | No domain names or URLs are logged. |
| Parsers | DNS, IP, UDP, TCP and list parsers survive **200,000** random inputs ([V] `FuzzTest`). |

---

## Part C: Privacy review

| Data | Stays on phone? | Leaves the phone to |
|---|---|---|
| Domain lookups | Checked on the phone | Your chosen DoH provider (or the network's DNS during fallback, with a notification) |
| Activity log (last 300 lookups) | Memory only, can be off | Nobody |
| Counters, settings, allow/block lists, speed results | App-private storage, no backup | Nobody |
| Block-list downloads | | GitHub (or your custom list host): your IP, about daily |
| Speed test | | Cloudflare: your IP, only when you tap |
| Fast download | | The server you chose, from both of your IP addresses (Wi-Fi and mobile) |
| Spread mode | Destinations are kept in memory for 10 minutes to keep each server on one network | Each website sees either your Wi-Fi or your mobile IP address |

No NetPilot server, account, analytics or crash upload exists.

---

## Part D: Performance

| Measure | Result | Notes |
|---|---|---|
| DNS filtering cost | Hash lookups per label; negligible | Phase 2 |
| Engine forwarding cost | ≈1.8–2.0 CPU-s per GB on one server core [V] | Phones are slower per core [M] |
| Engine memory | Capped at 64 MB of buffers in the worst case | Typical use is far lower |
| Wake-ups with screen off | 1 per 5 s (was 1 per s) | Spread engine: 10/s only while connections are open |
| App start on the VM | 9–11 s cold | The VM's CPU is software-emulated; not representative of a phone |
| Memory on the VM with spread on, after downloads | 93 MB PSS (Java heap 4.6 MB) | `dumpsys meminfo` |

Battery use can't be measured on the VM. Expect *Use both together* to cost noticeably more battery than protection
alone (every packet is copied by the app, and the mobile radio stays on) [M]. Protection alone remains light: only DNS
passes through NetPilot.

---

## Part E: Verification summary

| Suite | Result |
|---|---|
| Core unit tests (DNS, filtering, lists, scheduler, engine IPv6, spread policy, routes, speed maths, fuzzing) | **67 pass** |
| Robolectric UI (every tab in every state on Android 10/11/13/14/15, shared link, spread confirmation, file names) | **35 pass** |
| Linux TUN harness (real kernel TCP through the engine) | All pass (table A3) |
| Android 11 VM (spread mode on, real traffic) | All pass (table A3), 0 crashes |
