#!/usr/bin/env python3
"""Negative checks for the build-output audit. No device and no changes to the signed APKs."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'app/build/outputs/apk/p1Release/app-p1Release.apk'
TEST = ROOT / 'app/build/outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk'
DEBUG = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
CLASSES = ROOT / 'app/build/p1-apk-verifier/classes'
GOLDEN = ROOT / 'app/src/test/resources/p1/settings-default.hex'
JAVA = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def rewrite(source, dest, replacements):
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(dest, 'w') as out:
        for entry in src.infolist():
            out.writestr(entry, replacements.get(entry.filename, src.read(entry)))

def main():
    original = {p: digest(p) for p in [TARGET, TEST, DEBUG]}
    (ROOT / 'work').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='p1-apk-audit-', dir=ROOT/'work') as tmp:
        tmp = Path(tmp)
        def rejected(label, target, test, expected):
            result = subprocess.run([JAVA, '-cp', str(CLASSES), 'VerifyRuntimeApks',
                str(target), str(test), str(GOLDEN), str(tmp/'audit.txt')],
                text=True, capture_output=True, timeout=30)
            assert result.returncode != 0 and expected in result.stderr, label
            print(label + ': correctly rejected')
        rejected('debug substituted for release-derived target', DEBUG, TEST, 'APK entry missing')
        with zipfile.ZipFile(TARGET) as target:
            dex = target.read('classes.dex')
            data = bytearray(target.read('com/ibm/icu/impl/data/icudata/nfc.nrm')); data[-1] ^= 1
        duplicate = tmp/'duplicate-backend.apk'
        rewrite(TEST, duplicate, {})
        with zipfile.ZipFile(duplicate, 'a') as out:
            out.writestr('classes99.dex', dex, compress_type=zipfile.ZIP_DEFLATED)
        rejected('production backend injected into test APK', TARGET, duplicate, 'second backend implementation')
        corrupt = tmp/'corrupt-unicode.apk'
        rewrite(TARGET, corrupt, {'com/ibm/icu/impl/data/icudata/nfc.nrm': bytes(data)})
        rejected('changed pinned NFC data', corrupt, TEST, 'Pinned ICU data hash mismatch')
    assert all(digest(p) == value for p, value in original.items())
    print('APK audit negative checks: PASS; original signed APKs unchanged; runtime NOT RUN')

if __name__ == '__main__':
    main()
