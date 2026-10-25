"""Protocol, fuzz-style validation and real HTTP integration tests. No extra packages."""
import base64
from concurrent.futures import ThreadPoolExecutor
import copy
import http.client
import json
from pathlib import Path
import random
import threading
import unittest
import uuid

from laptop_receiver import LabServer, MAX_MESSAGES, ObservationStore, validate_message


VECTORS = json.loads((Path(__file__).parent.parent / "spec/test-vectors.json").read_text())


def readable(text="Hello laptop!"):
    return {"lab_version": 1, "mode": "plaintext", "message_id": str(uuid.uuid4()), "text": text}


class ValidationTests(unittest.TestCase):
    def test_shared_encrypted_vector(self):
        value = VECTORS["lab"]["envelope"]
        self.assertEqual(validate_message(value), value)
        self.assertEqual(base64.b64decode(value["aad"]).decode(), VECTORS["lab"]["aad_utf8"])
        self.assertNotIn(VECTORS["lab"]["plaintext"], json.dumps(value))

    def test_encrypted_field_corruption(self):
        mutations = {"algorithm": "AES-CBC", "lab_version": True, "counter": True,
                     "recipient_id": "other", "message_type": "file", "nonce": "AA==",
                     "ciphertext": "AA==", "aad": "YWJj", "sender_id": "not-uuid",
                     "message_id": "not-uuid", "mode": "other"}
        for field, value in mutations.items():
            with self.subTest(field=field):
                body = copy.deepcopy(VECTORS["lab"]["envelope"])
                body[field] = value
                with self.assertRaises(ValueError):
                    validate_message(body)
        body = dict(VECTORS["lab"]["envelope"], text="must never leak")
        with self.assertRaises(ValueError):
            validate_message(body)

    def test_unicode_size_and_missing_fields(self):
        validate_message(readable("नमस्ते 🔐"))
        for text in ("", "🔐" * 1025):
            with self.assertRaises(ValueError):
                validate_message(readable(text))
        for field in VECTORS["lab"]["envelope"]:
            body = dict(VECTORS["lab"]["envelope"])
            del body[field]
            with self.assertRaises(ValueError):
                validate_message(body)

    def test_fuzz_style_schema_mutations_fail_closed(self):
        rng = random.Random(711)
        invalid_values = [None, True, False, [], {}, 0, -1, 1.5, "", "\ud800", "x" * 40000]
        for _ in range(500):
            body = copy.deepcopy(VECTORS["lab"]["envelope"])
            body[rng.choice(list(body))] = rng.choice(invalid_values)
            try:
                validate_message(body)
            except (ValueError, UnicodeError):
                continue
            self.fail("A malformed envelope was accepted")

    def test_dedup_conflict_and_bounded_capacity(self):
        store = ObservationStore()
        body = readable()
        self.assertFalse(store.accept(body, 100))
        self.assertTrue(store.accept(body, 100))
        with self.assertRaises(ValueError):
            store.accept(dict(body, text="changed"), 100)
        for _ in range(MAX_MESSAGES - 1):
            store.accept(readable(), 100)
        with self.assertRaises(ValueError):
            store.accept(readable(), 100)
        self.assertEqual(len(store.snapshot()["events"]), 40)


class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = LabServer(("127.0.0.1", 0))
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def request(self, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            return response.status, response.read(), response.getheader("Content-Security-Policy")
        finally:
            connection.close()

    def post(self, body):
        return self.request("POST", "/message", json.dumps(body).encode(), {"Content-Type": "application/json"})

    def test_health_dashboard_and_assets(self):
        status, body, _ = self.request("GET", "/health")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["service"], "bridge-learning-observer")
        for path in ("/", "/viewer.js", "/style.css", "/api/events"):
            status, _, csp = self.request("GET", path)
            self.assertEqual(status, 200)
            self.assertIn("frame-ancestors 'none'", csp)

    def test_both_paths_arrive_and_duplicates_do_not_redisplay(self):
        encrypted = copy.deepcopy(VECTORS["lab"]["envelope"])
        plain = readable("a clearly invented comparison")
        self.assertEqual(self.post(encrypted)[0], 202)
        self.assertEqual(self.post(plain)[0], 202)
        status, result, _ = self.post(plain)
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(result)["duplicate"])
        self.assertEqual(self.post(dict(plain, text="changed"))[0], 409)
        _, payload, _ = self.request("GET", "/api/events")
        events = json.loads(payload)["events"]
        encrypted_event = next(e for e in events if e["body"]["mode"] == "encrypted")
        self.assertNotIn("text", encrypted_event["body"])

    def test_concurrent_duplicates_accept_only_once(self):
        body = readable()
        with ThreadPoolExecutor(max_workers=8) as executor:
            statuses = list(executor.map(lambda _: self.post(body)[0], range(16)))
        self.assertEqual(statuses.count(202), 1)
        self.assertEqual(statuses.count(200), 15)

    def test_malformed_transport_and_cross_origin_are_rejected(self):
        self.assertEqual(self.request("POST", "/message", b"{}", {"Content-Type": "text/plain"})[0], 415)
        self.assertEqual(self.request("POST", "/message", b"{", {"Content-Type": "application/json"})[0], 400)
        self.assertEqual(self.request("POST", "/message", b'{"a":1,"a":2}', {"Content-Type": "application/json"})[0], 400)
        self.assertEqual(self.request("POST", "/message", b"x" * 32769, {"Content-Type": "application/json"})[0], 413)
        self.assertEqual(self.request("GET", "/api/events", headers={"Origin": "https://unrelated.example"})[0], 403)
        self.assertEqual(self.request("GET", "/api/events", headers={"Host": "unrelated.example"})[0], 403)
        self.assertEqual(self.request("GET", "/../../etc/passwd")[0], 404)

    def test_pairing_secret_creation_is_post_and_same_origin_only(self):
        self.assertEqual(self.request("GET", "/api/pairing/new")[0], 404)
        body = json.dumps({"host": "127.0.0.1", "port": 8787}).encode()
        self.assertEqual(self.request("POST", "/api/pairing/new", body,
                                     {"Content-Type": "application/json"})[0], 403)
        self.assertEqual(self.request("POST", "/api/pairing/new", body,
                                     {"Content-Type": "application/json", "Origin": "https://unrelated.example"})[0], 403)

    def test_paired_network_receipt_never_contains_decrypted_text(self):
        fixture = json.loads((Path(__file__).parent.parent / "spec/paired-test-vectors.json").read_text())
        class FakeRecipient:
            # Routing unit test only, on an ephemeral test server; real crypto has separate tests.
            def call(self, operation, _body):
                if operation != "receive":
                    raise ValueError("Unexpected operation")
                return {"receipt": fixture["receipt"], "decrypted_text": fixture["plaintext"]}
        self.server.crypto = FakeRecipient()
        try:
            status, response, _ = self.request("POST", "/paired-message",
                json.dumps(fixture["message"]).encode(), {"Content-Type": "application/json"})
            self.assertEqual(status, 202)
            self.assertNotIn(fixture["plaintext"], response.decode())
            self.assertNotIn("decrypted_text", json.loads(response))
            self.assertEqual(self.server.store.snapshot()["events"][0]["decrypted_text"], fixture["plaintext"])
        finally:
            self.server.crypto = None


if __name__ == "__main__":
    unittest.main()
