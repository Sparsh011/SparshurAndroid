# Android relay verification

The Android transport tests use the frozen Mac
[authentication](../spec/relay-auth.json) and [wire](../spec/relay-wire.json)
fixtures. They check Tink verification, all nine protobuf kinds, exact URL
and relay ID handling, canonical packet digests and malformed input.

MockWebServer tests exercise TLS 1.3, trust and hostname checks, redirect
refusal, subprotocol selection, a full signed exchange, stale challenges,
binary frame validation, retry and stop behavior, pending-ack correlation
and the upgrade-required delivery handler. Tests use ephemeral TLS
certificates and public fixture signing keys. The debug instrumentation test
checks both input fields survive activity recreation while the screen stays
disconnected.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python3 -m unittest discover -s tools -p 'test_*.py' -v
```

MockWebServer and the Python HTTP tests require loopback port binding. Run
`./gradlew :app:connectedDebugAndroidTest` with an Android device or emulator.

Live verification still requires the deployed mini, its trusted TLS
certificate, both real device enrollments and a phone on mobile data. Check
that Android and Mac authenticate using the same URL and relay ID. The
[relay plan](RELAY_IMPLEMENTATION.md) tracks the later end-to-end milestones.
