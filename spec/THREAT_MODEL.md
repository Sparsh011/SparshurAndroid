# Original learning lab 01 limits

Protects: encrypted message contents against a LAN observer, assuming correct
AES-GCM use and an uncompromised Android endpoint. Local decryption detects
changed ciphertext, nonce, AAD, and a different key. No plaintext/key logging.

Does not protect: metadata/length/timing, compromised Android, screen capture,
intentional readable comparison messages, HTTP receipts, sender impersonation,
active denial of service, receiver memory access, or future long-term key theft.
There is no pairing, recipient encryption key, sender signature, membership,
forward secrecy, or authenticated network session. This is not phone-to-Mac E2EE.
The laptop observes ciphertext; it cannot decrypt it. Never export the phone AES
key to make the demo work. Pairing + public-key encryption is a later lesson.

Keystore hardware backing is reported from KeyInfo. Even hardware-backed keys
can be invoked by a compromised authorized app. Backup and device transfer of
app data are excluded to avoid moving state without its nonexportable key.

The owner explicitly requested a plaintext comparison and a hotspot experiment.
This is the sole exception to the planned relay's ciphertext-only rule: a
debug-only local observation receiver, never the production relay. Sample text
is visible in the app and loopback dashboard, not in terminal/access logs.


## Paired lesson 02

See PAIRED_V2.md and ADR 0002 for the separate authenticated protocol. It provides
one-way payload confidentiality to the paired Mac, pinned-key sender signatures,
authenticated successful receipts, and a persistent strict replay high-water mark.
The Python server is part of the receiving Mac endpoint, not an untrusted relay;
plaintext is disclosed to its loopback-only dashboard after Swift verification.
No plaintext is included in paired HTTP receipts. The original explicit readable
comparison remains available and still exposes sample text by design.

The QR's local display is the trust anchor; anyone who obtains its unused secret
can race pairing during its lifetime. Secret is 256 random bits, single-use,
5-minute expiry; HMAC proofs bind all public pairing data and both identities.
Its raw bytes never go over the LAN. A new pairing supersedes the old phone.
Persisted cached confirmation proofs contain no secret. No reset/revoke API is
exposed to unauthenticated network callers.

HTTP reveals metadata and permits blocking/replaying traffic. Negative HTTP
errors are not authenticated; only successful paired receipts are signed.
Endpoint compromise, malicious keyboards/manual bundle transfer, user trusting
an attacker's QR, rollback of protected local state, concurrent receiver writers,
loss of key storage, clock errors, and denial of service remain outside this
learning protocol's guarantees. Run one receiver per Mac. No forward secrecy
against later theft of the Mac's static HPKE private key. Out-of-order messages
below the receive high-water mark are rejected; a reorder window is future work.
Messages/display history are RAM-only; identity and replay metadata persist.
Hardware-backed protection for Curve25519 keys is not claimed. Human crypto
review and the project's future TLS/relay/membership phases are still required.

## Mac mini relay connection lesson

The Android connection milestone in RELAY_V1.md uses TLS 1.3 with normal chain/
hostname validation and a domain-separated Ed25519 proof of the existing wrapped
signing identity. It sends no message content, QR secret or private key. Device
IDs/public signing keys are disclosed as necessary for enrollment/authentication.
The relay's registered key map must be populated through a trusted admin path;
a challenge response alone cannot authorize an unknown or replacement key.

TLS and relay connection authentication do not establish end-to-end peer trust,
recipient reachability, decryption or durable delivery. A malicious relay may
lie about its connection state, withhold traffic, observe metadata or consume
resources. Android rejects out-of-state/duplicate/oversized controls but OkHttp
buffers complete WebSocket messages before the application size check. This is
not a pre-allocation memory bound against a hostile relay/decompression bomb.
Resolving bounded receive is a hardening gate before production payload receive.

An optional local-network permission action supports private relay endpoints on
Android 17+. It never weakens TLS checks. There is no background service, automatic
enrollment, hidden trust reset or production delivery claim. If relay and recipient
share Mac mini, shared-host compromise can reach recipient keys; separate OS users
reduce accidental access but do not protect from root/OS compromise. See
docs/RELAY_IMPLEMENTATION.md and ADR 0005 for the full security/reliability plan.
