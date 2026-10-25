# Android ↔ Mac through Mac mini: implementation plan

Owner request: 2026-10-24. The [delivery walkthrough](https://bridge-pairing-walkthrough.sappypark256.chatgpt.site/)
is the behavioral reference; its 11 internet steps describe a design, not a
deployed relay. Shared AGENTS.md is the broader roadmap. This is an explicitly
requested networking lesson, not evidence that Phase 0 or all Phase 1 goals pass.

## The system we are building

```text
Android app                 Mac mini                          Receiving Mac app
  keys, outbox     → WSS →   TLS terminator + Go relay   ← WSS ←  keys, inbox
  inbox, UI       ← frames  opaque live routes/TTL queue  frames → receipts, UI
```

The receiving Mac can be this same mini or a different Mac. The relay process
does not read clipboard/SMS, invoke CryptoKit/Keychain, create message content keys,
decrypt payloads, or return plaintext in acknowledgements. Endpoints alone do
that. No inbound Android/Mac client listener, direct private-IP addressing, or
phone port forwarding is needed. Pairing is persistent trust; a socket is an
ephemeral authenticated path. Reconnection never replaces a peer key.

If recipient and relay share a machine, separate their processes, OS users,
files and privileges. The relay service must not have access to the receiving
Mac's login Keychain or history database. An attacker with root access to that
same host can attack the recipient too; process separation does not create a
cryptographic boundary against host compromise.

## Network and hosting choice

The owner selected **private VPN first (Tailscale/WireGuard)** on 2026-10-24,
with authenticated HTTPS/WSS to a stable configured DNS name. The specific VPN
provider and relay hostname remain to be selected. The Android endpoint is
configurable and contains no assumed domain or embedded VPN dependency. VPN
access is an extra access control; TLS, registered-device auth and E2EE remain
required.

For this deployment:

- Android, mini and any separate receiving Mac join the authorized private
  network. Android's existing VPN app manages its tunnel; Sparshur does not
  implement VpnService, store VPN private keys or change device-wide routing.
- Configure a stable private-reachable relay hostname and a certificate trusted
  by Android for that exact hostname. Private reachability must not lead to
  accepting self-signed certificates or downgrading to ws://. Certificate
  provisioning/renewal must work without making the relay publicly reachable.
- Bind the TLS ingress to the VPN interface/address, with Go on loopback/a Unix
  socket. Restrict VPN ACLs/firewall rules to authorized clients reaching the
  relay TLS port. Keep admin enrollment, metrics, database and debug routes
  inaccessible to ordinary VPN peers. Joining the VPN does not enroll a device
  signing key or authorize an end-to-end peer.
- Do not expose the relay with router port forwarding, public tunnels or a
  public-interface listener in the first deployment. Do not add a public-address
  fallback when the VPN drops. Retry the same configured WSS destination and
  show unavailable when it cannot be reached.
- The current Android client relies on OS routing and does not verify/bind a
  particular VPN interface. Private-only enforcement belongs on mini's listener,
  VPN policy and firewall. A future client-enforced VPN-only mode would require
  a separate Android network-selection design and handover tests.
- Acceptance includes phone on mobile data with VPN enabled, reconnect after a
  VPN interruption, no delivery while the private route is unavailable, rejection
  of unauthorized VPN peers, and no publicly reachable relay/admin listener.

These are deployment requirements; choosing this topology does not install a
VPN, issue a certificate, change firewall rules or deploy a relay.

Public hosting is a deferred alternative. It would use a stable DNS name, a reachable TCP 443 path and
a valid automatically renewed certificate, with Caddy terminating TLS 1.3. Bind
Go to loopback/a Unix socket; expose only the TLS ingress, not SQLite, Keychain,
debug dashboards, pairing secrets, admin enrollment or metrics. Determine CGNAT,
ISP port rules and router reachability before choosing port forwarding. A tunnel
provider adds a metadata/availability principal; it still must see ciphertext
only. Do not deploy a tunnel or open firewall ports as part of Android work.

On mini, run relay as a dedicated launchd service/account, keep it awake for the
intended service hours, restart on crash, bound log retention, monitor disk space
and certificate renewal. Sleep, power loss and home-internet outages cause an
honest unavailable state. An offline mini cannot receive messages on its queue.
Use encrypted-disk backups for relay configuration/opaque queues, never client
private keys. Restore must not silently restore revoked device authorization.

## Trust and security decisions

1. **Two authentication layers.** Normal TLS validation authenticates the relay
   hostname; a fresh Ed25519 challenge proves enrolled device-key possession to
   that relay. Independently pinned peer keys authenticate end-to-end messages.
   A relay directory is never authority to replace peers or membership.
2. **Enrollment has an owner.** Initially register public device keys through a
   local/admin-only mini command. No public self-registration, enrollment by UUID
   alone, or automatic overwrite. A later invitation flow needs its own reviewed
   bootstrap authorization, rate limits and recovery. Revoking relay access and
   revoking end-to-end membership are separate actions and both must propagate.
3. **The QR is the peer trust anchor.** A NEW relay-compatible invitation binds
   version, relay URL, pair/session route, both identity/public-key sets, purpose,
   expiry and a 256-bit, single-use five-minute secret. Secret reaches Android
   only through the trusted scan/manual channel and is never sent to relay.
   HMAC proofs and both signatures bind the complete canonical transcript.
   Explicitly compare fingerprints and commit pins only after confirmation.
4. **Use vetted primitives.** Keep Tink/CryptoKit HPKE X25519/HKDF-SHA256/
   ChaCha20-Poly1305 and signed envelopes. A fresh content key per NEW message;
   HPKE wraps it for the intended recipient. Never export Android AES keys.
   Existing software Ed25519 seeds stay Keystore-wrapped, not described as
   hardware Ed25519. Add a separately wrapped Android HPKE receiving key before
   any reverse encrypted reply. No handwritten primitive or WebSocket framing.
5. **Version and domain separation.** Local paired-v2 stays separate. New QR,
   messages, receipts and membership statements get exact versioned bytes and
   distinct purpose labels. Authenticated context binds pair/epoch, sender,
   recipient, direction, operation type, counter, message ID, expiry and any
   request/reply correlation. Routing identifiers must agree with signed context.
6. **A relay can lie or withhold.** It may observe IPs/IDs/timing/lengths, delay,
   drop, reorder, duplicate or route to a wrong device. Endpoints reject bad
   signatures, tags, recipients, epochs and stale replies. E2EE does not hide all
   metadata or force delivery. Minimize operational logs; no content, QR secrets,
   auth proofs, private keys, tokens, ciphertext dumps or URL credentials.
7. **Receipts have precise meanings.** A successful WebSocket enqueue means
   library-buffered bytes only. `relay_accepted` means live routing accepted;
   `relay_stored` is available only after a committed durable queue transaction.
   A signed endpoint receipt binds the exact envelope digest and stated committed
   processing result. It proves neither human reading nor clipboard paste.
8. **Persist state atomically.** Recipient inbox/result and replay record commit
   together BEFORE a receipt promising durable processing. Replay state alone
   plus a volatile UI can lose a message after crash. Sender never deletes an
   outbox item on relay acceptance. Receipt verification and outbox transition
   are transactional. Apply quotas and cleanup without reopening replay windows.
9. **No false forward-secrecy claim.** Static HPKE protects current transport
   content, but later theft of a recipient static private key can expose recorded
   envelopes. Sessions/ratchets are a later reviewed upgrade. Backups, rollback,
   device compromise and key loss need explicit limits and recovery UX.

## Delivery and state model

Sender: `prepared → connecting → relay accepted / recipient unavailable →
endpoint verified`, or `expired / revoked / cancelled / outcome unknown`.
Do not collapse a timeout into “not delivered”: the recipient may have processed
the message and lost the receipt. Retry exact stored envelope bytes/ID; an edited
message is a new ID and content key. Never re-encrypt with an old message ID.

Receiver: check size/schema → expected sender/recipient/epoch and signature →
known duplicate/digest → decrypt/tag/type/expiry → atomic inbox+replay+result
commit → signed receipt → local UI. Exact duplicates replay the recorded result
without another history row or clipboard side effect. Same ID/different digest
is an error. Bad messages do not advance replay state. Unknown message types and
SMS sent as untyped clip content must not bypass the Android SMS policy gate.

Counter reservation persists before encryption. Crash between reservation and
outbox insertion creates a harmless gap. Process-level serialization must cover
counter allocation AND ordered outbox scheduling for each peer, not just individual
socket calls. Concurrent UI taps/worker drains must not dispatch competing copies.

Paired-v2's strict high-water rule is unsuitable for an unordered offline queue.
Before offline delivery choose and test a v3 replay policy: proposed bounded
per-sender/per-recipient/per-epoch sliding counter window (4096 positions), durable
ID+digest/result records and authenticated expiry. The sender limits outstanding
counters to the window; revocation retires the epoch. Explicitly define handling
of long gaps, window exhaustion, expired duplicates and clock skew in the new
spec. This is a review proposal, not a rule enabled by the connection milestone.

## Milestones, in implementation order

| Milestone | Android | Go relay / mini | Receiving Mac | Acceptance |
|---|---|---|---|---|
| 1 — connection foundation (this change) | WSS config validation, TLS 1.3, existing wrapped Ed25519 signer, challenge auth, state/deadlines/jitter/cancellation; debug foreground screen | Implement exact RELAY_V1 auth, explicit enrolled-key map and session ownership | Implement same client auth | Public vectors + TLS tests; later Android/Go/Swift real-auth proof |
| 2 — relay-compatible pairing and envelope | New pairing store/QR flow; Android receive HPKE key; v3 seal/open/receipt tests; protected pending pairing recovery | Authenticate already enrolled peers; bounded short-lived pair routes; forward opaque proof frames | QR/transcript upgrade, confirm both key sets; HPKE interop | Fresh QR on trusted display; no QR secret at relay; substituted keys/context/expired/replayed confirmations rejected |
| 3 — live text path | Wire Devices/Transfer to selected relay pairing; async sends; verify signed recipient receipts | Pair-scoped live routing and receipt return path; explicit recipient-offline result | WSS receive, verify/decrypt, persist defined result, signed receipt | Phone mobile data ↔ Mac Wi-Fi via mini; sender success only after verified recipient receipt; both old labs still work |
| 4 — durable store-and-forward | Keystore-protected outbox/inbox, one drainer, exact retries after process death, cancellation/expiry UI | SQLite ciphertext+receipt queue, transactions, TTL, quotas and recipient acknowledgements | Atomic durable inbox/dedup/result, restart recovery | Kill/restart each component at every commit/ack boundary; lost receipts and duplicate/reordered traffic cannot lose or duplicate committed results |
| 5 — get a Mac clip | Encrypted typed list/get requests, authenticated request IDs, bounded previews, decrypt replies, explicit Copy | Opaque bidirectional routing only | User-consented history capture, encrypted local history, paginated list/get handlers | Mac→Android HPKE tested; preview never overwrites clipboard; stale/wrong/unsolicited replies rejected |
| 6 — Android background and SMS | Network-aware rescheduling, FCM wake, reviewed finite foreground work; SMS receiver/backfill gated per recipient | FCM data wake with no content, TTL queue/quotas and policy-aware removal | SMS typed receive/history; consent and notifications | Real-phone Doze/OEM/network-switch testing; Off/default/invalid policy sends no SMS; queued items re-evaluated after rule changes |
| 7 — multi-device and hardening | Signed membership verification, add/revoke UX, per-recipient fan-out | Advisory roster, per-route authorization, rate limits and metrics | Same verified membership chain and revocation | Third-device add/revoke; fuzzing, chaos/load, protocol review and operational recovery drill |

Milestones 2–7 are planned, not implemented here. Preserve owner checkpoints for
the broader project phases. New dependencies beyond the agreed stack still need
discussion. Use JSON only for this narrowly specified connection milestone;
resolve Kotlin/Swift/Go Protobuf libraries via ADR before the payload wire format.
Do not accidentally turn the diagnostic JSON into the permanent production envelope.

## Android structure and integration boundaries

- `network/relay/RelayEndpoint.kt`: strict service URL, separate from LocalEndpoint.
- `RelayAuth.kt`, `KeystoreRelaySigner.kt`: narrow control codec, purpose-bound
  bytes and per-operation unwrapping/wiping of existing signing seed.
- `RelayConnection.kt`: one socket/controller, honest typed states, fresh auth,
  deadlines, bounded exponential reconnect, stale-callback invalidation and close.
- `RelayViewModel.kt`, `RelayActivity.kt`: explicit debug diagnostics in Settings
  → Mac mini relay; no payload API, background service or automatic connection.
- Next add a message repository/transport interface; TransferViewModel should use
  a pairing's explicit transport version, not silently guess HTTP vs relay. The
  UI waits for a verified endpoint receipt. Widgets keep immutable destination IDs.
- Next add `RelayPairingStore` with its own wrapping alias/backup exclusions and
  reviewed v3 schema. Existing v2 identity use is only a connection-auth bootstrap.
  Fresh installations should initialize v3 keys explicitly, without first needing
  the old lab. Never auto-upgrade v2 trust into v3 recipient capability.
- Later use a Room outbox/inbox with encrypted sensitive columns or reviewed
  SQLCipher integration. Primary's current Room table holds metadata only; do not
  start writing plaintext message/preview content into it. Keystore protects the
  database/content-at-rest key; no plaintext in SavedStateHandle, widgets or logs.

## Initial operational bounds to implement/test on relay

Proposed live text limit: 4096 UTF-8 payload bytes, 16 KiB total envelope, 2 KiB
auth/receipt controls, 10-second unauthenticated lifetime, one active authenticated
socket per device. Start with ≤5 enrolled devices/one pair at first; reject
unknown routes and duplicate sockets. Set aggregate connection/auth rate limits
so an unauthenticated client cannot occupy unbounded slots or signing work.

For offline milestone, start with 24-hour TTL, ≤1000 queued items and ≤32 MiB per
recipient, and a global disk quota. Enforce BOTH bytes and counts. Expiry must be
authenticated in endpoint context; the relay may drop earlier but cannot extend
recipient acceptance. Queue conflicts compare digest, not just message ID. Bound
receipt queues too. Relay deletion/expiry is not evidence an endpoint processed it.
Do not echo errors with request content. Emit aggregate counters and redacted
error codes only; make health endpoints reveal no device roster or pairing secret.

## Android reliability constraints

Start with explicit foreground sessions. Handle Wi-Fi/mobile handover as a new
connection, authenticate again and drain persisted work only once. Add a
ConnectivityManager observer when outbox work exists; cancel stale sockets, avoid
retrying without a validated network and bound wake-ups. WorkManager is for finite
reliable work, not an immortal socket. FCM signals “work may exist” without SMS or
clip content. Design foreground-service type/notification/timeout around actual
user-visible work and current Android restrictions, with separate acceptance tests.
Do not promise delivery while force-stopped, without connectivity, after key loss,
or when the mini is offline. A sensitive clip is always blocked before encryption.

## Review and test gates

Connection tests must cover certificate/hostname rejection, TLS downgrade,
redirects, unknown protocol, fresh nonces, wrong/duplicate/out-of-state controls,
binary/oversize/duplicate-key/fuzzed input, retry exhaustion, fast flapping, explicit
stop, stale callbacks and signing failure. Public OpenSSL/Tink auth vectors are
available now; Go/Swift consumers are still required.

Pairing/envelope gates require three-way public vectors, modified keys, bad MACs,
invalid encodings, endpoint/relay swaps, pairing expiry/races/restarts, HPKE/tag/
signature tampering, unknown types, and forged/stale receipts. Durable delivery
requires a crash matrix around every reservation/insertion/commit/ack, disk-full,
TTL/window boundaries, duplicate/reordered queues and revoked pending work.

Preserve the required Python/JVM/build/lint/device checks. No real Mac pairing is
replaced during connection tests. Test servers use only ephemeral test CAs/public
fixture keys; never enroll fixture identities on the real mini or add a test CA
to Android's trust configuration. Receiver dashboards must contain genuine data.

Before production, resolve OkHttp's complete-message buffering limit for a
hostile relay: the current control-size check occurs after allocation. Library
support/a reviewed alternative must provide pre-allocation bounds, including
fragmentation and decompression, without implementing RFC 6455 by hand. This
connection spike is not a claim of availability protection against a malicious
relay. TLS/auth/payload protocol review remains a human checkpoint.

## Sources used for this plan

- The owner's walkthrough and local source: existing v2 vs planned internet roles,
  three acknowledgement levels, exact retry, reverse HPKE and explicit Copy.
- [RFC 6455](https://www.rfc-editor.org/rfc/rfc6455): WebSocket transport and framing.
- [OkHttp changelog](https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md):
  verified dependency version and current TLS-related fixes.
- [Android background starts](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
  and [WorkManager work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work):
  why an unattended socket is not a background-delivery guarantee.
