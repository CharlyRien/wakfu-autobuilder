#!/usr/bin/env python3
"""Regenerate the 8 rarity-gem badges from the OFFICIAL local Wakfu client (gui.jar).

The faceted rarity gems only ship for two rarities in the client:
  - theme/images/pictos/RarityEpic.tga   (pink gem,   22x22)
  - theme/images/pictos/RarityRelic.tga  (purple gem, 22x22)
The other six rarities have no faceted-gem asset (gui.jar offers only the square
border frames `theme/images/itemsRarityBorders/*` — and not even one for UNCOMMON).

So this tool grounds the whole set on official art: epic/relic are copied verbatim
from the client, and the remaining six are derived by recolouring the official EPIC
gem (its faceted shape + shading are kept; only the hue/saturation change). The hues
match each rarity's in-game colour (the NP-ItemRarity palette): rare=green,
mythic=orange, legendary=gold, souvenir=blue; common=silver-grey, uncommon=white.

Maintainer-local (needs a Wakfu install, like generateAssets / bdata-extractor); the
resulting PNGs are committed under gui-compose/src/main/resources/assets/rarities/.

Usage:  python3 scripts/generate-rarity-gems.py [/path/to/Wakfu]
        (default install: /Applications/Ankama/Wakfu)
Requires Pillow (PIL).
"""
import colorsys
import io
import math
import os
import sys
import zipfile

from PIL import Image

INSTALL = sys.argv[1] if len(sys.argv) > 1 else "/Applications/Ankama/Wakfu"
GUI_JAR = os.path.join(INSTALL, "contents/gui_jar/gui.jar")
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO, "gui-compose/src/main/resources/assets/rarities")

# Hue/saturation per recoloured rarity, taken from the in-game (NP-ItemRarity) palette.
# A hue of None means achromatic (grey/white), controlled by the value scale instead.
RECOLOURED = {
    "common": (None, 0.0, 0.74),   # silver-grey
    "uncommon": (None, 0.0, 1.00),  # white
    "rare": (0.424, 0.95, 1.0),    # green
    "mythic": (0.069, 1.0, 1.0),   # orange
    "legendary": (0.123, 1.0, 1.0),  # gold
    "souvenir": (0.556, 0.62, 1.0),  # blue
}


def main():
    z = zipfile.ZipFile(GUI_JAR)

    def tga(name):
        return Image.open(io.BytesIO(z.read(name))).convert("RGBA")

    epic = tga("theme/images/pictos/RarityEpic.tga")
    relic = tga("theme/images/pictos/RarityRelic.tga")

    # Reference body saturation of the official epic gem (median of opaque saturated pixels),
    # so a target saturation can be applied proportionally rather than flatly.
    sats = sorted(
        colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)[1]
        for r, g, b, a in epic.getdata()
        if a > 120 and colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)[1] > 0.15
    )
    ref_s = sats[len(sats) // 2] if sats else 0.5

    def recolour(target_h, target_s, value_scale):
        out = Image.new("RGBA", epic.size)
        src, dst = epic.load(), out.load()
        scale = (target_s / ref_s) if ref_s > 0 else 1.0
        for y in range(epic.height):
            for x in range(epic.width):
                r, g, b, a = src[x, y]
                if a == 0:
                    dst[x, y] = (0, 0, 0, 0)
                    continue
                h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
                v = min(1.0, v * value_scale)
                if target_h is None:
                    nr = ng = nb = v
                else:
                    nr, ng, nb = colorsys.hsv_to_rgb(target_h, min(1.0, s * scale), v)
                dst[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
        return out

    os.makedirs(OUT, exist_ok=True)
    gems = {"epic": epic, "relic": relic}
    for name, (h, s, vscale) in RECOLOURED.items():
        gems[name] = recolour(h, s, vscale)
    for name, image in gems.items():
        image.save(os.path.join(OUT, f"{name}.png"))
        print(f"wrote {name}.png {image.size}")


if __name__ == "__main__":
    main()
