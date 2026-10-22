#!/usr/bin/env python3
"""Negative build-output audits. Never modifies or installs signed APKs."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parents[2]
release = root / 'app/build/outputs/apk/release/app-release-unsigned.apk'
harness = root / 'app/build/outputs/apk/p1Release/app-p1Release.apk'
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
classes = str(root / 'app/build/p1-apk-verifier/classes')
original = [hashlib.sha256(p.read_bytes()).hexdigest() for p in (release, harness)]

def run(a, b, success):
    p = subprocess.run([java, '-cp', classes, 'VerifyRuntimeApks', '--p6a-isolation', str(a), str(b)], capture_output=True, text=True, timeout=60)
    assert (p.returncode == 0) == success, p.stdout + p.stderr

run(release, harness, True)
with tempfile.TemporaryDirectory(prefix='p6a-isolation-') as owned:
    path = Path(owned) / 'mutated.apk'
    with zipfile.ZipFile(release) as src, zipfile.ZipFile(harness) as h, zipfile.ZipFile(path, 'w') as dst:
        for entry in src.infolist():
            dst.writestr(entry, h.read('AndroidManifest.xml') if entry.filename == 'AndroidManifest.xml' else src.read(entry))
    run(path, harness, False)
    run(harness, harness, False)
    run(release, release, False)
    with zipfile.ZipFile(release) as src, zipfile.ZipFile(path, 'w') as dst:
        for entry in src.infolist():
            if not (entry.filename.startswith('classes') and entry.filename.endswith('.dex')):
                dst.writestr(entry, src.read(entry))
    run(path, harness, False)
assert original == [hashlib.sha256(p.read_bytes()).hexdigest() for p in (release, harness)]
print('P6a isolation audit: positive + 4 negatives PASS; APKs unchanged; Android NOT_RUN')
