# ADR 0005 — Mac mini relay and Android WebSocket foundation

Status: implementation requested by owner on 2026-10-24. Human review required
for authentication, pairing, envelope and replay design. This request authorizes
starting the networking lesson; it does not establish earlier phase acceptance.

Use the already selected OkHttp stack for an explicit foreground connection to
a separately operated Go relay on Mac mini. Pin OkHttp 5.5.1 and matching test
fixtures (MockWebServer and okhttp-tls); no logging interceptor. Its TLS defaults
include the current certificate protections; we additionally require TLS 1.3.
The transport has no hardcoded deployment endpoint or enrollment credentials.

The owner selected private VPN first (Tailscale/WireGuard) on 2026-10-24. Keep
the VPN OS-managed and the relay ingress private, with a stable hostname and
normally validated TLS certificate. VPN membership does not replace device
enrollment or peer trust. Provider/hostname selection and deployment are pending;
no embedded VPN SDK, public ingress or routing changes are part of this lesson.

Keep paired-v2 and the nonexportable local AES lab unchanged. Do not repurpose
local HTTP addresses as internet relay URLs, reuse v2 receipt meanings, or send
v2 ciphertext through a transport pretending to implement reliable relay delivery.
The first diagnostic implements only service connection and registered-device
challenge-response. It sends no clipboard, SMS, pairing secret or message payload.

A relay and a recipient are distinct roles even when co-located on Mac mini.
The relay service needs no recipient Keychain entitlement and no decryption keys.
Deploy under a separate OS account and restrict files/ports; shared-host root or
recipient compromise is outside an E2EE content boundary.

Reuse the Android software Ed25519 signing identity wrapped by PairingStore only
for a domain-separated relay-auth signature. Load and wipe its seed per signing
operation. Public enrollment is explicit and admin-controlled; TLS authentication
of the relay is not authorization to replace peer keys. Reverse encrypted replies
require a NEW Android HPKE receive identity, authenticated by a new pairing format.

Foreground-only diagnostics stop on activity exit. WorkManager/FCM and any
foreground service require a separate background-reliability milestone; a socket
alone is not an Android delivery guarantee. Do not add a foreground service type
or battery-exemption flow merely to hold this diagnostic open.

Plan the full system in docs/RELAY_IMPLEMENTATION.md; specify the implemented
auth bytes in spec/RELAY_V1.md with public vectors. Gate the new message UI on
reviewed interoperable pairing/envelopes and real signed endpoint receipts.
