# Phase 2: Competitors, platform rules and best practices

Scope: what NetPilot would need to become an easy "internet booster + ad blocker" for ordinary Android users.
Research date: October 2026. Web pages change, so re-check prices and store policies before relying on them.

**How to read the evidence labels**

| Label | Meaning |
|---|---|
| **[V]** | Verified against a primary source (official docs, the vendor's own documentation, or the project's own repository). Vendor *claims* about performance are still claims. |
| **[S]** | Secondary source: press review, third-party guide, or an older (pre-2025) source. Likely right, but not confirmed against a primary source. |
| **[O]** | Opinion or anecdote: user reviews, forum posts, review-site ratings, and my own difficulty estimates. |
| **[NP]** | NetPilot fact, verified by reading this repository's source. The code has **never been built or run**, so none of this is tested behaviour. |

---

## 1. Key findings in plain English

1. **Real bonding always needs a server in the middle.** Speedify and OpenMPTCProuter both join several connections
   by sending traffic to a server that puts the pieces back together. Speedify does it at the packet level, which
   is why it can help even a single large download. [V][S] An app with no server can only switch between
   connections, or spread *separate* connections across them. That is load balancing, not bonding.
2. **Android lets only one VPN run at a time.** "Starting a new service automatically stops an existing service."
   [V] An ad blocker that works across the whole phone without root needs that VPN slot (AdGuard: local VPN is
   "the only system-wide method on non-rooted devices"). [V] So does any app that wants to steer other apps'
   traffic. **The booster and the ad blocker must therefore be one VPN service, not two.**
3. **That combination already exists.** RethinkDNS bundles encrypted DNS, blocklists, a per-app firewall and
   VPN/proxy forwarding into one open-source app ("Rethink: DNS + Firewall + VPN"). [V][S] It's the closest
   open-source reference for NetPilot's target design.
4. **Google Play is the biggest business risk.** Play only allows the VpnService API when VPN is the app's core
   function, or for listed categories such as firewalls and device security. It bans using it to "manipulate ads
   that can impact apps monetization". [S, Play policy article 12564964 via search excerpts; the page itself
   could not be loaded from this environment.] Third-party reviews say this is why the full AdGuard for Android
   is distributed from AdGuard's website instead of Play. [S]
5. **DNS blocking is cheap and private, but limited.** It works on whole domains, so it can't remove ads served
   from the same domain as the content. YouTube is the classic example. [S] It *can* catch "CNAME-cloaked"
   trackers if the resolver follows the alias chain, which AdGuard documents doing. [V]
6. **Honest statistics are possible, but only some.** "Blocked domain lookups" can be counted exactly. "Ads
   blocked" can't, because one blocked domain may be zero or many ads. Device-wide data use needs the user to
   grant usage access. By default an app can only see its own usage. [V]
7. **Bonding costs data and battery, and users notice.** Speedify's "Redundant" mode sends the same packets over
   every connection. [S] Speedify says the battery impact can be as low as 1 %, which is its own lab claim. [V-claim]
   It also explains that Android credits *all* traffic to the VPN app, so users see the VPN as the battery and data
   hog. [V] Review aggregators show users complaining about unexpected mobile-data use. [O]

---

## 2. Comparison table

Difficulty is my estimate for adding the feature to NetPilot [O]: **S** = days, **M** = 1–3 weeks,
**L** = 1–3 months, **XL** = months plus ongoing operations. "User impact" is a judgement of how much an ordinary
user would notice the feature [O].

### 2.1 Connectivity and bonding

| Capability | NetPilot today [NP] | Speedify | Open source | Gap | User impact | Difficulty |
|---|---|---|---|---|---|---|
| See Wi-Fi and mobile data status together | Yes (`NetworkMonitor`) | Yes, per-connection stats [S] | n/a | None | Medium | — |
| Keep mobile data ready while on Wi-Fi | Yes (`requestNetwork(CELLULAR)`) | Yes; mobile data can be "backup-only" [V] | n/a | No user-friendly control | Medium | S |
| Automatic failover | **Only for NetPilot's own traffic** (`bindProcessToNetwork`) | Device-wide, through its VPN [V] | OpenMPTCProuter: router-level failover [V] | Doesn't help other apps | **High** | L (needs VPN) |
| Spread separate connections over both links (load balancing) | No | Yes (part of bonding) | Consumer routers do per-flow balancing [O] | Missing | Medium; breaks some logins (§4) | L |
| **True bonding of one connection** | **No.** Only an in-app split-download test | **Yes**, packet-level through Speed Servers [V/S] | OMR via MPTCP to your own VPS [V] | Missing; impossible without a server | High when it works | **XL** (server fleet) |
| Redundant / "never drop" mode | No | Yes (Redundant mode) [S] | OMR failover [V] | Missing | High for calls and gaming | XL (same server) |
| Encryption of tunnelled traffic | n/a (no tunnel) | AES, or ChaCha on phones without AES hardware [V] | WireGuard/OpenVPN/Glorytun in OMR [V] | Needed if a server is added | Required by Play policy for VPNs [S] | M (use WireGuard/Noise) |
| Mobile-data caps | No | Daily/monthly caps [S] | — | Missing | **High** (bill shock) | S |
| Server locations | None | Auto-picks a server by latency [S] | Bring your own VPS | Depends on the server decision | — | XL |
| Price | Free | Free 2 GB/month; paid roughly $5–15/month depending on plan and date [S] | Free plus VPS cost | — | — | — |

### 2.2 Ad and tracker blocking

| Capability | NetPilot today [NP] | AdGuard for Android | Open source (RethinkDNS, personalDNSfilter, NetGuard, Blokada) | Gap | User impact | Difficulty |
|---|---|---|---|---|---|---|
| Device-wide DNS blocking without root | **No** | Yes, via local VPN [V] | Rethink, personalDNSfilter, Blokada [V/S] | **Missing entirely** | **High** | M |
| Ready-made filter lists | No | AdGuard DNS filter, about 50K rules, plus custom list URLs [S] | Rethink: about 190 lists (2024 figure) [S] | Missing | High | S–M |
| Automatic list updates | No | Yes [S] | Yes [S] | Missing | Medium | S |
| Custom allowlist and blocklist rules | No | User rules and allowlist [S] | Yes [S] | Missing | High (fixes broken sites) | S |
| Per-app exceptions | No | Choose which apps are filtered [V] | Rethink per-app rules [V]; NetGuard per-app allow/deny [V] | Missing | High | M |
| Request log and statistics | No | DNS request log [S] | Rethink connection tracker [V] | Missing | Medium; builds trust | M |
| CNAME-cloaked tracker blocking | No | Yes [V] | Possible if the resolver inspects CNAMEs [S] | Missing | Low–medium | M |
| Encrypted upstream DNS (DoH/DoT) | No; it only *diagnoses* Private DNS | Yes [S] | Rethink, Intra (DoH) [V/S] | Missing | Medium (privacy) | M |
| HTTPS filtering (cosmetic, in-page ads) | No | Yes, needs a CA certificate, installed manually on Android 11+ [V] | Not in the main open-source tools | **Don't add.** Breaks certificate validation; high risk | — | Not recommended |
| Same-domain ads (e.g. YouTube) | No | Only with HTTPS filtering [S] | Not possible with DNS [S] | Accept as a documented limit | Users will ask | — |
| Works next to another VPN | n/a | **No** in local VPN mode; workarounds use proxy modes [V] | Same single-slot rule [V] | Platform limit | Must be explained in the UI | — |
| Available on Google Play | n/a | Full app from website only [S] | Rethink on Play and F-Droid [S] | Distribution decision | High | Policy, not code |

### 2.3 Usability, battery and trust

| Capability | NetPilot today [NP] | Competitors | Gap | User impact | Difficulty |
|---|---|---|---|---|---|
| One big Connect button | No; Start/Stop plus a technical text dump | Standard in VPN apps [O] | Missing | **High** | S |
| Plain-language status ("using Wi-Fi", "switched to mobile data") | No; raw names like `PREFER_WIFI` and network IDs | Speedify shows per-connection graphs [S] | Missing | **High** | S |
| Live speed, latency, quality | Latency only, as raw ms | Speedify yes [S] | Missing | High | M |
| Data-use view | No | Speedify caps [S]; Android only shows device-wide use with user-granted usage access [V] | Missing | High | M |
| Battery guidance | `Diagnoser` flags background restriction | Speedify battery FAQ; Mullvad tells users to set battery to "Unrestricted" [V] | Partial | Medium | S |
| Onboarding and help | None | Common [O] | Missing | High | S–M |
| Accessibility | Not designed for it | Android guidance: 48×48 dp touch targets, 4.5:1 text contrast, content descriptions [V] | Missing | High for some users | S–M |
| Always-on VPN support | n/a | Android 7+ always-on and "Block connections without VPN" [V] | Needed once there is a VPN | Medium | S |
| Open source / auditable | Code is in this repo | Rethink, NetGuard, personalDNSfilter, Intra open [V/S]; Speedify and AdGuard Android closed [S] | — | Trust | — |

---

## 3. Competitor notes

### Speedify (bonding VPN)
- **How it works [V/S]:** the app connects to a "Speed Server", and client and server split and reassemble
  traffic at the packet level across Wi-Fi, mobile data, Ethernet and satellite. It's a VPN on Android.
- **Modes [S]:** *Speed* spreads traffic over all links. *Redundant* duplicates packets on every link. *Streaming*
  is the default, which speeds up normally and switches to redundant when the connection looks unstable.
- **Measured example [S]:** one Tom's Guide test went from about 27.7 Mbps (LTE only) to 74.2 Mbps (LTE plus home
  broadband). That's a single test, not a promise.
- **Costs and complaints:** a 2 GB/month free cap [S]; an aggregator's sampled reviews complain about mobile data
  use [O]; Android credits all battery and data to the VPN app [V].
- **Lesson for NetPilot:** true bonding = a server fleet plus encryption plus a usage-based business model. The UX
  ideas worth taking are per-connection status, mobile data as "backup only", and data caps.

### AdGuard for Android (closed source; reference for features only)
- **Local VPN mode [V]** is its only system-wide method without root, and can't run next to another VPN. Its
  workaround is AdGuard's own VPN integration, or a proxy mode.
- **HTTPS filtering [V]** needs a CA certificate in the user store, plus the system store for apps that only trust
  system CAs. Android 11+ needs manual install. Extended Validation (bank) sites are excluded by default.
- **Distribution [S]:** the full app comes from AdGuard's website; the Play version is a limited browser content
  blocker.
- **Lesson:** the feature set to match is lists, user rules, an allowlist, per-app control and a log. NetPilot
  should **not** copy its code, branding, list names or UI assets, and should leave out HTTPS interception (§5).

### Open-source references
| Project | What to learn | Licence / status |
|---|---|---|
| **RethinkDNS** (`celzero/rethink-app`) | One VPN service that combines DNS blocklists, encrypted DNS, a per-app firewall and proxy forwarding. Finds the app behind each connection via Android 10+ APIs. [V/S] | Open source; Apache-2.0 per a secondary source [S] |
| **NetGuard** (`M66B/NetGuard`) | Per-app allow/deny through a local VPN sinkhole, with no root. Per-address rules are a Pro feature. [V] | Open source |
| **personalDNSfilter** | Simple local-VPN DNS filter with hosts lists. [S] | GPL [S] |
| **Intra** (Jigsaw) | Encrypted DNS (DoH) over a local VPN, aimed at censorship. [S] | Apache-2.0 [S] |
| **Blokada 5** | DNS blocking; forum users report it isn't open source. [O] Some versions broke apps through its DNS. [O] | Unclear |
| **OpenMPTCProuter** | True bonding via MPTCP to your own VPS; the VPS's IP may be blocked by some services. [V/O] | Open source |

Licence check before reusing any code: GPL code (personalDNSfilter, likely NetGuard) would force NetPilot to be GPL.
Use these as references for behaviour, or confirm the licence and accept its terms.

---

## 4. Android platform facts that shape the design (all [V] from developer.android.com unless noted)

| Fact | Consequence for NetPilot |
|---|---|
| Only one VPN per user/profile, and starting one stops the other | Booster and ad blocker must share **one** `VpnService`. The UI must explain the conflict with other VPNs. |
| Call `VpnService.prepare()` every time; consent can be revoked | Handle "another app took the VPN" gracefully, with a clear message. |
| Per-app VPN: an allowed list *or* a disallowed list, set before `establish()`; changes need a new connection | "Exclude this app" requires a brief reconnect. Exclusions are the main tool for fixing broken apps. |
| `protect()` keeps the app's own sockets outside the tunnel | Needed for upstream DNS and for any server connection, to avoid routing loops. |
| Apps that bind their own network bypass the VPN unless lockdown is on; `allowBypass()` is fixed at start | Some apps escape filtering. Document it. |
| Always-on VPN (Android 7+), with an optional "block connections without VPN" | Support it. When always-on, hide the Disconnect button ("the system and Settings control the connection"). |
| A VPN must run in the foreground with a non-dismissible notification (Android 8+) | Make the notification useful: status, data saved, quick actions. |
| Device-wide data stats need usage access that the user grants in Settings; apps see their own usage by default | Data-use screen: show the app's own tunnel traffic by default, and the full device view only after opt-in. |
| Battery optimisation and OEM killers can stop VPNs (Mullvad documentation) [V-vendor] | Guided "set battery to Unrestricted" step during onboarding. |
| Accessibility: 48×48 dp targets, 4.5:1 contrast for small text, content descriptions, never rely on colour alone | Phase 3 design rules. |
| Private DNS (DoT) is system-wide; apps doing their own DNS should check `isPrivateDnsActive()` [V, 2018 blog] | How Private DNS interacts with a VPN's DNS is **unverified** here. Test it on devices (Phase 5). |

---

## 5. What this means for the next phases (direction only; design comes in Phases 3–5)

- **Unified service.** One `VpnService` that does DNS filtering for every user, plus optional
  routing/failover/bonding. RethinkDNS shows this is practical.
- **Two product tiers, labelled honestly:**
  - *Free, no server:* ad and tracker blocking, smarter switching (failover), and per-connection load balancing.
    Never called "bonding".
  - *Optional, server-based:* true bonding through a NetPilot-run or user-run server, with clear data and cost
    warnings.
- **Distribution decision is needed early:** Play (strict VpnService declaration and the ad-interference rule)
  versus website/F-Droid (what AdGuard does for its full app). This affects onboarding, updates and trust.
- **Leave out HTTPS interception:** installing a CA breaks the trust model, needs manual steps on Android 11+, and
  is out of proportion to DNS-level blocking for a mass-market app. Explain the YouTube-style limit instead.

---

## 6. Gaps in this research

- The live Google Play VpnService policy page couldn't be loaded from this environment (DNS failure). Policy
  details are from search excerpts of that page plus 2022 press coverage. Re-read the live page before submitting.
- Google Play user ratings for Speedify and AdGuard weren't retrieved. The user-complaint evidence is one aggregator [O].
- Speedify's current prices disagree between sources. Use its official pricing page.
- RethinkDNS's licence was taken from a secondary source. Confirm it in its repository's LICENSE file.
- How Private DNS behaves alongside a VPN on Android 14–16 isn't documented in what I found. Treat it as a device test item.

---

## Sources

**Android (primary)**
- Android VPN guide: https://developer.android.com/develop/connectivity/vpn
- VpnService reference: https://developer.android.com/reference/android/net/VpnService
- NetworkStatsManager reference: https://developer.android.com/reference/android/app/usage/NetworkStatsManager
- Accessibility guide: https://developer.android.com/guide/topics/ui/accessibility/apps
- Foreground service changes (Android 15 types): https://developer.android.com/about/versions/15/changes/foreground-service-types
- DNS over TLS in Android P (2018): https://android-developers.googleblog.com/2018/04/dns-over-tls-support-in-android-p.html
- Play Console help, VpnService policy: https://support.google.com/googleplay/android-developer/answer/12564964

**Speedify**
- How Speedify works: https://msupport.speedify.com/article/167-how-does-speedify-work
- Mobile VPN page: https://speedify.com/mobile-vpn/
- Battery: https://speedify.com/blog/speedify-and-your-battery/ and https://support.speedify.com/article/957-battery-use
- Privacy settings: https://support.speedify.com/article/312-privacy
- Tom's Guide reviews: https://www.tomsguide.com/reviews/speedify-review and https://tomsguide.com/us/speedify-vpn-free,review-5510.html
- TechRepublic review: https://www.techrepublic.com/article/speedify-vpn-review/
- Cloudwards review: https://www.cloudwards.net/speedify-review
- TechSpot listing: https://www.techspot.com/downloads/1643-befaster.html
- User reviews aggregator [O]: https://justuseapp.com/en/app/999025824/speedify/reviews

**AdGuard**
- Android settings KB: https://adguard.com/kb/adguard-for-android/features/settings/
- Compatibility issues KB: https://adguard.com/kb/adguard-for-android/solving-problems/compatibility-issues/
- What is DNS filtering: https://adguard.com/en/blog/what-is-dns-filtering.html
- Android features overview: https://adguard.com/en/blog/in-depth-review-adguard-for-android/
- CNAME tracker research: https://www.theregister.com/2021/03/04/adguard_cname_tracker/
- Distribution and pricing review [S]: https://adblock-tester.com/ad-blockers/?p=1317

**Play policy coverage (2022)**
- Bitdefender: https://www.bitdefender.com/blog/hotforsecurity/google-plans-to-bar-android-vpn-apps-from-blocking-or-interfering-with-ads/
- Blokada community: https://community.blokada.org/t/google-to-remove-all-vpn-ad-blockers-that-don-t-comply/27586
- Reclaim The Net: https://reclaimthenet.org/google-play-policy-could-restrict-vpns-that-block-invasive-ads/

**DNS filtering limits**
- Techlore primer: https://discuss.techlore.tech/t/dns-level-content-blocking-adblocking-a-primer/5455
- PortSwigger on CNAME cloaking: https://portswigger.net/daily-swig/web-trackers-using-cname-cloaking-to-bypass-browsers-ad-blockers

**Open source**
- RethinkDNS: https://bravedns.com/download, https://apt.izzysoft.de/fdroid/index/apk/com.celzero.bravedns
- NetGuard: https://github.com/M66B/NetGuard, https://netguard.me
- personalDNSfilter: https://f-droid.org/en/packages/dnsfilter.android
- Intra: https://pkg.go.dev/github.com/Jigsaw-Code/Intra, https://www.helpnetsecurity.com/2018/10/04/intra/
- OpenMPTCProuter: https://openmptcprouter.com/, https://github.com/digitalocean/marketplace-openmptcprouter
- Mullvad Android docs (battery): https://www.mintlify.com/mullvad/mullvadvpn-app/platforms/android
