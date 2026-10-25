# ADR 0003 — Mock device widget and local SMS recipient rules

Status: historical first implementation, superseded by ADR 0004 and the current
app refinement. Mock recipients and simulated sends have been removed from the
app and widget. See ../ANDROID_APP.md for current behavior.

Use Android's platform RemoteViews widget with the existing Compose activity
stack. No new dependency is necessary. A shared mock device source stands in for
discovery. Widget taps open a foreground composer because Android restricts
background clipboard access. Reading the clipboard requires a separate explicit
Paste action and sensitive clips are refused. All sends here are visibly mock.

Store settings per stable device ID. SMS defaults Off; Allowlist matches exact
senders or literal body keywords; All requires explicit selection. Unknown or
invalid settings block. Filtering must happen independently per recipient on
Android before encryption and transmission, including future history backfill.
Do not forward broadly and rely on hiding private SMS at the receiving laptop.

The app has no SMS ingestion or multi-peer transport yet. Provide the pure gate,
persisted editor and local sample preview now, rather than acquiring SMS before
the typed protocol and transport are ready. The existing v1/v2 lab wire formats,
keys and receiver observations are unchanged. Real device onboarding must use
verified identities and fresh Off policies; mock settings do not confer trust.
