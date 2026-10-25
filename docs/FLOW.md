# Read the implemented flow

Start at README.md. There are two independent lessons sharing the same local
transport. Read lesson 01 first if Keystore/AES is still new; lesson 02 is the
next step that actually lets the Mac decrypt.

## Lesson 02 reading order

Android paths below start at `app/src/main/java/com/sparsh/sparshur/`.

1. `MainActivity.kt` — opens Devices / Learn / Settings; `lab/LabActivity.kt`
   opens the preserved experiments. Both connect the Google QR scanner and network
   permission gate. `lab/LocalNetworkActivity.kt` requests local-network access
   only on Android 17+.
2. `lab/LabScreen.kt` — switch between local-key and paired lessons.
3. `lab/paired/PairedMacLesson.kt` — pairing, encryption and send controls, with
   algorithm/storage explanations next to the actions.
4. `lab/LabViewModel.kt` — follow `importBundle` → `pairMac` → `encryptForMac` →
   `sendToMac`. Background work happens on the executor; only UI state returns
   to the main thread. Secret-bearing QR state stays in memory and is cleared
   after pairing; errors never log request/secret bytes.
5. `lab/paired/PairingStore.kt` — AES-GCM wraps the SOFTWARE Ed25519 signing seed
   at rest using a separate nonexportable Keystore key. Public Mac keys and a
   durable send counter are stored separately. No automatic key replacement.
6. `lab/paired/PairedProtocol.kt` — exact wire fields, HMAC proofs, Ed25519,
   ChaCha20-Poly1305, HPKE and signed receipt verification. All cryptographic
   primitives come from Tink/JCA; application code only composes the protocol.
7. `lab/network/LocalEndpoint.kt`, `LabClient.kt` — validate local numeric IPv4,
   post the pairing/message, check the pinned Mac's receipt. Address changes
   affect routing, not trusted keys. This does not bind a specific interface.
8. `../Sparshur/Sparshur/App/AppModel.swift` and `Services/ReceiverCoordinator.swift`
   — one native app-owned model, menu/window lifetime and receiver coordination.
9. `../Sparshur/Sparshur/Networking/` — bounded TCP/HTTP parsing and native listener.
10. `../Sparshur/Sparshur/Core/PairedProtocol.swift` and `EndpointService.swift` —
    shared crypto/Keychain transactions, replay persistence before plaintext,
    typed local observations and wire receipts. The reference CLI compiles the
    same protocol core; native operation never launches that CLI.
11. `tools/laptop_receiver.py`, `mac_crypto.py`, `mac_endpoint/main.swift` and
    `receiver_ui/` — optional reference dashboard/pipe adapter. Quit the native
    app before using this reference; never run competing identity writers.
12. `spec/PAIRED_V2.md` and `docs/adr/0002-paired-mac.md` — exact byte contract and
    design reasons/limits. `spec/paired-test-vectors.json` is the interop fixture.

## Pairing, in plain language

```text
Mac Keychain → private keys stay local
Native Mac Pair phone → QR: public keys + temporary secret + endpoint
Android scans trusted display → public-key fingerprint comparison
Android → HMAC proof + signing public key + sender signature
Mac → verifies possession → saves paired sender → confirms with proof + signature
Android → checks confirmation → pins Mac public keys → discards QR secret
```

The secret authenticates the exchange but is never itself sent over the LAN.
A captured confirmation cannot authorize a different phone or public key.
Re-pairing is explicit and supports one phone at a time.

## Sending and receiving

```text
Sample text
  → fresh random content key
  → ChaCha20-Poly1305 encrypts text, authenticates metadata
  → HPKE encrypts the content key for the pinned Mac
  → Ed25519 signs the complete envelope
  → HTTP sends ciphertext / wrapped key / signature
  → Mac verifies the pinned sender
  → Mac unwraps the content key with its own private key
  → Mac verifies the payload tag and decrypts
  → Mac saves replay state in Keychain BEFORE disclosure / acknowledgement
  → local Mac dashboard displays plaintext
  → Mac signs a receipt; Android verifies it against the pinned signing key
```

Retries use the same message ID and signed content; a duplicate gets a signed
acknowledgement without redisplay. Counter gaps are allowed; late unknown
counters below the high-water mark are rejected. The demo deliberately has no
reorder window. This is different from durable at-least-once delivery, which is
not implemented yet.

## Original lesson 01 reading order

`MainActivity.kt` → `LabScreen.kt` → `LabViewModel.kt` →
`lab/crypto/LabEnvelope.kt` → `lab/crypto/KeystoreCipher.kt` →
`lab/network/LabWire.kt` → `LabClient.kt` → `tools/laptop_receiver.py` → `viewer.js`.

That AES key never leaves Android, so those v1 envelopes remain unreadable on
Mac. The readable comparison button intentionally sends plaintext instead.
The original flip-byte action changes one bit in a COPY of ciphertext byte 0;
it now reports the before/after hexadecimal values. It never mutates the
original UI envelope. The paired lesson's tamper button sends an altered copy
to the Mac, where authentication fails.
