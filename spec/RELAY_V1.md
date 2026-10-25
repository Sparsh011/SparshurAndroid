# Relay transport v1 — Android connection milestone

Status: implemented Android connection/authentication draft; requires human
protocol review and Go/Swift interoperability before real use. This is NOT the
paired-v2 wire format. It does not migrate a local pairing or deliver messages.
The end-to-end relay envelope/pairing protocol will have its own version and
vectors before payload routing is enabled. See docs/RELAY_IMPLEMENTATION.md.

## Roles and trust

Android and the receiving Mac open outbound WebSockets to a Go process on Mac
mini. The relay is a routing/availability principal, never a content-decryption
principal. A receiver app on the same mini is a separate process/key-storage
role; compromise of that host can compromise both roles. E2EE cannot isolate a
recipient from its compromised OS. Prefer a dedicated relay OS account without
access to the recipient's login Keychain, backups or plaintext history.

TLS authenticates the configured relay service; the signed challenge authenticates
the device to that service. Neither enrolls an end-to-end peer. Only a trusted
pairing may pin peer keys. The relay must have an administrator-enrolled device
ID → Ed25519 public-key mapping; an unknown ID is rejected. Do not accept a key
from hello/auth and register it automatically. Public enrollment details can be
read from Android's debug connection screen; private keys never leave Android.
Using the existing signing identity is allowed only with this distinct purpose
domain. The original local AES key is never involved.

## Connection

- First deployment topology, selected by owner: private VPN (Tailscale/WireGuard).
  Use the configured private-reachable hostname with normal TLS validation; wire
  bytes and device-auth requirements are unchanged. Mini must enforce private
  ingress. This client currently relies on OS routing, not VPN interface binding.
- Explicitly configured canonical `wss://HOST[:PORT]/bridge/v1/ws`.
- No user info, query, fragment, alternate path, redirects or cleartext fallback.
  No secret or bearer token in a URL, header or log. The URL is user configuration,
  not a key fetched from a relay directory. Changing it requires explicit action.
- TLS 1.3 only, normal system certificate chain and hostname checks. No trust-all
  manager, permissive verifier, custom production CA, or TOFU certificate bypass.
  Android's platform TLS 1.3 requires API 29+; older devices retain the local labs.
- `Sec-WebSocket-Protocol: bridge.relay.v1`; exact selection required. HTTP 101
  alone is not an authenticated device session. HTTP/1.1 upgrade, no HTTP auth,
  cookies, cache, logging interceptor, or automatic HTTP retry. System proxy
  configuration is respected; TLS validation remains in force.
- At most one socket per Android connection controller. The server should allow
  one active socket per enrolled identity; reject a second authenticated socket
  with code 4409 rather than silently replacing a live session. Never kick a
  session on an unauthenticated hello. Dead sockets must expire before retry.

## Control encoding

One UTF-8 text WebSocket message is one flat JSON object, at most 2048 bytes.
No binary messages, nested values, nulls, booleans, duplicate/unknown fields,
trailing tokens/commas, comments or coercion. JSON whitespace is allowed. Field
order is irrelevant. Strings use only ASCII letters/digits and `_ . / : + = -`
(without spaces), without JSON escapes; these control frames carry identifiers,
canonical padded Base64 and fixed protocol labels, not arbitrary user text.
Version is the JSON integer `1`, not a quoted string or float. UUIDs are lowercase
UUIDv4; Base64 lengths are checked after decoding and re-encoding canonically.
This intentionally narrow parser must not be reused for encrypted payload schemas.

1. Android generates a fresh 32-byte SecureRandom client nonce on EVERY attempt:

   `{"version":1,"type":"hello","device_id":"UUID","client_nonce":"BASE64_32"}`

2. Relay allocates a fresh UUIDv4 session ID and independent 32-byte CSPRNG
   challenge, associated with this socket, hello/device and a ten-second expiry:

   `{"version":1,"type":"challenge","session_id":"UUID","challenge":"BASE64_32"}`

3. Android signs the UTF-8 bytes below with Ed25519, without a final newline:

   `bridge-relay-v1/auth|WSS_URL|DEVICE_ID|CLIENT_NONCE|SESSION_ID|CHALLENGE`

   `WSS_URL` is the canonical configured URL, including path and nondefault port.
   The relay uses its configured public URL, never a caller-controlled Host header
   or arbitrary forwarded header. This binds purpose, destination, identity and
   this connection's fresh challenge. Do not use a `bridge-v2/*` signature here.

   `{"version":1,"type":"auth","device_id":"UUID","session_id":"UUID","signature":"BASE64_64"}`

4. Relay verifies against the previously enrolled key, checks the bound device,
   client nonce/session/challenge/expiry and atomically consumes the challenge
   on the first auth attempt. Repeated auth, replay on another socket, expired
   challenge or changed context fails. It then binds that connection to the ID:

   `{"version":1,"type":"ready","device_id":"UUID","session_id":"UUID"}`

Android requires the expected ID/session and correct state. Ready means only
**authenticated to relay**. It says nothing about a receiving Mac, E2EE pairing,
queue durability, decryption, human reading or clipboard changes. Ready is
authenticated by the TLS channel, not an endpoint receipt.

The relay may return exactly `version,type:"error",code`, where code is one of
`unauthorized`, `revoked`, `busy`, `unsupported`, `rate_limited`. The Android
diagnostic stops for all such errors; explicit retry is required. Error text is
never copied into logs/UI. No data frame is accepted in this milestone.

## Lifetimes and bounds

Connect/upgrade and authentication each have a ten-second deadline. Ping every
25 seconds; missing pong fails the socket. Send calls must check enqueue success
and keep the library queue below 8192 bytes. This milestone sends only two small
control frames and accepts only one challenge and one ready. Additional text or
binary frames fail closed. The relay must enforce its own frame/read/rate,
per-IP, per-device and global connection bounds BEFORE allocating large bodies.

Important implementation limit: OkHttp delivers a complete message to its
listener, so Android's 2048-byte check is AFTER library buffering. It is not a
pre-allocation bound against a malicious TLS-authenticated relay or compressed
message bomb. Treat relay availability as trusted for this connection spike;
streaming/bounded receive support must be resolved before hostile-relay
hardening or payload receive. Do not implement a handwritten WebSocket parser.

Transport loss, timeout, HTTP 429/503, and close 1001/1012/1013 may reconnect with
fresh nonce/auth, capped exponential backoff with jitter (1–30 seconds), at most
six consecutive retries. Authentication/protocol/TLS certificate/hostname errors,
redirects and unsupported TLS never automatically retry. Reset the retry budget
only after 30 seconds of authenticated stability; rapid flaps cannot retry forever.
Stop, screen exit, endpoint changes and disposal invalidate all old callbacks and
timers. There is no background socket, service, auto-start or hidden identity reset.

`spec/relay-auth-test-vectors.json` contains public deterministic TEST inputs and
exact signed bytes. JVM tests verify them independently of Android storage. These
are not proof of Go/Swift interop; both implementations must consume the vectors.
