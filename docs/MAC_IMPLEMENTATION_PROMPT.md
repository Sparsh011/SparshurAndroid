# Prompt for the Mac implementation chat

Paste the following into a Codex chat opened in the existing Mac project.
Prepared after inspecting that project on 21 October 2026. Recheck its current
files and Git status before editing because another chat may advance the work.

```text
Continue implementing my native Mac app in:
 /Users/sparsh/Desktop/Tech/ClipSparshur/Sparshur

The existing Android project and working Python/Swift reference implementation are in:
 /Users/sparsh/Desktop/Tech/ClipSparshur/SparshurAndroid

Read first:
- /Users/sparsh/Desktop/Tech/ClipSparshur/AGENTS.md
- /Users/sparsh/Desktop/Tech/ClipSparshur/CLAUDE.md
- The Mac project's README.md and docs/IMPLEMENTATION.md, plus applicable local instructions.
- The Android project's AGENTS.md, docs/MAC_APP_PLAN.md, docs/FLOW.md,
  spec/PAIRED_V2.md, docs/adr/0002-paired-mac.md and docs/VERIFICATION_V2.md.

Goal:
Complete the agreed native Mac migration for the existing one-way Android→Mac
local-network encryption lesson. Start from the current app, not a new project.
Work autonomously through implementation and testing; don't stop at a plan.

Current Mac checkpoint:
There is already a SwiftUI sidebar with Messages, Pair phone and Learn, native
Settings, nine lessons, navigation history and keyboard shortcuts. AppModel is
app-owned and observable. LocalAddress discovers actual private IPv4 interfaces.
PlatformService performs a read-only legacy Keychain check and an ephemeral
loopback socket probe. These checks do NOT prove LAN reachability.
QR creation, native HTTP receiving, protocol-core extraction, live messages,
inspection and menu-bar receiving are still missing.

Preserve the existing design:
Charcoal/dark appearance, #F72681 accent, shared 1.2× typography, existing navigation
history, shortcuts and reusable view components. These are newer decisions than
the earlier teal/light-dark sketch. Preserve all uncommitted work and inspect
git status before editing. Do not overwrite or reset user changes.

Implement in reviewable checkpoints:
1. Verify Xcode/local signing, sandbox entitlements and access to the existing
   Keychain identity. Use the installed Xcode through DEVELOPER_DIR if necessary;
   don't change the global developer-directory setting.
2. Extract/reuse the tested Swift protocol and Keychain code from the Android
   project's tools/mac_endpoint/main.swift into shared Swift source or a local
   package. Keep the existing CLI/reference lab working against the same core.
3. Implement a bounded native HTTP receiver using Apple's Network framework.
   Preserve GET /health, POST /message, POST /pairing/complete and
   POST /paired-message compatibility with the existing Android debug app.
   NWListener provides TCP connections, not a complete HTTP server: implement
   and test framing, partial/coalesced reads, deadlines, body/header limits,
   duplicate JSON fields, schema validation and malformed inputs as specified
   in MAC_APP_PLAN.md. Do not expose QR creation or decrypted history over LAN.
4. Connect the existing Pair phone UI to real locally generated QR codes,
   explicit interface selection, five-minute expiry and actual handshake results.
   Keep a trusted manual-bundle fallback. Scan alone must not be labeled paired.
5. Populate Messages only from genuinely verified/decrypted deliveries. Implement
   inspection with the actual wire envelope and truthful processing stages.
   Keep readable samples and phone-only AES ciphertext clearly separate under
   the learning comparison. No fabricated observations in the live inbox.
6. Add MenuBarExtra sharing the SAME model and receiver. Closing the main window
   keeps receiving; reopening doesn't create another listener; pause and quit
   cancel receiving appropriately. Update status from actual listener state.
7. Update run instructions, source-reading order, lessons and verification notes.
   Demonstrate that Android can pair/send and Mac can decrypt with Python stopped.

Protocol/storage requirements:
- Preserve spec/PAIRED_V2.md, canonical signing/HMAC inputs, shared vectors and
  Android interoperability. This migration is not a protocol redesign.
- Preserve the Keychain service com.sparsh.bridge.learning.v2 and account
  identity-and-replay-state when supported. Do not silently replace identities,
  reset counters, export private keys or broaden Keychain access.
- The new app's signing/sandbox identity may not automatically access the old
  CLI's item. Verify this early and report an actual access blocker clearly.
  A helper-dependent checkpoint is not the final native migration.
- Never run the Python helper and native app as simultaneous state writers,
  even on different ports. Coordinate stopping the reference receiver.
- Android's original AES key never leaves Keystore. Its paired Ed25519 signing
  seed is software material encrypted at rest by a separate Keystore AES key.
  Mac Curve25519 keys are software material protected at rest by Keychain.
  Explain these accurately; do not claim all private key bytes remain inside OS
  hardware or that this protocol provides forward secrecy.
- Keep crypto/replay state owned by one service actor. Verification, deduplication
  and replay-state persistence must be serialized without suspension mid-commit.
  Persist before plaintext disclosure or successful acknowledgement.
- Separate typed local observations from network receipts. HTTP success responses
  must never include decrypted text. Exact duplicate retries get authenticated
  receipts without another inbox row.
- No secrets, plaintext, tokens or QR bundles in logs, fixtures or preferences.

Teaching requirements:
I'm comfortable with Android/Compose but rusty with Swift/iOS and new to applied
crypto/networking. Explain in short, simple language beside the relevant actions.
Use optional deeper disclosures rather than walls of text.
Include an understandable native pairing walkthrough showing:
  Mac creates public keys + a public pairing ID + a separate temporary secret;
  QR carries these visually to Android;
  Android confirms and sends an HMAC proof plus signature, not the secret;
  Mac verifies, saves Android's public key and returns its own proof/signature;
  Android verifies using the scanned Mac public key and pins the Mac's keys.
Clarify that HMAC uses a shared secret whereas signatures use private/public keys.
Show why changing a public key invalidates the bound proof, why replay checks are
separate, and why stealing the usable QR secret is a different threat.
Tie lessons to actual source files, with Compose comparisons and small exercises.
Explain IP/port, TCP bytes versus messages, HTTP, async/await, MainActor, actors,
view lifetime and Keychain as they become relevant.

Scope:
macOS 14+, native SwiftUI, Apple's frameworks, no unnecessary third-party packages.
A local shared Swift package is fine. Keep Android in Jetpack Compose.
No Google/phone login, automatic account-based enrollment, Go relay, cloud
recovery, SMS, clipboard permissions, files, reverse messaging or ratchet now.
Those are later milestones; don't silently add them.
The protocol remains a learning implementation requiring human review.

Validation:
Preserve and run existing Mac model/navigation/platform tests. Add meaningful
protocol, strict parser, persistence-failure, replay/restart, pairing-expiry,
tampering and receipt tests. Use the public shared interop vectors.
Adapt the Android integration harness to exercise the actual native endpoint;
don't rely on the browser dashboard or a fresh legacy helper process as proof
of native delivery. Test the UI and window/menu lifecycle too.
Clearly distinguish emulator checks from physical-phone QR/hotspot checks.
Use swift-format and build with the installed Xcode.
Use code-review-graph locally where useful; preserve existing tooling and avoid
source uploads or unrelated Git initialization.

At each milestone give a brief explanation of what changed and how to follow it.
Finish with working behavior, test evidence, remaining genuine blockers and a
short ordered reading guide. Don't claim completion while placeholders or
Python/subprocess dependencies remain in the primary native path.
```
