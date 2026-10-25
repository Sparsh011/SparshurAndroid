# ADR 0004 — Device-first UI and a Room-backed Primary choice

Status: accepted by the owner after reviewing the Device first proposal.

Use the Mac app’s charcoal palette and #F72681 accent. Devices is the Android
landing screen, with Learn and Settings as the other destinations. Pairing and
per-device SMS rules have dedicated screens. Keep the original crypto lessons
in a separate activity reachable through Learn; do not remove the protocol,
Keystore checks, tamper exercises or signed-receipt verification.

The owner explicitly requested the term **Primary** and Room persistence for
that choice. Use Room 2.8.4 with KSP 2.3.12 to generate the DAO. One row, keyed by
slot 1, stores the chosen device ID. Upserting replaces the previous selection.
All DB access runs off the main thread. Export schema v1; no destructive
migration fallback. Existing SMS rules stay in their existing settings store.
Room here holds only the Primary ID, not clipboard text, SMS, crypto keys,
envelopes, pairing secrets or delivery history. Existing backup/transfer
exclusions cover this metadata database. It is ordinary SQLite metadata,
not the planned SQLCipher message-history store.

Primary does not establish trust, authorize SMS or silently reroute a stale
widget action. A real device is identified by its authenticated pairing epoch;
a new epoch gets fresh per-device policy defaults. An unavailable Primary is
shown as not set rather than replaced automatically. The widget reloads Room
and puts the matching device first with an explicit Primary label.

A user’s send tap opens a focused activity. Read the clipboard once, refuse
sensitive/non-text/empty/oversized items, and use the existing paired v2 sender
for the saved Mac. The later UI refinement removes all sample recipients and
simulated sends; an unpaired installation shows setup. A successful real
send requires the pinned Mac’s signed receipt. Keep a failed envelope only in
RAM for an identical retry. Rotation does not create a new request, and process
recreation does not replay a send. No background clipboard monitoring, SMS
capture, multi-peer protocol or wire-format change is introduced.

Room: https://developer.android.com/jetpack/androidx/releases/room
KSP: https://github.com/google/ksp/releases/tag/2.3.12
