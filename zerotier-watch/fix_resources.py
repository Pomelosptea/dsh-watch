"""Rebuild an APK with exact-case resource entries copied from the original.

Windows filesystems collapse names that differ only by case (e.g. res/Ln.xml and
res/LN.xml). Apktool therefore writes only one of them. Android's AssetManager is
case-sensitive and crashes when the other path is missing, so after apktool build
we rebuild the zip: keep the patched code/manifest, but take every res/* entry
byte-for-byte from the original APK.
"""
import sys
import zipfile
from pathlib import Path

if len(sys.argv) != 4:
    raise SystemExit("usage: fix_resources.py <built.apk> <original.apk> <out.apk>")

built, original, out = map(Path, sys.argv[1:])
with zipfile.ZipFile(built, "r") as src, zipfile.ZipFile(original, "r") as orig, \
        zipfile.ZipFile(out, "w") as dst:
    # Keep everything except resources (classes, manifest, assets, lib, arsc).
    for info in src.infolist():
        if info.filename.startswith("res/"):
            continue
        if info.filename.startswith("META-INF/"):
            continue
        dst.writestr(info, src.read(info.filename))
    # Exact-case resources from the untouched original.
    for info in orig.infolist():
        if not info.filename.startswith("res/"):
            continue
        dst.writestr(info, orig.read(info.filename))
print(f"wrote {out}")
