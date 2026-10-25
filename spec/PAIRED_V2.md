# Paired local lab v2 — source of truth

Scope and limitations: docs/adr/0002-paired-mac.md. All strings below are UTF-8
without a final newline; Base64 is standard padded, canonical and unwrapped.
UUIDs are canonical lowercase UUIDv4 strings in this lab. IDs are labels, not
credentials. Strict field sets, integers (not booleans), and sizes are checked.

## Pairing over a trusted local display

Loopback-only POST /api/pairing/new takes exactly {host, port}; host is a private
literal IPv4/127.0.0.1 and port 1024..65535. Response has a JSON bundle and a
locally generated PNG QR. The secret-bearing bundle has exactly:

    bridge_pairing:2, pairing_id, recipient_id, hpke_key, signing_key,
    host, port, expires_at, secret

Keys and secret are 32 bytes each. pairing_id and secret are fresh. expires_at
is Unix seconds, 5 minutes after creation. Never GET this endpoint over LAN.
The QR is the trust anchor. Manual input is equivalent ONLY if transferred via
a trusted channel; do not fetch keys from an unauthenticated network endpoint.

QR public transcript Q (secret excluded):

    bridge-pair-v2|pairing_id|recipient_id|hpke_key|signing_key|host|port|expires_at

Android appends its own UUID sender_id and 32-byte Ed25519 public key Base64:

    T = Q|sender_id|sender_key

POST /pairing/complete has exactly pairing_id, sender_id, sender_key,
proof, signature. proof = HMAC-SHA256(secret, "bridge-v2/pair|" + T).
signature = Ed25519_Android("bridge-v2/pair|" + T), 64 bytes.
Mac checks expiry, HMAC in constant time and signature before committing.

Response has exactly pairing_id, recipient_id, sender_id, proof, signature.
proof = HMAC-SHA256(secret, "bridge-v2/paired|" + T);
signature = Ed25519_Mac("bridge-v2/paired|" + T).
Android verifies both before pinning Mac keys and clears the secret from UI.
Equivalent retries return the cached acknowledgement after checking the sender
signature again; signature bytes may differ between valid signing operations.
The cached digest covers all request fields except signature; changed proofs,
IDs or keys fail. Changed/reused requests cannot replace a pairing.
Used secrets are discarded; the active acknowledgement cache contains no secret.
In-flight secrets expire and are replaced by an explicit new-pairing action.
No unauthenticated route forgets a paired device.

## Message

POST /paired-message has exactly:

    lab_version:2, mode:"paired", suite:"X25519-SHA256-CHACHA20POLY1305",
    pairing_id, sender_id, recipient_id, message_id, message_type:"text",
    counter, nonce, ciphertext, wrapped_key, signature

counter is 1..1000000, persisted/reserved on Android before encrypting. A fresh
content key is generated for EACH new message. A retry sends identical bytes.
Plaintext is 1..4096 UTF-8 bytes. Nonce is 12 bytes; ciphertext includes a
16-byte Poly1305 tag; wrapped_key is exactly 80 bytes; signature 64 bytes.

    A = bridge-lab|2|pairing_id|sender_id|recipient_id|text|counter|message_id
    C = ChaCha20Poly1305(content_key, nonce, plaintext, AAD=A)
    W = HPKE_MacPublicKey(content_key, info="bridge-v2/key-wrap|" + A, AAD=empty)
    S = "bridge-v2/message|" + A + "|" + nonce + "|" + ciphertext + "|" + wrapped_key

Here nonce/ciphertext/wrapped_key in S are their canonical Base64 strings.
Tink HPKE NO_PREFIX output W = encapsulatedKey(32 bytes) || ciphertext(48).
Signature is Ed25519_Android(S). No plaintext or secret key appears in the body.
Mac verifies the pinned sender signature, unwraps the key, verifies the payload
tag and UTF-8/size, then persists replay state BEFORE displaying or acknowledging.
Unknown sender, recipient, pairing, metadata edits or tag/signature failures
fail closed and do not advance replay state.

## Receipts / replays

Success: 202 (new) or 200 (exact duplicate). Response has exactly accepted:true,
duplicate:boolean, message_id, pairing_id, counter, digest, signature.
digest is Base64(SHA256(S)); signature = Ed25519_Mac(R), where

    R = bridge-v2/receipt|pairing_id|message_id|counter|digest|0-or-1

Last value is 1 for duplicate, 0 otherwise. Android verifies all fields, digest
and Mac signature before saying "Mac decrypted". Forged HTTP receipts fail.
HTTP errors/invalid ciphertext: no decrypted text in any network response.
Receipts contain no plaintext. The dashboard is loopback-only.

Mac stores the active pair, highest accepted counter and last 128 message-ID /
digest / counter entries in Keychain. Exact known duplicates are acknowledged
without redisplay. Counter <= high-water mark with no exact cached match fails,
including after restart. This deliberately rejects reordered messages.
One pair may accept at most counter 1000000; re-pair for a new epoch.
Re-pairing creates a fresh pairing_id and counter epoch; previous envelopes fail.

Shared fixtures are in paired-test-vectors.json. Deterministic private keys in
that file are PUBLIC TEST INPUTS ONLY. Production keys are always random.
No primitives, KEMs or KDFs are implemented in application code.
