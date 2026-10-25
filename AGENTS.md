# Android checkout guidance

Read ../AGENTS.md and ../CLAUDE.md for the shared Bridge project context.
The owner's explicit request authorizes this debug-only Keystore / plaintext
comparison lab before Phase 0; do not treat it as production E2EE or a relay.
spec/PROTOCOL.md, spec/THREAT_MODEL.md and docs/adr/0001-keystore-learning-lab.md
describe this limited exercise. Never export the Android AES key.

The owner subsequently authorized the next lesson: one-way paired Android→Mac
encryption/decryption. See spec/PAIRED_V2.md, docs/adr/0002-paired-mac.md and
docs/FLOW.md. It uses Tink 1.24.0, CryptoKit (macOS 14+), Keychain, and an
Android Keystore-wrapped software signing seed. Preserve the old local-key lab.
Run one receiver per Mac; multi-process writers / group membership are out of
scope. Human protocol review is still required before production use.

The owner next authorized Mac mini as a Go WebSocket relay and starting Android
networking (2026-10-24). See docs/RELAY_IMPLEMENTATION.md, spec/RELAY_V1.md and
ADR 0005. The initial debug foreground screen authenticates a TLS 1.3 WSS
connection only; it sends no clipboard/SMS and does not implement relay pairing,
payload delivery, durable queues or Android reverse decryption. Keep local v1/v2
intact. Relay auth uses the existing wrapped signing seed under a new purpose
domain; enrollment is admin-controlled. Never equate relay readiness with an
endpoint receipt. Preserve the documented complete-message buffering limitation.
The owner selected private VPN first (Tailscale/WireGuard). Keep WSS/TLS and
device auth intact, use OS-managed VPN routing and private mini ingress; provider,
hostname and deployment are still pending. See the plan's network/hosting section.

## Local code-review-graph

The owner requested code-review-graph for compact code navigation. It is pinned
to 2.3.9 in .tools/venv, with its graph in .code-review-graph (both ignored).
Use the project CLI; no global Codex configuration change is required:

    sh tools/crg build
    sh tools/crg search KeystoreCipher --limit 5
    sh tools/crg query callers_of encrypt
    sh tools/crg query file_summary app/src/main/java/com/sparsh/sparshur/lab/crypto/KeystoreCipher.kt

Refresh after edits. Treat results as navigation aids, not security validation.
No cloud embeddings or source uploads. This folder currently has no Git
repository, so Git-diff-based impact analysis is unavailable until one exists.
Do not add a Git repository just to make that feature work.

## Checks

    python3 -m unittest discover -s tools -p 'test_*.py' -v
    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
    ./gradlew :app:connectedDebugAndroidTest

The last check needs an emulator or real Android device. The receiver dashboard
must show only genuine observations; never leave fabricated events as live data.
