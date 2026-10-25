#!/usr/bin/env python3
"""Local observation lab, not the production relay. Python standard library only."""

import argparse
import base64
import binascii
from collections import deque
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
from pathlib import Path
import re
import subprocess
import threading
import uuid

MAX_BODY = 32_768
MAX_TEXT = 4096
MAX_MESSAGES = 1000
ASSETS = Path(__file__).parent / "receiver_ui"
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(n) for n in (
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
))


def private_address(address):
    try:
        value = ipaddress.ip_address(address)
        return any(value in network for network in PRIVATE_NETWORKS)
    except ValueError:
        return False


def canonical_uuid(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError("Invalid UUID")


def decoded(value, maximum):
    if not isinstance(value, str) or len(value) > maximum * 2 + 8:
        raise ValueError("Invalid Base64 size")
    try:
        result = base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("Invalid Base64") from None
    if len(result) > maximum or base64.b64encode(result).decode("ascii") != value:
        raise ValueError("Noncanonical Base64")
    return result


def validate_message(body):
    if not isinstance(body, dict) or type(body.get("lab_version")) is not int or body["lab_version"] != 1:
        raise ValueError("Unsupported lab version")
    canonical_uuid(body.get("message_id"))
    mode = body.get("mode")
    if mode == "plaintext":
        if set(body) != {"lab_version", "mode", "message_id", "text"}:
            raise ValueError("Unexpected plaintext fields")
        text = body["text"]
        if not isinstance(text, str) or not 1 <= len(text.encode("utf-8")) <= MAX_TEXT:
            raise ValueError("Invalid sample size")
    elif mode == "encrypted":
        if set(body) != {"lab_version", "mode", "algorithm", "message_id", "sender_id",
                         "recipient_id", "message_type", "counter", "nonce", "aad", "ciphertext"}:
            raise ValueError("Unexpected encrypted fields")
        canonical_uuid(body["sender_id"])
        if (body["algorithm"] != "AES-256-GCM" or body["recipient_id"] != "laptop-observer"
                or body["message_type"] != "text" or type(body["counter"]) is not int
                or not 1 <= body["counter"] <= 10000):
            raise ValueError("Invalid encrypted metadata")
        if len(decoded(body["nonce"], 12)) != 12:
            raise ValueError("Invalid nonce length")
        if not 17 <= len(decoded(body["ciphertext"], MAX_TEXT + 16)) <= MAX_TEXT + 16:
            raise ValueError("Invalid ciphertext size")
        expected = (f'bridge-lab|1|{body["sender_id"]}|laptop-observer|text|'
                    f'{body["counter"]}|{body["message_id"]}').encode("utf-8")
        if decoded(body["aad"], 256) != expected:
            raise ValueError("Associated data does not match metadata")
    else:
        raise ValueError("Unknown mode")
    return body


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field")
        result[key] = value
    return result


class ObservationStore:
    def __init__(self):
        self.lock = threading.Lock()
        self.events = deque(maxlen=40)
        self.digests = {}
        self.counts = {"plaintext": 0, "encrypted": 0, "duplicates": 0}
        self.counts["paired"] = 0

    def paired(self, body, wire_bytes, result):
        with self.lock:
            if result["receipt"]["duplicate"]:
                self.counts["duplicates"] += 1
                return
            self.counts["paired"] += 1
            self.events.appendleft({"received_at": datetime.now(timezone.utc).isoformat(),
                                    "wire_bytes": wire_bytes, "body": body,
                                    "decrypted_text": result["decrypted_text"]})

    def accept(self, body, wire_bytes):
        digest = hashlib.sha256(json.dumps(body, sort_keys=True, separators=(",", ":"),
                                          ensure_ascii=True).encode("utf-8")).digest()
        with self.lock:
            previous = self.digests.get(body["message_id"])
            if previous is not None:
                if previous != digest:
                    raise ValueError("Message ID reused with a different body")
                self.counts["duplicates"] += 1
                return True
            if len(self.digests) >= MAX_MESSAGES:
                raise ValueError("Session full; restart the receiver")
            self.digests[body["message_id"]] = digest
            self.counts[body["mode"]] += 1
            self.events.appendleft({"received_at": datetime.now(timezone.utc).isoformat(),
                                    "wire_bytes": wire_bytes, "body": body})
            return False

    def snapshot(self):
        with self.lock:
            return {"counts": dict(self.counts), "events": list(self.events)}


class LabServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 32

    def __init__(self, address):
        self.store = ObservationStore()
        self.crypto = None
        super().__init__(address, Handler)

    def recipient(self):
        # Startup is explicit in main; unit tests retain their v1-only receiver.
        if self.crypto is None:
            raise ValueError("Start the Mac recipient with tools/laptop_receiver.py")
        return self.crypto


class Handler(BaseHTTPRequestHandler):
    server_version = "BridgeLab/1"

    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def log_message(self, *_args):
        # Message contents, client addresses and requested paths never go to logs.
        pass

    def reply(self, status, data, content_type="application/json; charset=utf-8"):
        raw = json.dumps(data).encode("utf-8") if isinstance(data, dict) else data
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        self.end_headers()
        self.wfile.write(raw)

    def allowed_origin(self):
        origin = self.headers.get("Origin")
        port = self.server.server_port
        return origin is None or origin in {f"http://127.0.0.1:{port}", f"http://localhost:{port}"}

    def do_GET(self):
        if not private_address(self.client_address[0]) or not self.allowed_origin():
            self.reply(403, {"error": "Local lab only"})
            return
        if self.path == "/health":
            self.reply(200, {"service": "bridge-learning-observer", "lab_version": 1})
            return
        # A peer may POST an observation, but only the Mac can read the dashboard.
        if not ipaddress.ip_address(self.client_address[0]).is_loopback:
            self.reply(403, {"error": "Open the dashboard on the Mac using 127.0.0.1"})
            return
        if self.headers.get("Host") not in {f"127.0.0.1:{self.server.server_port}",
                                             f"localhost:{self.server.server_port}"}:
            self.reply(403, {"error": "Use the loopback dashboard address"})
            return
        if self.path == "/api/events":
            self.reply(200, self.server.store.snapshot())
            return
        if self.path == "/api/pairing/status":
            try:
                result = self.server.recipient().call("status")
                result["addresses"] = local_addresses()
                self.reply(200, result)
            except ValueError:
                self.reply(503, {"error": "Mac recipient unavailable. Restart receiver / unlock Keychain."})
            return
        assets = {"/": ("index.html", "text/html; charset=utf-8"),
                  "/viewer.js": ("viewer.js", "text/javascript; charset=utf-8"),
                  "/style.css": ("style.css", "text/css; charset=utf-8")}
        if self.path not in assets:
            self.reply(404, {"error": "Unknown route"})
            return
        filename, content_type = assets[self.path]
        self.reply(200, (ASSETS / filename).read_bytes(), content_type)

    def do_POST(self):
        if not private_address(self.client_address[0]) or not self.allowed_origin():
            self.reply(403, {"error": "Local lab only"})
            return
        if self.path not in {"/message", "/pairing/complete", "/paired-message", "/api/pairing/new"}:
            self.reply(404, {"error": "Unknown route"})
            return
        if self.path == "/api/pairing/new" and (
                not ipaddress.ip_address(self.client_address[0]).is_loopback
                or self.headers.get("Host") not in {f"127.0.0.1:{self.server.server_port}",
                                                    f"localhost:{self.server.server_port}"}
                or self.headers.get("Origin") not in {f"http://127.0.0.1:{self.server.server_port}",
                                                      f"http://localhost:{self.server.server_port}"}):
            self.reply(403, {"error": "Create pairing on the Mac's local dashboard only"})
            return
        if self.headers.get_content_type() != "application/json":
            self.reply(415, {"error": "Expected JSON"})
            return
        if self.headers.get("Transfer-Encoding") is not None:
            self.reply(400, {"error": "Use Content-Length"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 1 <= length <= MAX_BODY:
                self.reply(413, {"error": "Invalid body size"})
                return
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ValueError("Incomplete body")
            body = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
            if self.path == "/message":
                body = validate_message(body)
            elif not isinstance(body, dict):
                raise ValueError("Expected object")
        except (ValueError, TypeError, KeyError, AttributeError, UnicodeError, RecursionError):
            self.reply(400, {"error": "Invalid lab envelope"})
            return
        except TimeoutError:
            self.reply(408, {"error": "Request timed out"})
            return
        if self.path != "/message":
            try:
                operation = {"/pairing/complete": "pair", "/paired-message": "receive",
                             "/api/pairing/new": "new_pairing"}[self.path]
                result = self.server.recipient().call(operation, body)
                if self.path == "/paired-message":
                    self.server.store.paired(body, len(raw), result)
                    receipt = result["receipt"]
                    self.reply(200 if receipt["duplicate"] else 202, receipt)
                else:
                    self.reply(200, result)
            except (ValueError, KeyError):
                self.reply(400, {"error": "Pairing, signature, decryption, replay or Mac storage check failed"})
            return
        try:
            duplicate = self.server.store.accept(body, len(raw))
        except ValueError:
            self.reply(409, {"error": "Conflicting message ID or session full"})
            return
        self.reply(200 if duplicate else 202, {"accepted": True, "duplicate": duplicate})


def local_addresses():
    try:
        result = subprocess.run(["/sbin/ifconfig"], capture_output=True, text=True, timeout=3)
        return sorted({ip for ip in re.findall(r"\binet (\d+\.\d+\.\d+\.\d+)", result.stdout)
                       if private_address(ip) and not ip.startswith("127.")})
    except (OSError, subprocess.TimeoutExpired):
        return []


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="0.0.0.0", help="Bind address; 0.0.0.0 accepts hotspot traffic")
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()
    if args.host != "0.0.0.0" and not private_address(args.host):
        parser.error("Bind only a private IPv4 address or 0.0.0.0")
    if not 1024 <= args.port <= 65535:
        parser.error("Use a port from 1024 to 65535")
    try:
        server = LabServer((args.host, args.port))
    except OSError:
        parser.exit(1, "Could not open the port. Is another lab receiver already running?\n")
    try:
        from mac_crypto import MacCrypto
        server.crypto = MacCrypto()
        # First launch/rebuild may require the owner's macOS Keychain approval.
        # Give the system prompt time; ordinary network operations retain 10s.
        server.crypto.call("status", timeout=120)
    except (RuntimeError, ValueError, subprocess.TimeoutExpired):
        server.server_close()
        if server.crypto:
            server.crypto.close()
        parser.exit(1, "Mac recipient unavailable. Unlock the login Keychain and allow mac-endpoint access to its Bridge item if macOS asks. Apple Command Line Tools and macOS 14+ are required.\n")
    print(f"Bridge learning recipient · paired encryption + original HTTP lab\nDashboard: http://127.0.0.1:{args.port}", flush=True)
    for address in local_addresses():
        print(f"Possible Mac address for Android: {address}   port: {args.port}", flush=True)
    print("Sample text only; no bodies/keys logged. Ctrl+C stops. Display is in memory; pairing/replay state persists in Keychain.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        server.crypto.close()


if __name__ == "__main__":
    main()
