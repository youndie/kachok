#!/usr/bin/env python3
#
# Onest, cut down to Archivo's Cyrillic companion (B-165).
#
# Archivo, the face of every label and row name, has no Cyrillic at all, so the Material kit draws
# the Cyrillic runs of a text in Onest — chosen for its metrics beside Archivo's: the same x-height
# (0.527 against 0.526 of the em) and the same stems at 400, 500 and 600. Only the Cyrillic and the
# combining marks are kept; the Latin stays Archivo's, and a second Latin nobody draws is most of
# the file.
#
# **The vertical metrics are Archivo's.** A line's height is the tallest ascent and the deepest
# descent of the faces drawn on it, and Onest's (970 / -305) are taller than Archivo's (878 / -210):
# a dialog line with a Russian name in it would stand 2 px apart from the lines around it at 13 sp.
# Both fonts have 1000 units to the em, so the numbers are copied as they are.
#
# The name table is kept whole — the copyright and the licence (name IDs 0, 13, 14) are how the
# OFL's notice travels with the binary — and the source file is checked against the one recorded
# in B-165, so a rerun on another download says so instead of quietly differing.
#
#   python3 -m venv /tmp/venv && /tmp/venv/bin/pip install fonttools
#   curl -fL -o /tmp/onest/Onest.ttf \
#     'https://raw.githubusercontent.com/google/fonts/4c1db3aec83c67dd223dd82a68c039cab30917a9/ofl/onest/Onest%5Bwght%5D.ttf'
#   /tmp/venv/bin/python -I scripts/subset_cyrillic_font.py /tmp/onest/Onest.ttf
#
import hashlib
import os
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

SOURCE_SHA256 = "966c5c29b4755da84b6854d5c21dd4eaa2420225d0e9874de602de176d4a9f31"

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONTS = os.path.join(HERE, "ui", "src", "desktopMain", "resources", "fonts")
OUT = os.path.join(FONTS, "OnestCyrillic.ttf")
ARCHIVO = os.path.join(FONTS, "Archivo.ttf")

# The Cyrillic block the run-splitter routes to this face (`CyrillicRuns.kt` names the same range),
# and the combining marks a stress mark on a Cyrillic letter is drawn with.
UNICODES = list(range(0x0400, 0x0500)) + list(range(0x0300, 0x0370))

# Onest's MVAR varies nothing but the cap height; a record for a metric copied from Archivo below
# would move it again with the weight.
VERTICAL_TAGS = {"hasc", "hdsc", "hlgp", "hcla", "hcld"}


def main(source: str) -> None:
    with open(source, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    if digest != SOURCE_SHA256:
        sys.exit(f"{source} is not the Onest 2.001 B-165 recorded: sha256 {digest}")

    font = TTFont(source)
    options = subset.Options()
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.notdef_outline = True
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=UNICODES)
    subsetter.subset(font)

    archivo = TTFont(ARCHIVO)
    if archivo["head"].unitsPerEm != font["head"].unitsPerEm:
        sys.exit("the two fonts' ems differ; the vertical metrics cannot be copied as they are")
    for table, fields in (
        ("hhea", ("ascent", "descent", "lineGap")),
        ("OS/2", ("sTypoAscender", "sTypoDescender", "sTypoLineGap", "usWinAscent", "usWinDescent")),
    ):
        for field in fields:
            setattr(font[table], field, getattr(archivo[table], field))
    if "MVAR" in font:
        mvar = font["MVAR"].table
        mvar.ValueRecord = [r for r in mvar.ValueRecord if r.ValueTag not in VERTICAL_TAGS]
        mvar.ValueCount = len(mvar.ValueRecord)

    # The source's own timestamp, so a rerun on the same file writes the same bytes.
    font.recalcTimestamp = False
    font.save(OUT)
    size = os.path.getsize(OUT)
    kept = sum(1 for cp in TTFont(OUT).getBestCmap() if 0x0400 <= cp < 0x0500)
    print(f"subset to {OUT} ({size} bytes), {kept} Cyrillic code points")
    # A subset that is most of the original kept the Latin it was not asked for.
    if size >= 100_000:
        sys.exit(f"the subset is {size} bytes — it kept glyphs it was not asked for")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: subset_cyrillic_font.py <Onest[wght].ttf>")
    main(sys.argv[1])
