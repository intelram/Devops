# NetPilot: ad and tracker blocking plus Wi-Fi/mobile-data monitoring for Android (no server)

**Phase 3 (UX):** [`docs/PHASE3_UX.md`](docs/PHASE3_UX.md) has the screen-by-screen user journey with screenshots from a real Android 11 system, the accessibility results, and how each brief item was delivered.

**What's new in 0.2:** [`docs/V0.2_PROTECTION_AND_UI.md`](docs/V0.2_PROTECTION_AND_UI.md) covers the on-phone protection VPN, the simplified four-tab interface, and what is and isn't verified.

**Read [`docs/DESIGN.md`](docs/DESIGN.md) first.** It covers the feasibility analysis, architecture, plan, build and
signing, delivery limits, the test matrix and the acceptance criteria.

**This is failover and network management, not bandwidth bonding.** On a stock, non-rooted phone with no
cooperating server, an app cannot combine Wi-Fi and cellular bandwidth for the device's traffic. The design doc
explains why. NetPilot does what Android allows:

- Monitors every Wi-Fi and cellular network at once, and can keep cellular up while Wi-Fi is the default.
- Probes each network separately (DNS → TCP → TLS/HTTPS → captive-portal HTTP) and explains faults.
- Steers **its own** traffic to the best network (`bindProcessToNetwork`), failing over fast and failing back slowly.
- Can download one file over **both** networks at once from any server that supports HTTP Range requests.
  That speeds up this app's download only.

```bash
./gradlew :core:test                        # policy/diagnosis/scheduler tests (pure JVM)
./gradlew :app:assembleDebug                # needs Android SDK 35 (local.properties: sdk.dir=…)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No access to Google's repositories (e.g. a locked-down CI box)? `tools/build-apk-no-agp.sh` builds a debug APK from
Maven Central and Ubuntu packages only (aapt, dx, zipalign, apksigner, Retrolambda), with pinned checksums. It
produces `build/no-agp/out/netpilot-debug.apk`, signed with a throwaway debug key. The script header lists what it needs.

Min SDK 29 (Android 10), target SDK 35. Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` (VPN), `POST_NOTIFICATIONS`.
