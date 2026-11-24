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

The debug foreground relay screen uses the same TLS 1.3 WSS and binary
`bridge.relay.v1` transport as the Mac and Go relay. See spec/RELAY_V1.md and
docs/RELAY_IMPLEMENTATION.md. Keep local v1/v2 intact. Authentication uses the
existing wrapped signing identity. Enrollment is admin-controlled. A forwarding
acknowledgement confirms transport progress. Endpoint processing requires the
relay pairing and encrypted-envelope upgrade. Preserve the documented
complete-message buffering limitation and private VPN ingress.

## Local code-review-graph

The owner requested code-review-graph for compact code navigation. It is pinned
to 2.3.9 in .tools/venv, with its graph in .code-review-graph (both ignored).
Use the project CLI; no global Codex configuration change is required:

    sh tools/crg build
    sh tools/crg search KeystoreCipher --limit 5
    sh tools/crg query callers_of encrypt
    sh tools/crg query file_summary app/src/main/java/com/sparsh/sparshur/lab/crypto/KeystoreCipher.kt

Refresh after edits. Treat results as navigation aids, not security validation.
No cloud embeddings or source uploads. Use the existing checkout for Git-diff-based impact analysis.

## Checks

    python3 -m unittest discover -s tools -p 'test_*.py' -v
    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
    ./gradlew :app:connectedDebugAndroidTest

The last check needs an emulator or real Android device. The receiver dashboard
must show only genuine observations; never leave fabricated events as live data.
