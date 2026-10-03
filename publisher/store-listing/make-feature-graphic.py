"""Render the Google Play feature graphic from VitaLibre's bundled fonts and colours."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[2]
out = Path(__file__).resolve().parent / "android" / "feature-graphic.png"
font_dir = root / "App" / "Resources" / "Fonts"

im = Image.new("RGB", (1024, 500), "#111112")
p = im.load()
for y in range(500):
    for x in range(1024):
        glow = max(0, 1 - ((x - 770) ** 2 / 480**2 + (y - 255) ** 2 / 360**2))
        p[x, y] = (int(17 + 23 * glow), int(17 + 3 * glow), int(18 + 7 * glow))

draw = ImageDraw.Draw(im)
cx, cy = 795, 250
for width, colour in [(102, "#2f0910"), (78, "#6f0816"), (53, "#b30c28"), (30, "#ed2243")]:
    r = 173
    draw.ellipse((cx-r, cy-r, cx+r, cy+r), outline=colour, width=width)
draw.ellipse((cx-116, cy-116, cx+116, cy+116), fill="#171719", outline="#09090a", width=8)
for deg in range(0, 360, 15):
    import math
    a = math.radians(deg)
    x1, y1 = cx + 139 * math.cos(a), cy + 139 * math.sin(a)
    x2, y2 = cx + 166 * math.cos(a), cy + 166 * math.sin(a)
    draw.line((x1, y1, x2, y2), fill="#fc6976", width=2)

heading = ImageFont.truetype(font_dir / "ChakraPetch-SemiBold.ttf", 72)
subtitle = ImageFont.truetype(font_dir / "Rajdhani-SemiBold.ttf", 35)
small = ImageFont.truetype(font_dir / "Rajdhani-Medium.ttf", 25)
draw.text((64, 148), "VITALIBRE", font=heading, fill="#f5f2f1")
draw.text((68, 260), "Camera pulse readings", font=subtitle, fill="#f0c6c6")
draw.text((69, 318), "Free to use  ·  Private by design", font=small, fill="#a7a4a6")
draw.line((68, 376, 505, 376), fill="#e83b51", width=3)
out.parent.mkdir(exist_ok=True)
im.save(out, optimize=True)
print(out)
