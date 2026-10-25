# ADR 0002 — Pair one Android sender with a decrypting Mac

Status: learning implementation authorized by "implement the next step".
Protocol/crypto design requires human review before production use.

Scope: one Android sender → one Mac recipient on the existing local HTTP lab.
Keep lab 01 intact. No Go relay, group membership, reverse messaging, ratchet,
production Protobuf or Phase 0/1 acceptance claim.

Use Tink Android 1.24.0 and Apple CryptoKit (macOS 14+). Matching HPKE suite:
X25519 / HKDF-SHA256 / ChaCha20-Poly1305, base mode, RAW/NO_PREFIX output.
Tink contextInfo maps to HPKE info, with empty HPKE AAD. The 32-byte HPKE
encapsulated key precedes its ciphertext. Interop is verified using shared
vectors and actual Android → Swift messages, never a handwritten primitive.

Each message gets a fresh random 32-byte content key; Tink ChaCha20-Poly1305
encrypts the payload once. HPKE wraps that content key for the Mac. Ed25519
signs the entire canonical envelope because HPKE base mode alone does not
authenticate a sender. Signed Mac receipts authenticate the acknowledgement.
Domain-separated HMAC-SHA256 confirms a single-use QR pairing secret without
sending it on the LAN. Ed25519 proves possession of both signing identities.

Android's Ed25519 seed is software key material in app memory during use,
encrypted at rest under a separate nonexportable AES-256-GCM Keystore key.
Do not claim hardware-backed X25519/Ed25519 at min API 24. The original lab AES
key is never exported or reused for messaging. Mac keys and replay state live
in an application-specific Keychain item (device only); CryptoKit uses software
Curve25519 keys in the helper's memory. No private key crosses device boundaries.

The existing Python HTTP observer is the Mac endpoint, NOT a relay. A persistent
Swift helper owns keys, pairing, signatures and decryption. Python receives
decrypted text only for local display, through local anonymous stdio pipes.
The dashboard and QR endpoints remain loopback-only, with same-origin checks.
CoreImage generates QR PNGs locally; no QR service receives secrets.
Android uses Google code scanner 16.1.0 (no app camera permission), with a manual
bundle input fallback for devices without its downloaded Play services module.

Mac pairing secrets expire after 5 minutes and are never sent over LAN or logged.
A new pairing invalidates the old phone only after successful authentication.
One active phone is supported. Replay high-water mark and a bounded duplicate
receipt cache persist in Keychain before returning success. Ordering is strict:
late messages below the high-water mark are rejected; durable offline queues
and a reorder window are future work. Concurrent requests are serialized.

HTTP still exposes metadata and permits denial of service, but message content
is encrypted and sender/Mac receipts authenticated. Trust depends on importing
the QR from the intended Mac's trusted local display. No forward secrecy against
later theft of the Mac's long-term HPKE private key. This is a reviewed-in-code
learning step, not a claim of production security.
