#!/usr/bin/env python3
"""Fill an Xcode AppIcon.appiconset with Bouclier's icon (macOS sizes).

usage: appicon.py <folder with icon_16.png ... icon_1024.png> <path/to/AppIcon.appiconset>
"""
import json
import os
import shutil
import sys

SIZES = [(16, 1), (16, 2), (32, 1), (32, 2), (128, 1), (128, 2), (256, 1), (256, 2), (512, 1), (512, 2)]


def main():
    src, dest = sys.argv[1], sys.argv[2]
    for name in os.listdir(dest):
        if name.endswith(".png"):
            os.remove(os.path.join(dest, name))
    images = []
    for points, scale in SIZES:
        px = points * scale
        filename = f"icon_{points}x{points}{'@2x' if scale == 2 else ''}.png"
        shutil.copyfile(os.path.join(src, f"icon_{px}.png"), os.path.join(dest, filename))
        images.append({"idiom": "mac", "scale": f"{scale}x", "size": f"{points}x{points}", "filename": filename})
    with open(os.path.join(dest, "Contents.json"), "w") as f:
        json.dump({"images": images, "info": {"author": "xcode", "version": 1}}, f, indent=2)
    print(f"App icon written to {dest}")


if __name__ == "__main__":
    main()
