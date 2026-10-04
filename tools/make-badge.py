#!/usr/bin/env python3
"""Draws the "Get it with Tern" badge that other projects put in their README, and the site's icon.

  tools/make-badge.py [--font-dir <folder with Noto Sans>]

646 by 250 pixels with the badge drawn inside a 41 pixel transparent margin, the way F-Droid's is, so
the two match when they sit side by side at the same height. The seal and its colours come from make-banner.py, which takes them from
the launcher icon. Noto Sans is under the SIL Open Font License 1.1; only its drawn letters end up
in the picture.
"""
import argparse, importlib.util, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "site/badge.png"
ICON = ROOT / "site/icon.png"
ICON_SIZE = 256
WIDTH, HEIGHT = 646, 250
MARGIN = 41
FINER = 4
EDGE = (166, 166, 166)
INK = (0, 0, 0)
WHITE = (255, 255, 255)

spec = importlib.util.spec_from_file_location("banner", ROOT / "tools/make-banner.py")
banner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(banner)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--font-dir", default="/usr/share/fonts/noto")
    args = parser.parse_args()
    fonts = Path(args.font_dir)
    ground, paper = banner.colors()
    w, h = WIDTH * FINER, HEIGHT * FINER
    picture = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(picture)
    m = MARGIN * FINER
    corner = 22 * FINER
    draw.rounded_rectangle((m, m, w - 1 - m, h - 1 - m), radius=corner, fill=EDGE)
    border = 2 * FINER
    draw.rounded_rectangle((m + border, m + border, w - 1 - m - border, h - 1 - m - border),
                           radius=corner - border, fill=INK)

    radius = 62 * FINER
    cx, cy = m + 24 * FINER + radius, h // 2
    draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=ground)
    banner.seal(draw, cx, cy, radius * 0.62, paper)

    space = (cx + radius + 20 * FINER, w - m - 24 * FINER)
    small = ImageFont.truetype(str(fonts / "NotoSans-Medium.ttf"), 31 * FINER)
    large = ImageFont.truetype(str(fonts / "NotoSans-Bold.ttf"), 96 * FINER)
    gap = 6 * FINER
    top = draw.textbbox((0, 0), "GET IT WITH", font=small, anchor="lt")
    name = draw.textbbox((0, 0), "Tern", font=large, anchor="lt")
    block = (top[3] - top[1]) + gap + (name[3] - name[1])
    y = cy - block // 2
    wide = max(top[2] - top[0], name[2] - name[0])
    left = (space[0] + space[1] - wide) // 2
    draw.text((left - top[0], y - top[1]), "GET IT WITH", font=small, fill=WHITE, anchor="lt")
    y += (top[3] - top[1]) + gap
    draw.text((left - name[0], y - name[1]), "Tern", font=large, fill=WHITE, anchor="lt")
    if left < space[0] or left + wide > space[1]:
        raise SystemExit("the name does not fit the badge")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    picture.resize((WIDTH, HEIGHT), Image.LANCZOS).save(OUT, optimize=True)
    print(f"wrote {OUT.relative_to(ROOT)}, {OUT.stat().st_size} bytes")

    size = ICON_SIZE * FINER
    icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(icon)
    draw.ellipse((0, 0, size - 1, size - 1), fill=ground)
    banner.seal(draw, size / 2, size / 2, size / 2 * 0.62, paper)
    icon.resize((ICON_SIZE, ICON_SIZE), Image.LANCZOS).save(ICON, optimize=True)
    print(f"wrote {ICON.relative_to(ROOT)}, {ICON.stat().st_size} bytes")


if __name__ == "__main__":
    sys.exit(main())
