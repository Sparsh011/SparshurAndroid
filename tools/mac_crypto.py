"""Local RPC to the native Mac recipient. No network key/secret export."""
import json
from pathlib import Path
import selectors
import subprocess
import threading

PROJECT = Path(__file__).resolve().parent.parent


class MacCrypto:
    def __init__(self):
        binary = PROJECT / '.tools/mac-endpoint'
        sources = [PROJECT / 'tools/mac_endpoint/main.swift', *
                   (PROJECT.parent / 'Sparshur/Sparshur/Core').glob('*.swift')]
        if not binary.exists() or any(binary.stat().st_mtime < source.stat().st_mtime for source in sources):
            result = subprocess.run(['sh', str(PROJECT / 'tools/mac_endpoint/build.sh')],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=90)
            if result.returncode:
                raise RuntimeError('Swift recipient could not build. Install Apple Command Line Tools; macOS 14+ is required.')
        self.lock = threading.Lock()
        self.process = subprocess.Popen([str(binary)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, bufsize=0)
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.process.stdout, selectors.EVENT_READ)
        self.buffer = b''

    def call(self, operation, body=None, timeout=10):
        with self.lock:
            try:
                if self.process.poll() is not None:
                    raise ValueError('Mac recipient stopped')
                request = json.dumps({'op': operation, 'body': body or {}}, ensure_ascii=True).encode() + b'\n'
                self.process.stdin.write(request)
                while b'\n' not in self.buffer:
                    if not self.selector.select(timeout=timeout):
                        self.process.kill()
                        raise ValueError('Mac recipient timed out')
                    chunk = self.process.stdout.read(4096)
                    if not chunk:
                        raise ValueError('Mac recipient stopped')
                    self.buffer += chunk
                    if len(self.buffer) > 1_000_000:
                        self.process.kill()
                        raise ValueError('Unexpected recipient output')
                line, self.buffer = self.buffer.split(b'\n', 1)
                response = json.loads(line)
                if response.get('ok') is not True:
                    raise ValueError('Pairing, authentication, replay protection or Mac Keychain check failed.')
                return response['value']
            except (OSError, json.JSONDecodeError, KeyError):
                raise ValueError('Mac recipient unavailable; unlock the login Keychain and restart the receiver.') from None

    def close(self):
        self.process.terminate()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        self.selector.close()
        self.process.stdin.close()
        self.process.stdout.close()
