# Phase 4: Internet bonding, what's real and how to build it

**Short version.** NetPilot today does **not** bond your connections, and it says so in the app. On a normal
Android phone, true bonding (one download or video call using Wi-Fi *and* mobile data at the same time) is only
possible with a **server in the middle** that splits and rejoins the traffic. Speedify works this way. This document:

1. checks what the current code really does,
2. explains switching vs load balancing vs bonding in plain terms,
3. recommends an architecture for optional server-based bonding,
4. lists the costs in latency, data, battery and money, and
5. sets acceptance criteria that never promise "both speeds added together".

Evidence labels: **[V]** verified (code, measured, or primary source) · **[S]** secondary source · **[M]** model/estimate.

---

## 1. What NetPilot does today (verified)

| Part | What it does with traffic | Bonding? | Evidence |
|---|---|---|---|
| Protection VPN | Routes **only its own DNS addresses** (`addRoute(10.111.222.1/32)`, `fd00:6e70:696c::1/128`). Web, video and app traffic never enters the tunnel; Android sends it over its chosen network. | No | [V] `ShieldVpnService.establish()` |
| Failover controller | Monitors Wi-Fi and mobile data, picks the best one, and binds **NetPilot's own process** (`bindProcessToNetwork`). Other apps follow Android's choice. | No, switching | [V] `NetworkSelector`, `FailoverPolicy` |
| Keep mobile data ready | `requestNetwork(CELLULAR)` keeps the cellular radio up so Android can switch faster. | No | [V] `NetworkMonitor` |
| Download test | Fetches **different parts of one file over both networks at once** (HTTP Range), from any server that supports Range requests. | **Yes, for that one download inside NetPilot** | [V] `MultipathDownloader`, `ChunkScheduler` (8 tests) |

**Improvement made in this phase (0.2.3):** the download test could finish *later* than Wi-Fi alone when mobile data
was much slower, because the last chunk got stuck on the slow link. The scheduler now uses **end-game mode**: once
nothing is left to hand out, an idle link re-fetches the oldest unfinished chunk, and whichever copy finishes first
wins. The deterministic model in `ChunkSchedulerTest` drives the real scheduler [M]:

| 50 MB file | Fast link alone | Combined (before) | Combined (end-game) |
|---|---|---|---|
| Similar links: Wi-Fi 50 Mbps / 30 ms + 4G 30 Mbps / 60 ms | 9.50 s | 6.21 s | **6.08 s** (1.56× faster) |
| Unequal: Wi-Fi 100 / 20 ms + 4G 8 / 80 ms | 5.00 s | 5.40 s ❌ slower | **4.60 s** |
| Very unequal: Wi-Fi 200 / 15 ms + 3G 2 / 150 ms | 2.75 s | 4.15 s ❌ 51 % slower | **2.75 s** (never worse) |

The model assumes one request at a time per link with a round-trip before each chunk, so it's conservative. It still
shows the core lesson for all bonding: **a second, slower link can make things slower unless the scheduler is careful.**

---

## 2. Three things people call "bonding"

Picture two roads into town: Wi-Fi is a motorway, mobile data a country lane.

| Term | Analogy | What happens | Single download faster? | Needs a server? |
|---|---|---|---|---|
| **Switching / failover** | If the motorway is closed, take the lane | All traffic on one network; move when it fails | No | No (NetPilot 0.2 does this, for alerts and its own traffic) |
| **Load balancing** | Some cars take the motorway, others the lane | Each *connection* uses one network; different connections can use different ones | No, only many parallel downloads together | No for NetPilot's own traffic. For other apps it needs a full-traffic VPN (§3.3). |
| **True bonding** | One lorry's cargo split across both roads, re-packed at a depot in town | Packets of **one** connection travel over both networks and are put back in order at a server | Yes, up to roughly the sum of both, minus losses (§6) | **Yes, always** |

---

## 3. Why true bonding needs a server, and what Android allows

### 3.1 The server requirement
- Wi-Fi and mobile data have **different public IP addresses**. A website sees packets from two addresses as two
  different clients, so one connection can't simply arrive over both. [V]
- Packets that take different paths arrive **out of order**. Something must buffer and re-order them before TCP sees
  them, or TCP mistakes the reordering for loss and slows down. MLVPN's documentation warns that the bonded link takes
  on the latency of the *worst* link and needs reordering when mixing 4G with DSL. [S]
- The only party that can do this is one **both paths reach**: a server you control. Speedify's "Speed Servers"
  [S, Phase 2] and OpenMPTCProuter's VPS [V, Phase 2] exist for exactly this reason.

### 3.2 Protocol options for the server path

| Option | How it bonds | Maturity | Android client feasibility |
|---|---|---|---|
| **MPTCP** (RFC 8684) | TCP subflows per path; the server is the MPTCP peer | Standard; in Linux | **Not app-controllable.** Android headers define `IPPROTO_MPTCP` [S], but adding a second path needs the kernel path manager (`CAP_NET_ADMIN`) and per-network routing that apps can't configure. Would need a userspace MPTCP stack. |
| **Multipath QUIC** | QUIC connection with several paths | IETF draft-ietf-quic-multipath **-21 (March 2026)**, in RFC Editor processing, no RFC number yet [V] | Userspace, so feasible. Pair with **MASQUE CONNECT-IP** (RFC 9484) to carry all IP traffic. Best long-term standard. |
| **Multipath UDP tunnel** (Glorytun/MUD, MLVPN style) | Encrypted UDP packets spread over per-path sockets, re-ordered at the server | Proven designs; Glorytun appears unmaintained [S] | Feasible today: userspace UDP and `Network.bindSocket()` per path |
| **Redundant WireGuard** (Engarde style) | Every WireGuard packet sent over **all** links; the first copy wins | Simple, robust; no aggregation | Feasible today; WireGuard's anti-replay window drops duplicates |

### 3.3 Android constraints (from the Android VPN guide [V])
- **One VPN at a time**: bonding and ad blocking must live in the **same** `VpnService`. Bonding mode extends the existing protection service; it is not a second app.
- Bonding needs a **full tunnel** (`0.0.0.0/0`, `::/0`) instead of today's DNS-only routes. Everything then goes through
  NetPilot and the server, so the server's capacity and location matter.
- Tunnel sockets must be `protect()`ed and pinned with `Network.bindSocket()` to Wi-Fi or mobile data, and mobile data
  kept up with `requestNetwork(TRANSPORT_CELLULAR)`. All of these are already used in NetPilot.
- Permissions: nothing new beyond today's (`INTERNET`, `CHANGE_NETWORK_STATE`, VPN consent, foreground service type `systemExempted`).
- Google Play: a VPN to a remote server is allowed when VPN is the core function, with a declaration and **encryption to the endpoint required** [S, Phase 2].

---

## 4. Recommended architecture: "NetPilot Bond" (optional, server-based)

### 4.1 Overview

```
 Phone (NetPilot, one VpnService)                                   Bond server (your VPS)
 ┌──────────────────────────────────────────────┐                   ┌───────────────────────────────┐
 │ apps ─► TUN (full tunnel when Bond is on)     │                   │                               │
 │          │                                    │   Wi-Fi path      │ per-path receivers            │
 │          ├─ DNS? ─► blocklist check (on phone) │══ UDP/443, enc ══►│   │                           │
 │          │                                    │                   │   ▼                           │
 │          ▼                                    │   mobile path     │ decrypt ─► reorder buffer     │
 │  encrypt (Noise IK, ChaCha20-Poly1305)        │══ UDP/443, enc ══►│ (bounded, ≤ Δdelay+jitter)    │
 │          │                                    │                   │   │                           │
 │  scheduler: Backup │ Redundant │ Speed        │◄═ return traffic ═│   ▼                           │
 │          │                                    │  (same scheduler, │ NAT ─► Internet (stable IP)   │
 │  path monitor: RTT, loss, jitter per path     │   mirrored)       │                               │
 │  sockets: protect() + Network.bindSocket()    │                   │ health/keepalive per path     │
 └──────────────────────────────────────────────┘                   └───────────────────────────────┘
```

### 4.2 Components

| Component | Design | Why |
|---|---|---|
| **Tunnel crypto** | Noise IK handshake (as in WireGuard), ChaCha20-Poly1305 (fast on phones without AES hardware), per-device keys, the server's public key pinned by scanning a QR code at setup | Strong, small, audited design; no certificates or TLS interception; meets Play's encryption requirement |
| **Wire format** | UDP datagrams: `[path id][seq no][flags]` + encrypted payload. Port 443/UDP so it passes most networks. | Sequence numbers enable re-ordering and loss detection; path id enables per-path statistics |
| **Scheduler** (client uplink, server downlink) | Three modes, matching Speedify's well-tested set [S]: **Backup** (default: Wi-Fi only, mobile data only when Wi-Fi degrades), **Redundant** (each packet on both paths, first copy wins), **Speed** (packets spread by measured delivery rate, avoiding a path when its extra delay would cause head-of-line blocking, as BLEST/ECF-style schedulers do) | Speed mode alone is risky on unequal links: studies find aggregation over Wi-Fi + cellular "problematic" without delay-aware scheduling [S, BLEST] |
| **Reassembly** | Reorder buffer at the receiver, bounded by `max(path delay) − min(path delay) + jitter`, capped at about 80 ms. Packets are released in order, or on timeout. | Stops TCP from seeing reordering as loss, without adding unbounded delay |
| **Loss recovery** | Inner TCP handles its own retransmission. A detected loss on one path is retransmitted on the other. Optional FEC for real-time UDP (calls, games) in later versions. | Keeps the tunnel simple; the end-to-end protocol stays in charge |
| **Health monitoring** | Keepalive every 1 s per path; EWMA of RTT, jitter and loss; states *good / degraded / down*, with the existing `FailoverPolicy` hysteresis (fail fast, recover slowly) | Reuses tested code; avoids flapping |
| **Adaptive routing** | Per-flow hints: small interactive flows (DNS, calls, games) go on the lowest-latency path or redundant; bulk transfers in Speed mode | Latency-sensitive traffic shouldn't wait behind the slow path |
| **Failover** | The server holds the stable public IP, so when Wi-Fi drops, flows continue on mobile data **without reconnecting** | The biggest everyday benefit, even without aggregation |
| **Ad blocking** | Stays on the phone: DNS is filtered before anything enters the tunnel | One VPN slot, same protection, the server never needs the blocklists |
| **Server** | Small userspace daemon (Go or Rust) plus Linux NAT, IPv4 and IPv6, firewall, rate limits, no traffic logs by default; shipped as a container | One command to self-host |

### 4.3 Build vs reuse
- **Recommended path:** a WireGuard-compatible tunnel with a **multipath shim** (sequence numbers, scheduler,
  reorder buffer) on both ends. Start with Backup and Redundant modes, then add Speed. Move to
  **multipath QUIC + MASQUE** once the RFC is published and a maintained library supports it.
- **Not recommended as the base:** Glorytun (appears unmaintained) [S]; OpenMPTCProuter's server (needs an MPTCP client, which Android apps can't be).

---

## 5. Costs and side effects

| Factor | Backup mode | Redundant mode | Speed mode |
|---|---|---|---|
| Extra latency | + RTT to the server (about 5–40 ms with a nearby server) [M] | Same; often **lower jitter**, since the first copy wins | Same, **plus** up to the reorder bound (≤ about 80 ms) on unequal paths [M] |
| Mobile data used | Only when Wi-Fi degrades | **About 2×** traffic: everything also goes over mobile data | The mobile share of traffic, often 20–50 % [M] |
| Tunnel overhead | About 4–6 % per packet (headers and authentication tag) [M] | Same, on both paths | Same |
| Battery | One radio, plus encryption | **Both radios on all the time**, the highest drain | Both radios on |
| Throughput ceiling | Best single path | Best single path (it's for reliability) | Up to the paths' total, **minus** overhead, reordering and loss; capped by the server's bandwidth |
| Privacy | The server sees destinations and volumes (not content inside HTTPS) | Same | Same |

**Money (self-hosted)** [S]: a small EU VPS such as Hetzner CX22 costs about €4.49/month with **20 TB** of traffic
included, and about €1 per extra TB. That's plenty for one family. A **managed service** for many users adds
servers in several regions, accounts, abuse handling and support: an ongoing business, not an app feature.

---

## 6. Honest expectations (rules for the app and the store page)

1. **Never** show "Wi-Fi + mobile = combined speed". Show only **measured** throughput, for example "Bonded download
   today: 61 Mbps (Wi-Fi alone: 40 Mbps)".
2. Default to **Backup** mode. Speed and Redundant are opt-in and show their data and battery cost before switching on.
3. Data-cap guard: monthly mobile-data budget for bonding, automatic fallback to Backup when it's reached, and a notification.
4. When links are very unequal, Speed mode should **stop using the slow link** (as the end-game scheduler already
   does for downloads), and say so in plain words.
5. Typical results with similar links: **60–90 % of the sum** [M, consistent with the download model's 72–82 %].
   It can be no better than the best single link when the second link is poor.

---

## 7. Options compared

| Option | What the user gets | Server | Effort [M] | Running cost | Recommendation |
|---|---|---|---|---|---|
| **A. Current (0.2.3)** | Ad blocking, alerts, switching, in-app striped download | None | Done | €0 | Keep as the free core |
| **B. Self-hosted Bond, Backup + Redundant** | Calls and streams survive Wi-Fi drops; stable IP | User's VPS (QR setup) | about 6–10 engineer-weeks | about €5/month per household | **Next step if you want bonding** |
| **C. B + Speed mode** | Faster single downloads on similar links | Same | +6–8 weeks (scheduler, reordering, tuning) | Same, plus more mobile data | After B is stable in real use |
| **D. Managed service** | Bonding without running a server | NetPilot-run fleet | Months, plus operations | Servers, bandwidth, support, legal | Only as a business decision |
| **E. Multipath QUIC + MASQUE** | Standards-based B/C | Same | Re-platform of B/C | Same | Once the RFC and a maintained library exist |

---

## 8. Acceptance criteria and tests (for B/C)

| ID | Criterion | How to test |
|---|---|---|
| B1 | A Wi-Fi drop during a voice call or download **doesn't drop the session** (Backup/Redundant) | Pull the Wi-Fi AP during a call and a 1 GB download; count disconnects (target 0) |
| B2 | Redundant mode: no packet loss visible to apps when one path loses 10 % | `tc netem loss 10%` on one path; app-level loss ≈ 0 |
| B3 | Speed mode is **never slower** than the best single path (within 5 %) on any tested pair | Matrix of rate/RTT pairs, including 10:1 ratios; iperf3 single stream |
| B4 | Speed mode reaches ≥ 60 % of the sum when paths are within 2:1 | Same matrix |
| B5 | Added latency ≤ server RTT + 20 ms (Backup), ≤ server RTT + 80 ms (Speed, unequal paths) | Ping/RTT under load (bufferbloat test) |
| B6 | Mobile data use matches the mode: Backup about 0 while Wi-Fi is healthy; the cap is enforced | Android data-usage screen vs the NetPilot counter |
| B7 | Ad blocking keeps working in Bond mode; still one VPN | Blocked counter rises; `dumpsys connectivity` shows one VPN |
| B8 | Tunnel crypto: server key pinned, no plaintext on the wire, replayed packets rejected | Packet capture; replay test |
| B9 | Server down → automatic fall back to direct internet, with a clear message | Stop the server process |
| B10 | Battery: Backup within +5 % of protection-only over 8 h; Redundant/Speed shown with their measured cost | Device battery stats, A/B runs |

---

## 9. Decision needed

Bonding beyond what NetPilot already does **requires a server**, so it's your call:

1. **Stay serverless** (option A). Phase 4 then ends with the end-game improvement and this document. Bonding stays
   an honest "no" in the app.
2. **Build B: self-hosted Bond (Backup + Redundant)**, with Speed later. I'd start with the server daemon and the
   multipath shim, testable here on two emulated paths, then the Android side.
3. **A managed service** (D) is a business decision (servers, cost, privacy policy, support), not just an engineering one.

---

## Sources
- Android VPN guide (one VPN, `protect()`, per-app, always-on): https://developer.android.com/develop/connectivity/vpn
- MPTCP v1, RFC 8684: https://www.rfc-editor.org/rfc/rfc8684
- `IPPROTO_MPTCP` in Android kernel headers (secondary): https://github.com/rust-lang/libc/pull/3197 ; kernel commit: https://android-kvm.googlesource.com/linux/+/c74a39c861aeaf6b789b7abdbb3256f54c9fb365%5E
- Multipath QUIC draft -21 and history: https://datatracker.ietf.org/doc/html/draft-ietf-quic-multipath , https://datatracker.ietf.org/doc/draft-ietf-quic-multipath/history/
- MASQUE CONNECT-IP, RFC 9484: https://www.rfc-editor.org/rfc/rfc9484
- MLVPN (bonding, worst-link latency, reordering): https://mlvpn.readthedocs.io/en/latest/what_is_mlvpn.html
- Glorytun (multipath UDP, MUD): https://github.com/angt/glorytun ; maintenance note (secondary): https://gittrend.io/repo/angt/glorytun
- Engarde (redundant WireGuard over multiple links): https://github.com/porech/engarde
- BLEST scheduler, heterogeneous Wi-Fi/cellular aggregation: https://dl.ifip.org/db/conf/networking/networking2016/1570234725.pdf
- MPTCP scheduler/congestion study under path heterogeneity (2025): https://arxiv.org/abs/2511.14550
- Loss-aware MPTCP scheduling (IEEE TWC 2021): https://www.ece.uvic.ca/~cai/twc21-mptcp.pdf
- Hetzner traffic and pricing: https://docs.hetzner.com/robot/general/traffic/
- Speedify and OpenMPTCProuter: see `PHASE2_RESEARCH.md`
