#!/usr/bin/env python3
"""Draws the banner a television shows for Tern: the seal and the name on ink.

  tools/make-banner.py [--font <file.ttf>]

A vector drawable cannot hold text, so the banner is a picture: 640 by 360 pixels, which is
320 by 180 dp on a television. The colours are read from values/colors.xml and the seal has the
proportions of the launcher icon. The name is set in Noto Sans Bold, which is under the SIL Open
Font License 1.1. Only its drawn letters end up in the picture, not the font.
"""
import argparse, math, re, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
OUT = RES / "drawable-xhdpi/banner.png"
FONT = "/usr/share/fonts/noto/NotoSans-Bold.ttf"
WIDTH, HEIGHT = 640, 360
FINER = 4

# The seal as fractions of its outer radius: ui/icons/Seal.kt, SealShape.
TILT = -8.0
RING, RING_WIDTH = 0.9083, 0.1833
INNER_RING, INNER_RING_WIDTH = 0.7, 0.0533
CHECK = [(-0.3833, 0.0167), (-0.1167, 0.2833), (0.4, -0.3333)]
CHECK_WIDTH = 0.2167

MARGIN = 56
SEAL_RADIUS = 76
GAP = 36
NAME_SIZE = 132


def colors():
    text = (RES / "values/colors.xml").read_text()
    found = dict(re.findall(r'<color name="(\w+)">#[0-9A-Fa-f]{2}([0-9A-Fa-f]{6})</color>', text))
    return tuple(tuple(int(found[name][i:i + 2], 16) for i in (0, 2, 4)) for name in ("icon_background", "icon_seal"))


def ring(draw, x, y, radius, width, color):
    outer = radius + width / 2
    draw.ellipse((x - outer, y - outer, x + outer, y + outer), outline=color, width=round(width))


def seal(draw, x, y, radius, color):
    ring(draw, x, y, radius * RING, radius * RING_WIDTH, color)
    ring(draw, x, y, radius * INNER_RING, radius * INNER_RING_WIDTH, color)
    turn = math.radians(TILT)
    points = [(x + (px * math.cos(turn) - py * math.sin(turn)) * radius, y + (px * math.sin(turn) + py * math.cos(turn)) * radius) for px, py in CHECK]
    width = radius * CHECK_WIDTH
    draw.line(points, fill=color, width=round(width), joint="curve")
    for px, py in points:
        draw.ellipse((px - width / 2, py - width / 2, px + width / 2, py + width / 2), fill=color)


def name_font(path, room):
    """The largest size at which the name fits the room it has."""
    size = NAME_SIZE * FINER
    while size > FINER:
        font = ImageFont.truetype(path, size)
        left, _, right, _ = font.getbbox("Tern", anchor="ls")
        if right - left <= room:
            return font, left
        size -= FINER
    raise SystemExit("the name does not fit")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--font", default=FONT)
    args = parser.parse_args()
    ground, paper = colors()
    # Drawn as coverage from 0 to 255, so the picture holds the two colours and their blends and nothing else.
    cover = Image.new("L", (WIDTH * FINER, HEIGHT * FINER), 0)
    draw = ImageDraw.Draw(cover)
    middle = HEIGHT * FINER / 2
    seal(draw, (MARGIN + SEAL_RADIUS) * FINER, middle, SEAL_RADIUS * FINER, 255)
    start = (MARGIN + 2 * SEAL_RADIUS + GAP) * FINER
    font, bearing = name_font(args.font, (WIDTH - MARGIN) * FINER - start)
    draw.text((start - bearing, middle), "Tern", font=font, fill=255, anchor="lm")
    picture = cover.resize((WIDTH, HEIGHT), Image.LANCZOS).convert("P")
    picture.putpalette([round(g + (p - g) * step / 255) for step in range(256) for g, p in zip(ground, paper)])
    OUT.parent.mkdir(parents=True, exist_ok=True)
    picture.save(OUT, optimize=True)
    print(f"wrote {OUT.relative_to(ROOT)}, {OUT.stat().st_size} bytes, name at {font.size // FINER} px")


if __name__ == "__main__":
    sys.exit(main())
