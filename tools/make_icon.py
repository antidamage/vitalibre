#!/usr/bin/env python3
"""Draw the app icon: the orb (deep green-blue ring, dark dome) with a heartbeat arc.
Supersampled 2x; saved without alpha as App Store icons must be."""
import math, json, os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = 2048; C = S // 2; R = int(S * 0.43)
img = Image.new("RGB", (S, S), (18, 18, 18))
d = ImageDraw.Draw(img)

def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))
stops = [(142, 16, 16), (187, 8, 39), (152, 99, 10)]  # dark orb linework over the plate

# plate, lip
d.ellipse([C - R, C - R, C + R, C + R], fill=(28, 28, 28))
# ring: angular gradient, drawn as thin wedges
r_out, r_in = R * 0.966, R * 0.51
ring = Image.new("RGB", (S, S), (0, 0, 0)); rd = ImageDraw.Draw(ring)
n = 720
for i in range(n):
    t = i / n * 3
    k = int(t) % 3
    col = lerp(stops[k], stops[(k + 1) % 3], t - int(t))
    a0, a1 = math.radians(i * 360 / n - 90), math.radians((i + 1.5) * 360 / n - 90)
    rd.pieslice([C - r_out, C - r_out, C + r_out, C + r_out], math.degrees(a0), math.degrees(a1), fill=col)
mask = Image.new("L", (S, S), 0); md = ImageDraw.Draw(mask)
md.ellipse([C - r_out, C - r_out, C + r_out, C + r_out], fill=255)
md.ellipse([C - r_in, C - r_in, C + r_in, C + r_in], fill=0)
img.paste(ring, (0, 0), mask)
# grid spokes
g = Image.new("RGBA", (S, S), (0, 0, 0, 0)); gd = ImageDraw.Draw(g)
for i in range(72):
    a = math.radians(i * 5)
    gd.line([C + r_in * math.sin(a), C - r_in * math.cos(a), C + r_out * math.sin(a), C - r_out * math.cos(a)], fill=(255, 190, 170, 26), width=3)
for k in range(1, 10):
    rr = r_in + k * R * 0.045
    if rr < r_out: gd.ellipse([C - rr, C - rr, C + rr, C + rr], outline=(255, 190, 170, 20), width=2)
img.paste(g, (0, 0), g)
# heartbeat arc around the ring, LED colour, with glow
mid = (r_in + r_out) / 2; amp = (r_out - r_in) * 0.42
pts = []
for i in range(0, 1400):
    t = i / 1400
    th = t * 2 * math.pi * 0.86 + math.radians(-60)
    ph = (t * 4.3) % 1
    p = math.exp(-((ph - .18) / .06) ** 2) + .35 * math.exp(-((ph - .46) / .09) ** 2) - .12 * math.exp(-((ph - .3) / .04) ** 2)
    rr = mid + amp * (p * 1.6 - 0.35)
    pts.append((C + rr * math.sin(th), C - rr * math.cos(th)))
glow = Image.new("RGBA", (S, S), (0, 0, 0, 0)); gld = ImageDraw.Draw(glow)
gld.line(pts, fill=(255, 90, 79, 200), width=34, joint="curve")
glow = glow.filter(ImageFilter.GaussianBlur(26))
img.paste(glow, (0, 0), glow)
line = Image.new("RGBA", (S, S), (0, 0, 0, 0)); ld = ImageDraw.Draw(line)
ld.line(pts, fill=(255, 230, 214, 255), width=12, joint="curve")
img.paste(line, (0, 0), line)
# dome: dark machined face with bevel
r_d = R * 0.457
dome = Image.new("RGB", (S, S)); dd = ImageDraw.Draw(dome)
for k in range(60, 0, -1):
    t = k / 60
    rr = r_d * t
    col = lerp((70, 70, 70), (8, 8, 8), t)
    dd.ellipse([C - rr - r_d * .1 * (1 - t), C - rr - r_d * .16 * (1 - t), C + rr - r_d * .1 * (1 - t), C + rr - r_d * .16 * (1 - t)], fill=col)
dm = Image.new("L", (S, S), 0); ImageDraw.Draw(dm).ellipse([C - r_d, C - r_d, C + r_d, C + r_d], fill=255)
img.paste(dome, (0, 0), dm)
d = ImageDraw.Draw(img)
d.ellipse([C - r_d, C - r_d, C + r_d, C + r_d], outline=(150, 150, 150), width=6)
# unlock band
ru = r_in - R * 0.053
d.ellipse([C - r_in, C - r_in, C + r_in, C + r_in], outline=(90, 110, 112), width=5)
d.ellipse([C - ru, C - ru, C + ru, C + ru], outline=(20, 20, 20), width=5)
out = img.resize((1024, 1024), Image.LANCZOS)
out.save(os.path.join(ROOT, "App/Assets.xcassets/AppIcon.appiconset/icon-1024.png"))
json.dump({"images": [{"filename": "icon-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
           "info": {"author": "xcode", "version": 1}},
          open(os.path.join(ROOT, "App/Assets.xcassets/AppIcon.appiconset/Contents.json"), "w"), indent=2)
json.dump({"info": {"author": "xcode", "version": 1}}, open(os.path.join(ROOT, "App/Assets.xcassets/Contents.json"), "w"), indent=2)
print("icon written")
