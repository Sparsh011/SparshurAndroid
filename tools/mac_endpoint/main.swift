import CoreGraphics
import CoreImage
import CryptoKit
import Foundation
import Security

// Adapter only. Protocol and Keychain code are shared with the native Mac app.
func run() throws {
  let endpoint = try Endpoint(KeychainStore())
  while let line = readLine() {
    do {
      guard line.utf8.count <= 65536 else { throw Rejected.invalid }
      let request = try object(utf8(line))
      let operation = try text(request, "op")
      let body = request["body"] as? Object ?? [:]
      let value: Object
      switch operation {
      case "status": value = try endpoint.status()
      case "new_pairing": value = try endpoint.newPair(body)
      case "pair": value = try endpoint.pair(body)
      case "receive": value = try endpoint.receive(body)
      default: throw Rejected.invalid
      }
      print(String(data: try json(["ok": true, "value": value]), encoding: .utf8)!)
    } catch {
      // Never print crypto inputs, plaintext, keys, pairing secrets or exception details.
      print(
        "{\"ok\":false,\"error\":\"Pairing, authentication, replay check or protected storage failed.\"}"
      )
    }
    fflush(stdout)
  }
}
// Test-only fixtures use an isolated memory store, never the login Keychain.
func fixtureStore() throws -> MemoryStore {
  let store = MemoryStore()
  try store.save(
    json([
      "recipient_id": "33333333-3333-4333-8333-333333333333",
      "hpke_seed": Data(repeating: 0x11, count: 32).base64EncodedString(),
      "signing_seed": Data(repeating: 0x22, count: 32).base64EncodedString(),
    ]))
  return store
}
func testPair(_ endpoint: Endpoint, _ android: Curve25519.Signing.PrivateKey) throws -> (
  Object, Object
) {
  let created = try endpoint.newPair(["host": "127.0.0.1", "port": 8787], now: 1_000_000_000)
  let bundle = try object(utf8(text(created, "bundle")))
  let sender = "22222222-2222-4222-8222-222222222222"
  let t =
    try transcript(bundle) + "|" + sender + "|"
    + android.publicKey.rawRepresentation.base64EncodedString()
  let input = utf8("bridge-v2/pair|" + t)
  let request: Object = [
    "pairing_id": try text(bundle, "pairing_id"), "sender_id": sender,
    "sender_key": android.publicKey.rawRepresentation.base64EncodedString(),
    "proof": Data(
      HMAC<SHA256>.authenticationCode(
        for: input,
        using: SymmetricKey(data: try bytes(text(bundle, "secret"), 32...32)))
    ).base64EncodedString(),
    "signature": try android.signature(for: input).base64EncodedString(),
  ]
  return (created, request)
}
func testMessage(_ bundle: Object, _ android: Curve25519.Signing.PrivateKey, _ counter: Int) throws
  -> Object
{
  var m: Object = [
    "lab_version": 2, "mode": "paired", "suite": suiteName,
    "pairing_id": try text(bundle, "pairing_id"),
    "sender_id": "22222222-2222-4222-8222-222222222222",
    "recipient_id": try text(bundle, "recipient_id"), "message_id": UUID().uuidString.lowercased(),
    "message_type": "text", "counter": counter,
  ]
  let key = Data(repeating: 0x44, count: 32)  // Public test key; never used by runtime encryption.
  let box = try ChaChaPoly.seal(
    utf8("Paired hello from a public test vector! 🔐"),
    using: SymmetricKey(data: key), nonce: .init(data: Data(repeating: UInt8(counter), count: 12)),
    authenticating: utf8(aad(m)))
  var sender = try HPKE.Sender(
    recipientKey: Curve25519.KeyAgreement.PublicKey(
      rawRepresentation: bytes(text(bundle, "hpke_key"), 32...32)),
    ciphersuite: .Curve25519_SHA256_ChachaPoly, info: utf8("bridge-v2/key-wrap|" + aad(m)))
  m["nonce"] = Data(box.nonce).base64EncodedString()
  m["ciphertext"] = (box.ciphertext + box.tag).base64EncodedString()
  m["wrapped_key"] = try (sender.encapsulatedKey + sender.seal(key)).base64EncodedString()
  m["signature"] = try android.signature(for: utf8(signedMessage(m))).base64EncodedString()
  return m
}
func mustReject(_ body: () throws -> Void) {
  var rejected = false
  do { try body() } catch { rejected = true }
  precondition(rejected, "A negative crypto/protocol test was accepted")
}
func checkTest(_ value: Bool) { precondition(value, "Crypto test failed") }
func selfTests() throws {
  let storage = try fixtureStore()
  let endpoint = try Endpoint(storage)
  let android = try Curve25519.Signing.PrivateKey(
    rawRepresentation: Data(repeating: 0x33, count: 32))
  let (created, request) = try testPair(endpoint, android)
  let bundle = try object(utf8(text(created, "bundle")))
  var wrong = request
  wrong["proof"] = Data(repeating: 0, count: 32).base64EncodedString()
  mustReject { _ = try endpoint.pair(wrong, now: 1_000_000_001) }
  mustReject { _ = try endpoint.pair(request, now: 1_000_000_400) }
  let ack = try endpoint.pair(request, now: 1_000_000_001)
  checkTest(try json(ack) == json(endpoint.pair(request)))
  var retry = request
  retry["signature"] = try android.signature(
    for: utf8(
      "bridge-v2/pair|" + transcript(bundle) + "|22222222-2222-4222-8222-222222222222|"
        + android.publicKey.rawRepresentation.base64EncodedString())
  ).base64EncodedString()
  checkTest(try json(ack) == json(endpoint.pair(retry)))
  let png = try bytes(created["qr_png"] as! String, 1...1_000_000)
  let detector = CIDetector(
    ofType: CIDetectorTypeQRCode, context: CIContext(),
    options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])!
  let decodedQR = detector.features(in: CIImage(data: png)!).compactMap {
    ($0 as? CIQRCodeFeature)?.messageString
  }.first
  checkTest(decodedQR == (try text(created, "bundle")))
  let m = try testMessage(bundle, android, 1)
  let result = try endpoint.receive(m)
  checkTest(result["decrypted_text"] as? String == "Paired hello from a public test vector! 🔐")
  checkTest(try (endpoint.receive(m)["receipt"] as! Object)["duplicate"] as? Bool == true)
  checkTest(try endpoint.receive(m)["decrypted_text"] == nil)
  let restarted = try Endpoint(storage)
  checkTest(try (restarted.receive(m)["receipt"] as! Object)["duplicate"] as? Bool == true)
  mustReject { _ = try restarted.receive(testMessage(bundle, android, 1)) }
  let m2 = try testMessage(bundle, android, 2)
  var corrupted = m2
  var cipher = try bytes(text(m2, "ciphertext"), 17...4112)
  cipher[0] ^= 1
  corrupted["ciphertext"] = cipher.base64EncodedString()
  mustReject { _ = try restarted.receive(corrupted) }
  // A valid sender signature must not make an invalid payload tag acceptable.
  corrupted["signature"] = try android.signature(for: utf8(signedMessage(corrupted)))
    .base64EncodedString()
  mustReject { _ = try restarted.receive(corrupted) }
  let stranger = Curve25519.Signing.PrivateKey()
  var impersonated = m2
  impersonated["signature"] = try stranger.signature(for: utf8(signedMessage(impersonated)))
    .base64EncodedString()
  mustReject { _ = try restarted.receive(impersonated) }
  let badValues: [Any] = [NSNull(), true, -1, [], [:], "invalid"]
  for key in m2.keys {
    for value in badValues {
      var malformed = m2
      malformed[key] = value
      mustReject { _ = try restarted.receive(malformed) }
    }
  }
  _ = try restarted.receive(m2)  // Failed attempts did not consume counter 2.
  let newer = try testMessage(bundle, android, 3)
  _ = try restarted.receive(newer)
  mustReject { _ = try restarted.receive(testMessage(bundle, android, 2)) }
  let (newQR, newRequest) = try testPair(restarted, android)
  _ = newQR
  _ = try restarted.pair(newRequest, now: 1_000_000_001)
  mustReject { _ = try restarted.receive(newer) }
  print(
    "Swift tests passed: pairing, expiry, QR roundtrip, HPKE, payload tags, signatures, replays, restart and malformed fields."
  )
}
func emitVectors() throws {
  let endpoint = try Endpoint(fixtureStore())
  let android = try Curve25519.Signing.PrivateKey(
    rawRepresentation: Data(repeating: 0x33, count: 32))
  let (created, request) = try testPair(endpoint, android)
  let bundle = try object(utf8(text(created, "bundle")))
  let ack = try endpoint.pair(request, now: 1_000_000_001)
  let message = try testMessage(bundle, android, 1)
  let result = try endpoint.receive(message)
  let fixture: Object = [
    "warning": "PUBLIC TEST INPUTS ONLY. Isolated memory state. Never use these keys in the app.",
    "android_seed": Data(repeating: 0x33, count: 32).base64EncodedString(),
    "mac_hpke_seed": Data(repeating: 0x11, count: 32).base64EncodedString(),
    "content_key": Data(repeating: 0x44, count: 32).base64EncodedString(),
    "bundle": bundle, "pair_request": request, "pair_ack": ack,
    "message": message, "aad": try aad(message), "signed_message": try signedMessage(message),
    "receipt": result["receipt"]!, "plaintext": result["decrypted_text"]!,
  ]
  print(String(data: try json(fixture), encoding: .utf8)!)
}
do {
  if CommandLine.arguments.contains("--self-test") {
    try selfTests()
  } else if CommandLine.arguments.contains("--vectors") {
    try emitVectors()
  } else {
    try run()
  }
} catch {
  if CommandLine.arguments.contains("--self-test") { print("Self-test diagnostic: \(error)") }
  if case Rejected.keychainStatus(let status) = error {
    // Status codes are diagnostic metadata, never key material.
    fputs("Mac Keychain status: \(status)\n", stderr)
  }
  print(
    "{\"ok\":false,\"error\":\"Mac crypto or protected storage failed. Unlock the login keychain and restart the receiver.\"}"
  )
  exit(1)
}
