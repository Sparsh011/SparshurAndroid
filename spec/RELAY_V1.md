# Android relay transport

The Mac checkout's [Bridge relay transport specification](https://github.com/sparshchadha/sparshur/blob/HEAD/spec/PROTOCOL.md)
and its [protobuf schema](https://github.com/sparshchadha/sparshur/blob/HEAD/spec/relay_transport.proto)
define the shared Mac, Android and Go relay contract. Android tests use byte-for-byte
copies of the shared [authentication](relay-auth.json) and [wire](relay-wire.json)
fixtures.

- Android 10+ debug Settings → **Mac mini relay** starts an explicit foreground
  connection. Enter the same exact `wss://host[:port]/v1/relay` URL and relay ID
  configured on the Go relay. Its subprotocol is `bridge.relay.v1`.
- OkHttp uses TLS 1.3 with platform trust and hostname checks. Redirects are
  disabled. Authentication signs the challenge using the existing
  Keystore-wrapped Ed25519 identity. Enroll its public data through `relayctl`.
- Reconnects use fresh authentication with up to six attempts and 50–100%
  jitter, from 1 to 30 seconds. Authorization and protocol failures stop
  reconnects. Leaving the diagnostic stops the connection.
- Android accepts only binary frames. A DELIVER addressed to this device
  currently stops the session with an upgrade-required status. Internal
  prepared submissions have one write in progress and up to 32 pending
  acknowledgements. They have no durable retry queue or UI.
- The 65,536-byte frame limit is applied after complete-message buffering by
  OkHttp. TLS and relay enrollment remain prerequisites for the debug screen.
