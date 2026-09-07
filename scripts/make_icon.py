#!/usr/bin/env python3
"""Draw the application icon from its geometry.

The design specifies the mark as numbers rather than as a picture — a barbell over a ring, five
rounded rectangles and one annulus — and says so in as many words: *this is geometry, not rendered
art*. So this renders it, at every size, rather than shrinking one export of it.

**The grid is 1024.** Every `em` fraction on the design canvas times 1024 is the number in its
caption to within half a unit (0.315 x 1024 = 322.6, captioned 322); times 1000 it is not.

That is the only thing the grid settles, and *"redraws exactly at 16 px"* is a separate claim about
the shape rather than about the grid: nothing in the geometry lands on a pixel boundary there — the
bar is 1.08 px tall and the ring's stroke 1.23. Drawn straight, 16 px is a grey smear where the bar
should be. So the small sizes are **hinted**: every edge is snapped to a whole output pixel before
it is filled (`Canvas(hint=True)`), which is what the design's sentence asks for and what a
downscale of one export cannot give you.

The mark's colour is read out of `Colors.kt` rather than written here twice. See B-86.

    python3 scripts/make_icon.py            # writes ui/src/desktopMain/resources/icon/
    python3 scripts/make_icon.py --check    # regenerates into a temp dir and diffs

Pure standard library: it emits PNG and ICO itself, and calls `iconutil` for ICNS, which only
exists on macOS — the other two formats are written everywhere.
"""

from __future__ import annotations

import argparse
import filecmp
import pathlib
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib

GRID = 1024

# The shapes, in grid units, from B-86's table.
PLATE_W, PLATE_H, PLATE_R = 84, 179, 29
BAR_W, BAR_H, BAR_R = 322, 69, 35
GAP = 29           # clear space between each plate and the bar
ROD_W, ROD_H = 69, 118
RING_D, RING_STROKE = 358, 79
OVERLAP = 13       # the ring rides up over the rod by one stroke, so the mark is one object
TILE_R = 180

# "The whole group sits 20 units below centre — optical, not arithmetic," says the caption. The
# canvas achieves 10: its `padding-top: 0.02em` is 20.5 units, and with `justify-content: center`
# a top padding moves the centre by half of itself. Two numbers for one intent; the caption is the
# one that was written down on purpose, and a padding that shifts by half is the kind of thing that
# looks like a slip. Recorded in B-86 either way, and one line to change.
BELOW_CENTRE = 20

# Below this, an unhinted edge is a row of half-lit pixels rather than a line: at 16 px the bar is
# 1.08 px tall. Above it the shapes are wide enough that snapping would move them visibly instead.
HINT_UPTO = 48

SUPERSAMPLE = 4
SIZES = (16, 32, 48, 64, 128, 256, 512, 1024)
ICO_SIZES = (16, 32, 48, 64, 128, 256)
ICNS_SIZES = (16, 32, 64, 128, 256, 512, 1024)

GROUND = (0x0B, 0x10, 0x0F)

ROOT = pathlib.Path(__file__).resolve().parent.parent
COLORS_KT = ROOT / "ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/theme/Colors.kt"
OUT = ROOT / "ui/src/desktopMain/resources/icon"


def mark_colour() -> tuple[int, int, int]:
    """The theme's `primary`, read rather than copied.

    A second spelling of the same colour is a second thing to keep in step, and this one lives in a
    file nobody would think to open when changing a palette.
    """
    source = COLORS_KT.read_text()
    match = re.search(r"primary\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)", source)
    if not match:
        raise SystemExit(f"no `primary = Color(0xFF……)` in {COLORS_KT}")
    value = match.group(1)
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16)


class Canvas:
    """A grey coverage mask on the 1024 grid, supersampled and then boxed down.

    Coverage rather than colour: every shape is the same one colour, so what is being drawn is
    *how much of each pixel the mark covers*, and the antialiasing is one average at the end.
    """

    def __init__(self, size: int, scale: int, hint: bool = False) -> None:
        self.n = size * scale
        self.scale = size * scale / GRID
        self.mask = bytearray(self.n * self.n)
        # One output pixel, in supersample units. Snapping to this is what "hinted" means: an edge
        # laid on a pixel boundary is a line, and the same edge 0.4 px inside it is two grey rows.
        self.step = scale if hint else 0

    def _px(self, u: float) -> float:
        return u * self.scale

    def _snap(self, v: float, floor: float = 0.0) -> float:
        """`v` moved to the nearest output-pixel boundary, or left alone when not hinting."""
        if not self.step:
            return v
        return max(floor, round(v / self.step) * self.step)

    def rounded_rect(self, x: float, y: float, w: float, h: float, r: float) -> None:
        r = min(r, w / 2, h / 2)
        x0, y0, x1, y1 = self._px(x), self._px(y), self._px(x + w), self._px(y + h)
        rp = self._px(r)
        if self.step:
            # The edges go to whole pixels and the shape keeps at least one: a bar rounded away to
            # nothing is worse than a bar one pixel too thick.
            x0, y0 = self._snap(x0), self._snap(y0)
            x1 = max(self._snap(x1), x0 + self.step)
            y1 = max(self._snap(y1), y0 + self.step)
            rp = min(self._snap(rp), (x1 - x0) / 2, (y1 - y0) / 2)
        for py in range(max(0, int(y0)), min(self.n, int(y1) + 1)):
            cy = py + 0.5
            if not (y0 <= cy < y1):
                continue
            for px in range(max(0, int(x0)), min(self.n, int(x1) + 1)):
                cx = px + 0.5
                if not (x0 <= cx < x1):
                    continue
                # Inside the straight part, or inside one of the four corner discs.
                dx = max(x0 + rp - cx, 0.0, cx - (x1 - rp))
                dy = max(y0 + rp - cy, 0.0, cy - (y1 - rp))
                if dx * dx + dy * dy <= rp * rp:
                    self.mask[py * self.n + px] = 255

    def ring(self, cx: float, cy: float, diameter: float, stroke: float) -> None:
        outer = self._px(diameter / 2)
        inner = outer - self._px(stroke)
        px_cx, px_cy = self._px(cx), self._px(cy)
        if self.step:
            # A ring has three numbers a person sees — where it is, how big, how thick — and the
            # hole is the one that disappears first. Diameter and stroke each go to whole pixels,
            # the stroke keeping one and the hole keeping two, and the centre lands on a boundary
            # so both edges do.
            px_cx, px_cy = self._snap(px_cx), self._snap(px_cy)
            outer = max(self._snap(outer), 2 * self.step)
            inner = min(max(self._snap(inner), self.step), outer - self.step)
        lo_y = max(0, int(px_cy - outer))
        hi_y = min(self.n, int(px_cy + outer) + 1)
        for py in range(lo_y, hi_y):
            dy = py + 0.5 - px_cy
            for px in range(max(0, int(px_cx - outer)), min(self.n, int(px_cx + outer) + 1)):
                dx = px + 0.5 - px_cx
                d2 = dx * dx + dy * dy
                if inner * inner <= d2 <= outer * outer:
                    self.mask[py * self.n + px] = 255

    def downsample(self, size: int, scale: int) -> list[int]:
        """One box filter over each `scale` x `scale` block: the coverage of that pixel."""
        out = [0] * (size * size)
        area = scale * scale
        for y in range(size):
            for x in range(size):
                total = 0
                for sy in range(y * scale, (y + 1) * scale):
                    row = sy * self.n
                    for sx in range(x * scale, (x + 1) * scale):
                        total += self.mask[row + sx]
                out[y * size + x] = total // area
        return out


def draw(size: int) -> bytes:
    """One RGBA raster of the icon at `size` pixels."""
    scale = SUPERSAMPLE if size <= 256 else 2
    hint = size <= HINT_UPTO
    mark = Canvas(size, scale, hint)
    # The tile is never hinted: it is a rounded square filling the raster, its edges are already on
    # the boundary, and snapping its corner radius only flattens the corner.
    tile = Canvas(size, scale)

    group_w = PLATE_W + GAP + BAR_W + GAP + PLATE_W
    row_h = max(PLATE_H, BAR_H)
    group_h = row_h + ROD_H + RING_D - OVERLAP
    left = (GRID - group_w) / 2
    top = (GRID - group_h) / 2 + BELOW_CENTRE

    # The barbell: plate, bar, plate — the bar centred against the plates rather than aligned to
    # them, because it is thinner and the row's height is the plates'.
    mark.rounded_rect(left, top, PLATE_W, PLATE_H, PLATE_R)
    mark.rounded_rect(left + PLATE_W + GAP, top + (row_h - BAR_H) / 2, BAR_W, BAR_H, BAR_R)
    mark.rounded_rect(left + PLATE_W + GAP + BAR_W + GAP, top, PLATE_W, PLATE_H, PLATE_R)

    # The rod hangs from the bar's centre; square ends, because it disappears under the ring.
    mark.rounded_rect(GRID / 2 - ROD_W / 2, top + row_h, ROD_W, ROD_H, 0)

    # And the ring rides up over the rod by one overlap, so the three are one object.
    ring_top = top + row_h + ROD_H - OVERLAP
    mark.ring(GRID / 2, ring_top + RING_D / 2, RING_D, RING_STROKE)

    tile.rounded_rect(0, 0, GRID, GRID, TILE_R)

    mark_cov = mark.downsample(size, scale)
    tile_cov = tile.downsample(size, scale)
    r, g, b = mark_colour()

    pixels = bytearray()
    for i in range(size * size):
        ground_a = tile_cov[i]
        # The mark is clipped by the tile: a shape may not spill past the rounded corner.
        m = min(mark_cov[i], ground_a) / 255.0
        pr = round(GROUND[0] * (1 - m) + r * m)
        pg = round(GROUND[1] * (1 - m) + g * m)
        pb = round(GROUND[2] * (1 - m) + b * m)
        pixels += bytes((pr, pg, pb, ground_a))
    return bytes(pixels)


def png(size: int, rgba: bytes) -> bytes:
    raw = bytearray()
    stride = size * 4
    for y in range(size):
        raw.append(0)  # filter 0: none. The image is flat colour, so filtering buys nothing.
        raw += rgba[y * stride:(y + 1) * stride]

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def ico(images: dict[int, bytes]) -> bytes:
    """An ICO of PNG payloads, which Windows has read since Vista.

    The alternative is the BMP-with-AND-mask form, which is three times the bytes and exists for
    Windows XP.
    """
    entries, payloads, offset = b"", b"", 6 + 16 * len(images)
    for size in sorted(images):
        data = images[size]
        entries += struct.pack(
            "<BBBBHHII",
            size if size < 256 else 0,  # 0 means 256 in an ICO directory
            size if size < 256 else 0,
            0,
            0,
            1,
            32,
            len(data),
            offset,
        )
        payloads += data
        offset += len(data)
    return struct.pack("<HHH", 0, 1, len(images)) + entries + payloads


def write_all(out: pathlib.Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    rasters = {size: draw(size) for size in SIZES}
    pngs = {size: png(size, rasters[size]) for size in SIZES}

    (out / "icon.png").write_bytes(pngs[512])
    (out / "icon.ico").write_bytes(ico({s: pngs[s] for s in ICO_SIZES}))

    if shutil.which("iconutil") is None:
        print("no iconutil (not macOS): icon.icns not written", file=sys.stderr)
        return
    with tempfile.TemporaryDirectory() as tmp:
        iconset = pathlib.Path(tmp) / "kachok.iconset"
        iconset.mkdir()
        for size in ICNS_SIZES:
            (iconset / f"icon_{size}x{size}.png").write_bytes(pngs[size])
            if size * 2 in pngs:
                (iconset / f"icon_{size}x{size}@2x.png").write_bytes(pngs[size * 2])
        subprocess.run(
            ["iconutil", "-c", "icns", str(iconset), "-o", str(out / "icon.icns")],
            check=True,
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="regenerate and diff instead of writing")
    args = parser.parse_args()

    if not args.check:
        write_all(OUT)
        for path in sorted(OUT.iterdir()):
            print(f"{path.relative_to(ROOT)}  {path.stat().st_size} bytes")
        return 0

    with tempfile.TemporaryDirectory() as tmp:
        fresh = pathlib.Path(tmp) / "icon"
        write_all(fresh)
        stale = [
            name.name
            for name in sorted(fresh.iterdir())
            if not (OUT / name.name).exists() or not filecmp.cmp(name, OUT / name.name, shallow=False)
        ]
    if stale:
        print("the committed icon does not match the geometry: " + ", ".join(stale), file=sys.stderr)
        print("run `python3 scripts/make_icon.py`", file=sys.stderr)
        return 1
    print(f"the icon matches its geometry ({len(SIZES)} sizes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
