# Sparshur Android: Devices, Learn and Settings

The app follows the approved Device first proposal: compact device cards,
rectangular controls, slim navigation, and the native Mac palette (charcoal
surfaces, gray text and #F72681). Settings uses grouped rows. Pairing and SMS
rules have dedicated screens. Original encryption lessons remain under
**Learn → Open hands-on labs**.

## Daily flows

- The app and widget list only the saved authenticated Mac pairing. With no
  saved pairing, they show setup instructions. There are no sample recipients,
  invented connection states or simulated delivery confirmations. The current
  protocol supports one Mac, not multi-peer discovery.
- Tap the device to send the current text clipboard without a Paste or second
  Send action. Android briefly focuses the transfer activity for one explicit
  read. Empty, non-text, sensitive and >4096 UTF-8-byte clips are blocked.
- Actual delivery uses the existing debug v2 sender and signed receipt checks.
  A saved pairing is labelled **Paired**; it does not imply current reachability.
  Release sending remains disabled because the production transport is unfinished.
- Tap the star to make the device **Primary**. Room stores one selected ID.
  It survives restarts; the widget labels it and lists it first. Settings can
  clear it. The choice never changes SMS rules or reroutes stale widget actions.
- The device’s **SMS sharing** footer opens Off / Selected / All SMS. Selected
  supports removable exact-sender chips and optional literal keyword chips.
  **Test these rules** starts empty and evaluates only input you provide locally.
  **Save rules** persists asynchronously and reports failures. Navigation asks
  before discarding changed rules.
- Sender OR keyword matching allows an SMS. New/missing/invalid policies block
  SMS. Rules are device-specific and default Off. Actual SMS capture, forwarding
  and work-laptop onboarding are not implemented; the editor states this.
- Settings includes widget pinning, pairing, Primary clearing, and editable local
  connection details. Updating the address preserves authenticated keys/counters.

Clipboard and rule-test message content remain in memory; they are never written
to Room, settings, logs, saved-state bundles or the widget. Existing backup and
device-transfer exclusions cover Primary and rule metadata.

## Learn

Thirteen bundled offline lessons cover Keystore/AES, nonce/tag/AAD, QR pairing,
HPKE/content encryption, signatures/receipts, wrapped identities and Keychain,
retries/replays, local networking, Android clipboard restrictions, SMS routing,
Compose/SwiftUI state and lifetime, concurrency and end-to-end tracing, and Room.
Search finds topics and code references. Lessons contain numbered explanations,
conceptual exercises and expandable code references. Conceptual outcomes are
labelled; the preserved labs run the actual cryptographic operations.

## Corrected name

- Checkout: `SparshurAndroid`; Gradle project and launcher: `Sparshur`.
- Namespace/application ID: `com.sparsh.sparshur`.
- Kotlin packages, exported Room schema directory, themes, widget intents,
  instrumentation runners and integration tools use the corrected name.
- The new application ID creates a separate Android app sandbox. Pair the
  corrected installation again. The previous installation is not uninstalled,
  and its nonexportable Keystore keys are not copied or exported.
- Reopen `SparshurAndroid` in Android Studio after the folder move.

## Implementation map

- `MainActivity.kt`, `ui/AppScreen.kt`: app shell, pairing, settings and navigation.
- `ui/AppComponents.kt`, `ui/theme/`: shared visual controls and Mac tokens.
- `devices/ConnectedDevices.kt`: real saved pairing through a replaceable source.
- `devices/DeviceDatabase.kt`, `app/schemas/`: Primary Room entity, DAO and schema.
- `devices/DevicesViewModel.kt`: asynchronous settings, saved rules and drafts.
- `devices/ClipboardText.kt`: explicit text validation without URI resolution.
- `devices/DevicesActivity.kt`, `TransferViewModel.kt`: one-read clipboard flow,
  verified delivery and exact-envelope retry.
- `devices/DevicesScreen.kt`: SMS editor and optional local rule test.
- `ui/learn/`, `lab/LabActivity.kt`: offline guide and preserved experiments.
- `widget/DevicesWidgetProvider.kt`: background Room reads and immutable,
  destination-specific actions; never reads the clipboard.

Kotlin paths begin at `app/src/main/java/com/sparsh/sparshur/`. The v1/v2 wire
contracts and crypto primitives are unchanged. See ADR 0004 and
DEVICES_AND_SMS.md for the future SMS transport contract.

## Verification — 2026-10-24

- Debug APK, Android-test APK, lint and all **18 JVM tests** passed.
- **17 selected Android instrumentation tests** passed on Pixel_10a / Android 17
  using AndroidJUnitRunner through `adb -s emulator-5554`. They cover the real
  empty catalogue, activity recreation, Room close/reopen and Primary isolation,
  SMS input/save/discard, local rule testing, interactive guide/code references,
  clipboard validation, stale widget destinations, minimum widget size and the
  original Keystore/tamper UI labs.
- All **11 Python receiver tests** passed. No wire format changed.
- A clean build, lint and JVM tests passed again from the renamed checkout.
  The project-local code-review-graph was rebuilt with the corrected paths.
- Gradle’s connected check passed all five `DevicesFeatureTest` cases. The
  complete 17-test suite was run directly because the Gradle class-list
  property selected only the first class. Lint has zero errors; its remaining
  warnings include dependency updates and existing style suggestions.
- Only emulator-5554 was installed/used. Physical-phone delivery and physical
  launcher pinning were not retested. Optional receiver/pairing/restart fixtures
  were not recreated; no existing Mac pairing was replaced for these UI checks.

Actual unpaired app captures: [Devices](screenshots/sparshur-devices.png),
[Learn](screenshots/sparshur-learn.png), [Settings](screenshots/sparshur-settings.png).

[Device-card layout](screenshots/paired-layout-test.png) and
[SMS editor](screenshots/sms-layout-test.png) are **isolated test renderings**.
Their explicitly named layout fixtures exist only in the test APK, never in the
app catalogue, PairingStore or receiver. They are not evidence of paired devices
or live messages. Older screenshots from the initial mock implementation have
been removed.
