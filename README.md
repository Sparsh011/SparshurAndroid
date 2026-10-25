# Sparshur Android

An interactive, four-step Android lesson: inspect a Keystore key, encrypt sample
text, inspect/decrypt/tamper with the envelope, and compare local-network sends.
The explanations are built into the app and the laptop observer.

**The original Local key lesson does not implement phone-to-Mac E2EE.** The nonexportable AES key
stays in Android Keystore. The Mac observes ciphertext and cannot decrypt it.
Lesson 02 below adds QR pairing and recipient public-key encryption. The lessons
do not read SMS or the clipboard. The device-first app supports explicit clipboard sends, a Room-backed Primary
choice and local SMS rule previews; see below.
No background services, cloud services, accounts or message logs.

## Mac mini relay networking — started 2026-10-24

The complete [relay implementation plan](docs/RELAY_IMPLEMENTATION.md) separates
the Go relay on Mac mini from the receiving Mac app and covers pairing, live
routing, durable delivery, reverse clipboard replies, background work and SMS.
The current Android slice is **connection/authentication only**: debug Settings
→ **Mac mini relay**, on Android 10+. Configure a canonical
`wss://your-relay-host/bridge/v1/ws` with a valid TLS 1.3 certificate and explicitly
enroll the screen's existing public signing identity on the Go relay. Leaving the
screen stops it. It never sends clipboard/SMS or auto-connects on launch.

The selected first deployment is **private VPN (Tailscale/WireGuard)**. The VPN
is managed by the devices' VPN apps; Sparshur still uses WSS with a normally
validated certificate and enrolled-device authentication. Provider, hostname,
certificate setup and mini deployment are pending. No public relay ingress is
part of the first deployment.

The Go/Swift side must implement [the exact relay auth contract](spec/RELAY_V1.md)
and consume [the public auth vectors](spec/relay-auth-test-vectors.json). No live
relay is deployed by this Android change. “Authenticated to relay” is not a Mac
delivery receipt. The original local labs and current device sends keep their
existing behavior; relay payload routing waits for a new interoperable pairing
and envelope format. See [ADR 0005](docs/adr/0005-mac-mini-relay.md).

## Android app — Device first

The app now opens **Devices**, using the native Mac’s charcoal surfaces and pink
accent. **Learn** contains the offline field guide and preserved hands-on crypto
labs; **Settings** contains pairing, widget setup and connection details.

- Tap a device (in the app or widget) to send the text clipboard without a Paste
  or second Send action. A focused transfer activity performs one explicit read.
  Sensitive, non-text, empty and oversized clips are blocked.
- The saved **Paired Mac** uses the existing debug v2 encrypted transport and
  signed receipt verification. Only the actual saved pairing appears in the app
  and widget. An unpaired installation shows setup, with no sample recipients or
  simulated sends. A saved pair is not a live connectivity observation.
- Tap a device’s star to make it **Primary**. The choice is stored in Room,
  survives restarts, and is marked first in the widget. It never changes SMS rules.
- Open a device’s SMS footer for Off / Selected / All, removable sender chips,
  optional keyword chips and an optional local rule test using your own input.
  Save rules to persist them; unsaved changes require an explicit discard.
  Actual SMS capture/forwarding remains future work.
- Learn has thirteen offline lessons with interactive conceptual exercises,
  flow explanations, code references and access to the original real labs.

[App guide and code map](docs/ANDROID_APP.md) ·
[Room decision](docs/adr/0004-primary-device-room.md) ·
[Future SMS integration contract](docs/DEVICES_AND_SMS.md)

The project folder is **SparshurAndroid**, Gradle project and launcher name are
**Sparshur**, and the Android application ID is **com.sparsh.sparshur**. The
corrected ID creates a separate Android app sandbox. Pair this installation
again; the previous installation and its Keystore keys are left untouched.
The original nonexportable keys are never exported to migrate the name.

## Lesson 02 — pair and decrypt on Mac

The native Mac app is implemented in the sibling `Sparshur` checkout. Its README
and `docs/VERIFICATION.md` describe native operation and verified Keychain
migration and remaining physical-device checks. [MAC_APP_PLAN.md](docs/MAC_APP_PLAN.md) records the
agreed plan. The Python dashboard remains an explicit reference path.

Open **Learn → Open hands-on labs** for these original experiments. The
**Local key lab** remains beside **Pair & decrypt** inside that screen. Full code-reading order and flow diagrams
are in [docs/FLOW.md](docs/FLOW.md).

1. Run the updated debug app on your phone. Join its hotspot from the Mac.
2. Run the sibling native **Sparshur** Xcode app (macOS 14+). Quit other recipients.
   If needed, use **Authorize existing identity** to grant macOS Keychain access.
   No Python receiver/helper is used by the native app.
3. In **Pair phone**, select the Mac's hotspot-facing IPv4 and create a five-minute
   QR. For emulator testing select the separately labeled `10.0.2.2` address.
4. In Android's **Pair & decrypt**, tap **Scan Mac pairing QR**. Compare the
   fingerprint with the Mac, then tap **Pair with this Mac**. Google Play services
   may need to download its scanner module on first use. If unavailable, privately
   transfer the Mac's manual bundle, paste it and tap **Review pairing bundle**.
   The QR/bundle contains a one-time secret: do not share it or fetch it over LAN.
5. Enter sample text, tap **Encrypt for paired Mac**, then **Send to Mac & verify
   receipt**. The native **Messages** screen displays verified decrypted text. The network
   receives ciphertext and a wrapped content key; the HTTP response contains only
   a signed receipt. Android checks that receipt before reporting decryption.
6. Send the same envelope again to see duplicate suppression. **Flip one byte &
   send a copy** demonstrates rejection; it leaves the original envelope unchanged.

Pairing, Mac identity and replay state survive receiver restarts in Keychain.
Android's signing identity is encrypted at rest under a separate Keystore key;
its private seed is software material in app memory during signing. Mac's
Curve25519 keys similarly enter the Swift helper's memory after Keychain access.
The original nonexportable AES key is never exported or shared with the Mac.
If macOS refuses Keychain access, unlock your login Keychain and rerun; a rebuilt
helper may require a system access prompt for its own item.

One Android sender per Mac is supported. A completed new pairing replaces the
old phone. Creating a QR alone does not. Reconnect/IP changes can be handled by
editing the send destination; pinned keys never come from that address. After
restarting the app it restores the QR's original address, so re-enter a changed
address if necessary. Run one receiver process per Mac. Do not manually edit its
Keychain state or the Android counter. The strict receive counter rejects late
out-of-order messages. This is a local learning protocol requiring human review,
with no forward secrecy, production relay, group membership or offline queue.

[Paired protocol](spec/PAIRED_V2.md) · [Design decision](docs/adr/0002-paired-mac.md)

## Original lesson 01 — run on your phone and Mac

1. Open this project in Android Studio, choose a phone or emulator, and run the
   **debug** app. A real phone needs Developer options / USB debugging enabled
   and its USB authorization prompt accepted.
2. Turn on your phone's hotspot and connect the Mac to it. They share a network,
   not one IP address. Some hotspot/OEM configurations restrict peer traffic.
3. From this project folder, start the Mac observer:

   ```sh
   python3 tools/laptop_receiver.py
   ```

   Python 3.9+ is sufficient. No receiver packages or server accounts required.
   It listens on port **8787** until Ctrl+C. Use `--port` to change it if needed.
   Allow Python's local-network / incoming-connection prompt if macOS asks.
4. On the Mac open **http://127.0.0.1:8787**. The dashboard is readable only on
   the Mac's loopback interface, even though the receiving endpoint accepts LAN
   messages. Leave the terminal running.
5. In Android, follow **Key → Encrypt → Inspect → Send**. Step 03 can decrypt and
   reject a deliberately changed byte locally.
6. On Send, enter a **Mac IPv4 address** printed by the receiver, port **8787**.
   Choose the address belonging to the hotspot connection. Do not enter 0.0.0.0,
   your phone's address, the dashboard's 127.0.0.1, or your public mobile-data IP.
   The address can change when switching networks; restart the receiver to print
   current candidates. Android 17+ asks for Nearby devices / local network access.
7. Tap **Check connection**, then **Send encrypted envelope** and **Send readable
   demo text**. Watch the two dashboard columns. Use invented sample text only.
   Sending the same encrypted envelope again demonstrates duplicate suppression.

The encrypted button sends the snapshot created in Encrypt; the readable button
sends the current text field. Changing the input does not change an old envelope.
Message display data stays in memory. Rotating retains the lesson; killing the
app clears its message. The key persists until uninstall / key loss. Restarting
the receiver clears its observations and dedup state (40 visible / 1000 IDs).

### Connection alternatives

- **Android emulator:** use Mac address `10.0.2.2`, port 8787.
- **USB fallback:** with an authorized device connected, run
  `adb reverse tcp:8787 tcp:8787`, then use `127.0.0.1` in Android. This tests USB
  forwarding, not hotspot connectivity. If `adb` is not on PATH, use
  `~/Library/Android/sdk/platform-tools/adb` on this Mac.
- If it times out: confirm both network connections, the current Mac IPv4,
  firewall/local-network permission, and that the receiver terminal is running.
  Do not disable your firewall; allow the receiver specifically if needed.

## What is implemented

- AES-256-GCM, 128-bit tag, 96-bit random IV generated by AndroidKeyStore.
- Nonexportable key, actual hardware protection reporting, no per-use biometric
  prompt, no silent key replacement, app backup/device transfer excluded.
- Authenticated version/sender/recipient/type/counter/message ID. Durable counter
  reservation and a 10,000-encryption limit bound random-nonce key usage.
- Explicit readable-message comparison and private-IPv4-only HTTP transport in
  **debug** builds. The release manifest blocks cleartext; release UI/client
  disable network actions. This is a limited local demo, not the production relay.
- No content/key logging. The receiver has no key, no cryptographic tag check,
  and no sender authentication. It validates the envelope's structure only.

Read [the lab protocol](spec/PROTOCOL.md), [limits](spec/THREAT_MODEL.md),
[test vectors](spec/test-vectors.json), and [design decision](docs/adr/0001-keystore-learning-lab.md).
Crypto design requires human review before production reuse.

## Code map

| File | Responsibility |
| --- | --- |
| `lab/crypto/KeystoreCipher.kt` | Key lifecycle, counter reservation, AES-GCM |
| `lab/crypto/LabEnvelope.kt` | Validated metadata and exact associated data |
| `lab/network/LabWire.kt` | Separate encrypted/readable JSON formats |
| `lab/network/LocalEndpoint.kt`, `LabClient.kt` | Local destination validation, HTTP and receipts |
| `lab/LabViewModel.kt` | Background work and in-memory lesson state |
| `lab/LabScreen.kt` | Interactive lessons and visualization |
| `tools/laptop_receiver.py`, `tools/receiver_ui/` | Mac observer and dashboard |

Android paths above are under `app/src/main/java/com/sparsh/sparshur/`.
Lesson 01 used no added runtime dependencies. Lesson 02 adds Tink Android 1.24.0
and Google code scanner 16.1.0; Fragment 1.8.9 is a compatibility constraint for
the scanner’s Activity Result APIs. Existing AndroidX test dependencies
were updated to JUnit extension 1.3.0 / Espresso 3.7.0 for Android 17 compatibility.

## Code-review-graph

Installed in `.tools/venv` at version **2.3.9**. Its local index is in
`.code-review-graph/`. Both directories are ignored. Use:

```sh
sh tools/crg build
sh tools/crg search KeystoreCipher --limit 5
sh tools/crg query file_summary app/src/main/java/com/sparsh/sparshur/lab/crypto/KeystoreCipher.kt
```

The project's AGENTS.md tells future coding sessions to use the graph for
compact navigation. No global MCP settings or cloud embedding provider were
changed. This graph helps navigation; it does not audit cryptography. Git-based
change analysis needs a Git repository; this starter folder currently has none.
To reinstall after moving/cloning, run `sh tools/setup_review_graph.sh python3`
with Python 3.10+. Do not copy the virtual environment between machines.

## Verification

```sh
python3 -m unittest discover -s tools -p 'test_*.py' -v
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

For the optional actual Android→Mac integration test, start the receiver first,
then run against the emulator:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.receiverHost=10.0.2.2
```

The project uses Android Studio's JBR 25, Gradle 9.6 and Android SDK 37 as
specified by the starter. Set JAVA_HOME to Android Studio's JBR if your shell
has no Java runtime. Build/test results for this change are in
[docs/VERIFICATION.md](docs/VERIFICATION.md) for the original lesson and
[docs/VERIFICATION_V2.md](docs/VERIFICATION_V2.md) for paired delivery.

## Paired lesson verification

Build and start the receiver, then run:

```sh
sh tools/mac_endpoint/build.sh
.tools/mac-endpoint --self-test
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 tools/test_paired_android.py
# Reference dashboard proof only: python3 tools/test_paired_android.py --reference
```

The last command targets the Android emulator. It installs the debug APKs,
creates a fresh one-time pairing through loopback, transfers its secret over
trusted adb stdin (not the network), runs the actual crypto/UI delivery tests,
and checks the real Keychain replay state from a fresh Swift process. It
replaces the Mac's previously paired phone; run it only as a deliberate local
test. It never prints the bundle or saves secrets in the repository.

The fixed keys in `spec/paired-test-vectors.json` are PUBLIC TEST INPUTS ONLY,
generated by the helper's isolated `--vectors` mode. That mode never accesses
Keychain. Kotlin tests check Swift HPKE output, signing, HMAC proofs and receipts;
real delivery tests check the reverse direction, Android→Swift.

### Native integration default

Build the sibling native Debug app and Android debug/androidTest APKs, boot the
emulator and quit all recipients before `python3 tools/test_paired_android.py`.
The harness starts the signed native app itself. `--memory-only` explicitly uses
a temporary identity; `--authorize-keychain` permits the owner-facing system prompt.
Real mode pairs the emulator using the existing Mac identity, then restarts the
native process to check persisted replay state and Android's signed duplicate receipt.
This changes the active phone/pairing epoch. No secret bundle is printed or passed
in argv. See the sibling `Sparshur/docs/VERIFICATION.md` for actual evidence.

For the reference only, start `python3 tools/laptop_receiver.py` and open its
loopback dashboard, then use `--reference`. Both recipients compile the same Swift
core and guard identity ownership with an exclusive process-lifetime bind on 8786.
Pause is insufficient to release ownership: quit before switching receivers.
