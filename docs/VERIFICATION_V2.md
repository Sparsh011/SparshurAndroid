# Paired lesson verification — 20 October 2026

## Checks completed

- Debug and release APK builds, Android lint, Kotlin formatting and Swift formatting passed.
- 11 Kotlin JVM tests passed: original AES wire validation plus Swift/Tink HPKE interoperability, payload decryption, signatures, pairing proofs, signed receipts and malformed inputs.
- 11 Python tests passed: HTTP validation, loopback/same-origin QR access, separate local plaintext display versus ciphertext network responses, and original comparison routes.
- Native Swift self-tests passed: pairing and expiry, QR image encode/decode, HPKE, payload authentication, sender signatures, malformed fields, duplicates, restart and pairing replacement.
- 9 emulator instrumentation tests passed against the actual Mac recipient: Android Keystore persistence, original lesson, pairing, Mac decryption, signed receipts, tampered signatures/tags and Compose delivery UI.
- A fresh Swift process loaded the real Keychain state and suppressed an authenticated replay without disclosing plaintext again.
- code-review-graph was used and its project index refreshed.

After macOS Keychain access was approved, all nine emulator tests passed again against the final helper and app. The fresh-process Keychain replay check also passed; this historical result concerns the reference receiver, not the later native migration.

The emulator integration uses a fresh short-lived pairing delivered through trusted adb stdin. It deliberately replaces the Mac’s active phone pairing. Public fixed test keys in spec/paired-test-vectors.json belong only to an isolated memory-backed test endpoint. They never access the real Keychain.

## Practical limits

Physical-phone camera scanning has not been exercised here. The actual QR image roundtrip and trusted manual-bundle path were tested; Google Play services supplies the phone scanner. The API-37 emulator was tested; older supported Android releases and OEM devices need device testing.

This is a learning protocol requiring human cryptographic review before real use. It supports one Android sender and one Mac recipient, local debug HTTP, no forward secrecy or reverse direction, and a strict counter without an out-of-order delivery window. HTTP reveals metadata and permits traffic disruption. Run one receiver process per Mac. Displayed history is memory-only; Keychain stores identity and replay state. A rebuilt helper may trigger macOS Keychain access approval; identities are never silently replaced to bypass that protection.

## Reproduce

See README.md → Paired lesson verification. The native default tools/test_paired_android.py starts the signed sibling app itself; for the historical reference proof start the receiver and use --reference. macOS 14+, Apple Command Line Tools, Android Studio’s JBR and the Android SDK are required. No Python crypto package is required by the receiver.

## Native migration — 21 October 2026

The sibling Mac app compiles the shared CryptoKit core and receives directly with
Network.framework. Twenty-seven native Swift tests pass. Ten Android emulator
instrumentation tests passed against the signed sandboxed native listener using
its explicit memory-only mode, including decryption, receipts, tamper rejection,
duplicates, QR clearing and pause/resume. Native connection/deadline bounds passed.
After the owner approved macOS Keychain access, all ten tests passed again with
the existing real identity. A fresh native app process restored the same public
fingerprint and saved counter; one additional Android test verified its signed
duplicate receipt, with zero new inbox rows. This establishes native protected-item
read/write/restart, with Python used only as test orchestration. No identity was
replaced or exported. The emulator pairing intentionally replaced the saved phone
and pairing epoch; re-pair a physical phone before using it.
Current evidence and remaining interactive/physical checks are tracked in
`../Sparshur/docs/VERIFICATION.md`; historical helper checks above are not native proof.
