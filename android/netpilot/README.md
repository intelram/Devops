# NetPilot: Wi-Fi/cellular failover and diagnostics for Android (no server)

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

Min SDK 29 (Android 10). Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`,
`FOREGROUND_SERVICE(_SPECIAL_USE)`, `POST_NOTIFICATIONS`.
