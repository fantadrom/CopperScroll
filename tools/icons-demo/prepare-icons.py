#!/usr/bin/env python3
"""Mechanical full-canvas PNG reduction only. Never writes master images or Gradle inputs implicitly."""
import argparse
import hashlib
import io
import json
from pathlib import Path

from PIL import Image, __version__ as pillow_version

ROOT = Path(__file__).resolve().parents[2]
MASTERS = ROOT / "docs/reference/icons-demo"
OUTPUT = ROOT / "app/src/debug/res/drawable-nodpi"
FILES = (
    "demo_bank_simple.png", "demo_bank_detailed.png",
    "demo_passport_simple.png", "demo_passport_detailed.png",
    "demo_papers_simple.png", "demo_papers_detailed.png",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Validate masters and existing derived pixels without writing")
    args = parser.parse_args()
    entries = json.loads((MASTERS / "manifest.json").read_text(encoding="utf-8"))["images"]
    assert len(entries) == len(FILES) and {e["file"] for e in entries} == set(FILES), "Unexpected master manifest"
    metadata = {e["file"]: e for e in entries}
    results = []
    for name in FILES:
        source = MASTERS / name
        info = metadata[name]
        data = source.read_bytes()
        assert len(data) == info["bytes"] and hashlib.sha256(data).hexdigest() == info["sha256"], name
        with Image.open(io.BytesIO(data)) as image:
            assert image.format == "PNG" and image.mode == info["mode"] == "RGBA", name
            assert image.size == (info["width"], info["height"]) == (1254, 1254), name
            assert info["transparent_background"] and image.getchannel("A").getextrema() == (0, 255), name
            # Filter RGB*alpha and alpha together; unpremultiply afterwards to avoid dark edge halos.
            # Resize the full square, never crop alpha bounds or change composition per sample.
            derived = image.convert("RGBa").resize((256, 256), Image.Resampling.LANCZOS).convert("RGBA")
        path = OUTPUT / name
        if args.check:
            with Image.open(path) as existing:
                assert existing.mode == "RGBA" and existing.size == (256, 256), name
                assert existing.tobytes() == derived.tobytes(), f"Derived pixels differ: {name}"
        else:
            OUTPUT.mkdir(parents=True, exist_ok=True)
            derived.save(path, format="PNG", compress_level=9)
        assert derived.getchannel("A").getextrema() == (0, 255), name
        results.append({"file": name, "width": 256, "height": 256, "mode": "RGBA",
                        "alpha": [0, 255], "bytes": path.stat().st_size,
                        "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    print(json.dumps({"pillow": pillow_version, "check": args.check, "images": results}, indent=2))


if __name__ == "__main__":
    main()
