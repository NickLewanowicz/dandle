#!/usr/bin/env python3
"""Key a uniform #808080 generation canvas to transparency (brief 04 art pipeline).

gpt-image-2 has no transparent-background support, so art is generated on a solid
mid-gray canvas and keyed out here (docs/design/icons.md gotcha). Ink pixels are
unmixed against the known background: alpha = |pixel-bg| / |ink-bg| (channel avg,
clamped), color snapped to pure ink. Anti-aliased edges come out clean.
"""
import sys
from PIL import Image

BG = (0x80, 0x80, 0x80)
INK = (0x2A, 0x28, 0x70)  # deep indigo — brief 04 palette anchor
TOLERANCE = 10             # pixels this close to BG are fully transparent


def dist(a, b):
    return sum(abs(x - y) for x, y in zip(a, b)) / 3.0


def main(src, dst, ink=INK):
    img = Image.open(src).convert("RGBA")
    px = img.load()
    span = max(dist(ink, BG), 1.0)
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, _ = px[x, y]
            d = dist((r, g, b), BG)
            if d <= TOLERANCE:
                px[x, y] = (ink[0], ink[1], ink[2], 0)
                continue
            alpha = min(1.0, d / span)
            # Snap color to ink; alpha carries the anti-aliasing.
            px[x, y] = (ink[0], ink[1], ink[2], int(round(alpha * 255)))
    img.save(dst)
    print(f"keyed: {dst}")


if __name__ == "__main__":
    ink = (
        int(sys.argv[3], 16),
        int(sys.argv[4], 16),
        int(sys.argv[5], 16),
    ) if len(sys.argv) > 5 else INK
    main(sys.argv[1], sys.argv[2], ink)
