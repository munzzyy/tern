#!/usr/bin/env python3
"""Draws the pictures of the store listing: the icon, the banner for a television and the feature
graphic, into fastlane/metadata/android/en-US/images/. The seal, the colours and the font are those
of tools/make-banner.py, so the listing looks like the app.
"""
import importlib.util, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "fastlane/metadata/android/en-US/images"
FINER = 4

spec = importlib.util.spec_from_file_location("banner", ROOT / "tools/make-banner.py")
banner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(banner)


def picture(width, height, draw_on):
    ground, paper = banner.colors()
    cover = Image.new("L", (width * FINER, height * FINER), 0)
    draw_on(ImageDraw.Draw(cover), width * FINER, height * FINER)
    flat = cover.resize((width, height), Image.LANCZOS).convert("P")
    flat.putpalette([round(g + (p - g) * step / 255) for step in range(256) for g, p in zip(ground, paper)])
    return flat.convert("RGB")


def seal_and_name(draw, w, h, seal_radius, name_size):
    middle = h / 2
    font = ImageFont.truetype(banner.FONT, name_size)
    left, _, right, _ = font.getbbox("Tern", anchor="ls")
    gap = seal_radius * 0.5
    total = 2 * seal_radius + gap + (right - left)
    start = (w - total) / 2
    banner.seal(draw, start + seal_radius, middle, seal_radius, 255)
    draw.text((start + 2 * seal_radius + gap - left, middle), "Tern", font=font, fill=255, anchor="lm")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    icon = picture(512, 512, lambda d, w, h: banner.seal(d, w / 2, h / 2, w * 0.36, 255))
    icon.save(OUT / "icon.png", optimize=True)
    tv = picture(1280, 720, lambda d, w, h: seal_and_name(d, w, h, 152 * FINER, 264 * FINER))
    tv.save(OUT / "tvBanner.png", optimize=True)
    feature = picture(1024, 500, lambda d, w, h: seal_and_name(d, w, h, 118 * FINER, 200 * FINER))
    feature.save(OUT / "featureGraphic.png", optimize=True)
    for name in ("icon.png", "tvBanner.png", "featureGraphic.png"):
        path = OUT / name
        print(f"wrote {path.relative_to(ROOT)}, {Image.open(path).size}, {path.stat().st_size} bytes")


if __name__ == "__main__":
    sys.exit(main())
