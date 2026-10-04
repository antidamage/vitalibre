#!/usr/bin/env python3
"""Build the app icon set from one image.

The icon is a supplied image, not drawn here. The master is
`App/Assets.xcassets/AppIcon.appiconset/icon-1024.png`; run this with a path to
install a new one, and it is written as the 1024x1024 iOS icon and resized to
the five Android launcher densities. The Android files are only written where
the Android tree exists (the `main` branch carries iOS alone).

    python tools/make_icon.py                  # rebuild from the master
    python tools/make_icon.py new-icon.png     # install a new master

Saved without alpha, as an App Store icon must be.
"""
import json
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ICON_SET = ROOT / "App/Assets.xcassets/AppIcon.appiconset"
MASTER = ICON_SET / "icon-1024.png"
ANDROID_RES = ROOT / "android/app/src/main/res"
PLAY_ICON = ROOT / "publisher/store-listing/android/icon-512.png"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

source = Path(sys.argv[1]) if len(sys.argv) > 1 else MASTER
master = Image.open(source).convert("RGB").resize((1024, 1024), Image.LANCZOS)

ICON_SET.mkdir(parents=True, exist_ok=True)
master.save(MASTER)
written = [MASTER]

for density, size in DENSITIES.items():
    target = ANDROID_RES / ("mipmap-" + density) / "ic_launcher.png"
    if not target.parent.is_dir():
        continue
    master.resize((size, size), Image.LANCZOS).save(target)
    written.append(target)

if ANDROID_RES.is_dir():
    PLAY_ICON.parent.mkdir(parents=True, exist_ok=True)
    master.resize((512, 512), Image.LANCZOS).convert("RGBA").save(PLAY_ICON)
    written.append(PLAY_ICON)

json.dump({"images": [{"filename": "icon-1024.png", "idiom": "universal",
                       "platform": "ios", "size": "1024x1024"}],
           "info": {"author": "xcode", "version": 1}},
          open(ICON_SET / "Contents.json", "w"), indent=2)
json.dump({"info": {"author": "xcode", "version": 1}},
          open(ROOT / "App/Assets.xcassets/Contents.json", "w"), indent=2)

for target in written:
    print("wrote", target.relative_to(ROOT))
