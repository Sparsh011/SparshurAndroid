# Connected-device widget and SMS filtering

The owner’s later refinement removes mock recipients and simulated sends. The
app and widget now use only the existing saved Mac pairing through
`AvailableDevices`. An unpaired installation shows setup. A saved pairing is
not a live connectivity observation. See [ANDROID_APP.md](ANDROID_APP.md) and
[ADR 0004](adr/0004-primary-device-room.md) for the current screen flows.

## Current behavior

The platform RemoteViews widget lists the actual saved pair and loads Primary
from Room off the main thread. It labels Primary and lists it first. Immutable,
destination-specific PendingIntents open a focused transfer activity. An
unavailable ID is rejected; it never selects another recipient implicitly.
The widget never receives clipboard or SMS content. A tap reads the clipboard
once after focus and uses the real debug paired transport, requiring a verified
Mac receipt. There is no simulated success path.

SMS rules are stored under each authenticated pairing epoch ID. New/missing
policies start Off; invalid saved data decodes as Off. **Save rules** persists
asynchronously, with failure reporting and unsaved-change navigation protection.
The optional local test starts with empty sender/body fields. Its content stays
in ViewModel memory and never goes into settings or saved-state bundles.

## Matching semantics

Off blocks all SMS. All explicitly allows all SMS, including personal messages
and OTPs. Selected permits a message if **any exact sender OR any body keyword**
matches. No rules means no match. Rules are trimmed, case insensitive, limited
to 40 entries per field and 120 characters per entry. Blank rules never match.
Numbers ignore formatting spaces, parentheses, dots and hyphens, but retain the
country code and leading `+`; there is no suffix match. Alphanumeric sender IDs
match in full, including regional prefixes. Keywords are literal case-insensitive
substrings, never regexes.

Prefer exact organization sender IDs on a work laptop. A broad keyword may
match a personal message. Neither sender headers nor keywords authenticate an
organization. The filter controls future SMS payloads, not manually shared clips.

## Future integration contract

`ConnectedDeviceSource` is the replacement point for verified multi-device
discovery. Its current implementation exposes the single real saved pair.
Future verified devices must start with SMS Off; IDs must not be silently rebound
to unrelated identities.

`SmsRouting.recipients(devices, policies, SmsSample(sender, body))` is the tested
per-recipient gate. For every live SMS and history/backfill message, call it on
Android **before creating recipient envelopes or queues**. Feed only allowed
recipients into the eventual SMS transport. Missing policies block. Re-evaluate
current policies before dispatching pending SMS after rule changes, and remove
blocked copies. Filtering must happen before transmission.

Keep SMS typed separately from arbitrary text so it cannot bypass the gate
through the clip path. Existing v2 accepts only `message_type: text`; do not send
SMS through it until a reviewed protocol and interop fixtures define typed SMS.
There is no SMS receiver/queue, SMS permission request, multi-peer transport or
work-laptop onboarding yet. The UI makes the missing delivery integration clear.

## Code map

- `devices/ConnectedDevices.kt`: replaceable source and actual saved pairing.
- `devices/SmsPolicy.kt`: pure matching and recipient routing.
- `devices/SmsPolicyStore.kt`: persistence with fail-closed decoding.
- `devices/DevicesViewModel.kt`: draft rules and asynchronous saves.
- `devices/DevicesScreen.kt`: editor and optional local rule test.
- `devices/DevicesActivity.kt`, `TransferViewModel.kt`: real clipboard transfer.
- `widget/DevicesWidgetProvider.kt`, `res/layout/widget_*`,
  `res/xml/devices_widget_info.xml`: widget and distinct recipient actions.

All Kotlin paths start at `app/src/main/java/com/sparsh/sparshur/`.
Verification and correctly labelled screenshots are in ANDROID_APP.md.
