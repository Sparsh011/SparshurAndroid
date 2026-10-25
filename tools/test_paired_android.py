#!/usr/bin/env python3
"""Real emulator -> Mac test. Transfers the one-time secret over trusted adb stdin."""
import json
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
ADB = Path.home() / 'Library/Android/sdk/platform-tools/adb'
ORIGIN = 'http://127.0.0.1:8787'


def run(*args, **kwargs):
    return subprocess.run([str(ADB), *args], check=True, **kwargs)


def main():
    run('install', '-r', str(ROOT / 'app/build/outputs/apk/debug/app-debug.apk'))
    run('install', '-r', str(ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
    request = urllib.request.Request(ORIGIN + '/api/pairing/new',
        data=json.dumps({'host': '10.0.2.2', 'port': 8787}).encode(),
        headers={'Content-Type': 'application/json', 'Origin': ORIGIN}, method='POST')
    with urllib.request.urlopen(request, timeout=15) as response:
        pairing = json.load(response)
    # Never put a secret into argv, stdout, the repository, or the LAN.
    run('shell', 'run-as', 'com.sparsh.sparshur', 'tee', 'cache/bridge-pairing-test.json',
        input=pairing['bundle'].encode(), stdout=subprocess.DEVNULL)
    result = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'receiverHost', '10.0.2.2',
        'com.sparsh.sparshur.test/androidx.test.runner.AndroidJUnitRunner',
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if 'FAILURES!!!' in result.stdout or 'OK (' not in result.stdout:
        print(result.stdout[-8000:])
        raise SystemExit('Android integration tests failed')
    print(next(line for line in result.stdout.splitlines() if line.startswith('OK (')))
    # A new Swift process must load identity/replay state from the real Keychain.
    raw = run('exec-out', 'run-as', 'com.sparsh.sparshur', 'cat', 'cache/bridge-paired-retry.json',
              stdout=subprocess.PIPE).stdout
    from mac_crypto import MacCrypto
    crypto = MacCrypto()
    try:
        replay = crypto.call('receive', json.loads(raw))
        assert replay['receipt']['duplicate'] is True and 'decrypted_text' not in replay
        print('Fresh Swift process loaded real Keychain state and suppressed the authenticated replay.')
    finally:
        crypto.close()
    run('shell', 'run-as', 'com.sparsh.sparshur', 'rm', '-f', 'cache/bridge-paired-retry.json',
        'cache/bridge-pairing-test.json', stdout=subprocess.DEVNULL)
    with urllib.request.urlopen(ORIGIN + '/api/events', timeout=5) as response:
        data = json.load(response)
    assert data['counts']['paired'] >= 2
    assert any(event.get('decrypted_text') and event['body']['mode'] == 'paired' for event in data['events'])
    print('Mac dashboard contains actual verified/decrypted Android observations. No plaintext in network receipts.')


if __name__ == '__main__':
    if '--reference' in sys.argv:
        main()
    else:
        # Default verification now exercises the actual signed native endpoint.
        import runpy
        sys.argv = [str(ROOT.parent / 'Sparshur/tools/test_android_native.py'), *
                    (arg for arg in sys.argv[1:] if arg != '--native')]
        runpy.run_path(sys.argv[0], run_name='__main__')
