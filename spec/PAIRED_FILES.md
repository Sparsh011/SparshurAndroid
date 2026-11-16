# Direct paired files

Existing paired-v2 QR, signing identities, text envelopes and text receipts still
work. Files use the same debug-only direct connection on LAN or Tailscale.
Both endpoints need the file-capable build. One file up to 5 MiB is sent per
transfer. A file is a four-byte unsigned big-endian metadata byte length, a
UTF-8 JSON object containing `name` and `mime`, then raw file bytes. Metadata is
bounded to 2048 bytes. The file name is a display name, never a filesystem path.
The framing fixture is in [paired-file-test-vector.json](paired-file-test-vector.json).

Android uploads to `/paired-file` with `message_type:file`. All three file
routes use the paired-v2 envelope with the actual message type in the AAD
(where text uses `text`). HPKE, signatures, duplicate checks, and the upload
receipt are unchanged. The Mac holds the verified attachment in its current
inbox and offers a system Save dialog.

The Mac queues one file in memory for its current pairing. In the foreground,
Android sends `/paired-file/pull` with `message_type:file_pull` and a fresh
random 32-byte plaintext response key. The Mac authenticates and consumes the
request counter, then responds with exactly `file_id`, `sealed`. `file_id` is
the queued file's UUID, or empty when no file is queued. The response is
ChaCha20-Poly1305 using the response key and AAD
`bridge-v2/file-response|<pairing_id>|<request message_id>|<file_id>`.
`sealed` is canonical Base64 of nonce || ciphertext || tag. Empty `file_id`
requires empty authenticated plaintext. The response key is secret to the
Android request and the pinned Mac; a fresh pull uses a fresh key and message ID.

After saving, Android sends `/paired-file/ack` with `message_type:file_ack` and
the file ID as plaintext UTF-8. The Mac clears only the matching file in that
pairing and returns a signed receipt. The queue remains available if an earlier
response or a save is interrupted, and clears on a successful re-pair or Mac
restart. Android can send a fresh acknowledgement for the same file ID if a
receipt is lost. Both directions are foreground debug features for one paired peer.
