# Android relay connection milestone — 2026-10-24

## Implemented and checked

- 38 JVM tests pass: the 18 existing tests plus 20 relay tests (4 control/auth
  vector/parser tests and 16 TLS/WebSocket/session tests).
- Local JVM TLS/WebSocket tests use ephemeral localhost certificates and public
  fixture signing keys. They check actual TLS 1.3 negotiation, chain/hostname
  rejection, no redirect following, exact subprotocol, purpose-bound signatures,
  wrong sessions/identities, replayed challenges, binary/oversize controls,
  authorization/signing failure, finite deadlines/retries, fresh nonces, fast
  connection flaps, stop during slow signing and cancellation of scheduled retries.
- The auth fixture was generated independently with Homebrew OpenSSL 3 Ed25519
  using the PUBLIC RFC 8032 test seed. Tink reproduces/verifies the exact signature;
  altered purpose, relay URL, device, client nonce, session or challenge fails.
- All 11 Python receiver tests pass, including their isolated loopback HTTP server.
- Debug build and debug lint pass (zero errors, 27 existing warnings). Release
  build also passes. The release manifest does not register RelayActivity; debug
  Settings exposes the explicit connection lesson. Existing HTTP lab controls
  retain their debug-only behavior.
- The complete Gradle connected check succeeds on Pixel_10a / Android 17
  (emulator-5554): 18 instrumentation cases pass, including RelayScreenTest's
  no-auto-connect/recreation check. Four optional receiver/pairing/restart cases
  exit through JUnit assumptions because no real receiver fixtures were supplied.
  Gradle's generated aggregate XML labels those assumption exits as failures;
  their exception types are AssumptionViolatedException and the Gradle check exits
  successfully. Do not represent them as four real integration passes.
- code-review-graph was refreshed after the implementation. It is navigation
  evidence, not cryptographic review.

## Scope and remaining gates

No real Mac pairing was replaced, no receiver dashboard was populated, no relay
service was started/deployed, and no clipboard/SMS payload was sent through WSS.
TLS tests are JVM tests; the emulator test checks the Android diagnostic lifecycle
without connecting. Actual Android→Go authentication, Swift→Go authentication,
real wrapped-key signing over WSS, physical-phone/VPN/network-handover behavior
and local-network permission acceptance/denial still need integration checks.

Human review of RELAY_V1.md is required. Go must implement registered-key lookup,
fresh socket-bound challenge consumption/expiry, bounded reads/rate limits and
single-session ownership; Go/Swift must consume the public auth fixture.
Pairing, payload envelopes, live routing, durable outbox/inbox/TTL queues and
reverse encrypted replies remain the next milestones in RELAY_IMPLEMENTATION.md.

The app's 2048-byte control check occurs after OkHttp complete-message buffering.
It does not prevent allocation/decompression denial of service by a malicious
TLS-authenticated relay. Resolve this before hostile-relay payload hardening.
No production-security or forward-secrecy claim is made.
