#!/usr/bin/env python3
"""Read-only audit of actual APK DEX definitions, resources, manifests and derived PNG pixels."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "app/build"
ACTIVITY = "com.fantadrom.copperscroll.demo.IconComparisonActivity"
PREFIX = "Lcom/fantadrom/copperscroll/demo/"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def definitions(data):
    assert data.startswith(b"dex\n"), "Expected DEX"
    uint = lambda offset: struct.unpack_from("<I", data, offset)[0]
    strings = []
    for i in range(uint(56)):
        pos = uint(uint(60) + i * 4)
        while data[pos] & 0x80:
            pos += 1
        pos += 1  # Skip the ULEB128 UTF-16 length; descriptors below are ASCII.
        strings.append(data[pos:data.index(0, pos)].decode("utf-8", errors="replace"))
    types = [strings[uint(uint(68) + i * 4)] for i in range(uint(64))]
    return {types[uint(uint(100) + i * 32)] for i in range(uint(96))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aapt2", required=True, type=Path, help="SDK build-tools/37.0.0/aapt2[.exe]")
    args = parser.parse_args()
    names = [e["file"] for e in json.loads((ROOT / "docs/reference/icons-demo/manifest.json").read_text())["images"]]
    results = []
    for variant in ("debug", "release", "p1Release"):
        apk = BUILD / f"outputs/apk/{variant}/app-{variant}{'-unsigned' if variant == 'release' else ''}.apk"
        manifests = list((BUILD / f"intermediates/merged_manifests/{variant}").glob("*/AndroidManifest.xml"))
        assert len(manifests) == 1, manifests
        manifest = ET.parse(manifests[0]).getroot()
        activities = manifest.findall("application/activity")
        demo = [a for a in activities if a.get(ANDROID + "name") == ACTIVITY]
        assert bool(demo) == (variant == "debug"), variant
        if demo:
            assert demo[0].get(ANDROID + "exported") == "true" and not demo[0].findall("intent-filter")
        badging = subprocess.check_output([str(args.aapt2), "dump", "badging", str(apk)], text=True)
        package = re.search(r"^package: name='([^']+)'", badging, re.M)[1]
        assert package == "com.fantadrom.copperscroll" + (".p1runtime" if variant == "p1Release" else "")
        assert re.search(r"^launchable-activity: name='([^']+)'", badging, re.M)[1] == "com.fantadrom.copperscroll.MainActivity"
        resources = subprocess.check_output([str(args.aapt2), "dump", "resources", str(apk)], text=True)
        assert ("icon_demo_title" in resources) == (variant == "debug"), variant
        if variant != "debug":
            assert "icon_demo_" not in resources and "demo_bank_" not in resources and "demo_passport_" not in resources and "demo_papers_" not in resources
        with zipfile.ZipFile(apk) as archive:
            classes = set().union(*(definitions(archive.read(n)) for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n)))
            assert "Lcom/fantadrom/copperscroll/MainActivity;" in classes
            demo_classes = {c for c in classes if c.startswith(PREFIX)}
            assert ("L" + ACTIVITY.replace(".", "/") + ";" in demo_classes) == (variant == "debug")
            if variant != "debug":
                assert not demo_classes
            for name in names:
                entries = [n for n in archive.namelist() if n.startswith("res/") and n.endswith("/" + name)]
                assert len(entries) == (1 if variant == "debug" else 0), (variant, name)
                if entries:
                    assert "drawable-nodpi" in entries[0], entries[0]
                    with Image.open(io.BytesIO(archive.read(entries[0]))) as packed, Image.open(ROOT / "app/src/debug/res/drawable-nodpi" / name) as source:
                        rgba = packed.convert("RGBA")
                        assert rgba.size == (256, 256) and rgba.getchannel("A").getextrema() == (0, 255)
                        assert rgba.tobytes() == source.tobytes(), name
        results.append({"variant": variant, "applicationId": package, "bytes": apk.stat().st_size,
                        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "demoClassDefinitions": len(demo_classes),
                        "demoImages": 6 if variant == "debug" else 0, "launcher": "MainActivity"})
    print(json.dumps({"artifactAudit": "PASS", "runtime": "NOT_RUN", "apks": results}, indent=2))


if __name__ == "__main__":
    main()
