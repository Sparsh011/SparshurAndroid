# ADR 0001: Keystore encryption and local observation lab

Status: accepted for the requested learning exercise; human review required
before any production reuse. Not an approval of Bridge's E2EE protocol.

Use platform AES-256-GCM with a nonexportable AndroidKeyStore key, rather than
adding Tink before we need HPKE. Keep Android's randomized-encryption requirement;
let the provider generate each nonce. Bound key use with a durable counter.
Display actual hardware backing. No silent key replacement or key export.

Use a Python-standard-library HTTP observer and local browser dashboard on the
Mac. It observes plaintext/ciphertext but never gets the AES key. Python avoids
introducing a Go/Swift toolchain just for the first experiment. No Android app
runtime dependencies are added. Existing test dependencies are updated for
Android 17 compatibility (JUnit extension 1.3.0 / Espresso 3.7.0). This server
will not become the production Go relay.
HTTP and readable sends are debug-only; private literal IPv4 destinations only.
JSON is a lab format; future production envelopes remain versioned Protobuf.

Keep crypto, envelope encoding, networking, UI state and Compose UI separate.
Explain properties and limitations in the interactive app and dashboard.
Test the primitive against a known-answer vector, local tampering/wrong-key
failure, persisted keys, wire schemas, receiver validation and deduplication.

User authorization allows this encryption exercise ahead of Phase 0; neither
Phase 0's real-device feasibility checks nor Phase 1's acceptance is claimed.
