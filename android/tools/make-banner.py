#!/usr/bin/env python3
"""Draw the TV launcher banner (res/drawable-xhdpi/tv_banner.png).

    python3 android/tools/make-banner.py

Android TV and Fire TV launchers show an app by a 320x180 dp banner, not its
icon; without one an app either does not appear in the leanback launcher or
appears as a stretched icon. This draws the launcher icon's mast and arcs
(the same geometry as res/drawable/ic_launcher_foreground.xml) beside the
app's name, white on black like the app itself.

A PNG rather than a vector: launchers render banners in their own process,
and a vector with text is not possible (text would have to be paths). The
text is rendered here with DejaVu Sans Bold from the build machine, so the
app itself carries no font; only the pixels are committed. Rerun after
changing the name or the icon.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable-xhdpi/tv_banner.png"
W, H = 640, 360          # 320x180 dp at xhdpi, the density TV launchers ask for
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
SS = 4                   # draw large and shrink: PIL's arcs are not antialiased

img = Image.new("RGB", (W * SS, H * SS), "black")
d = ImageDraw.Draw(img)

# The icon: its 108-unit viewport scaled into a square at the left.
scale = 2.5 * SS
ox, oy = 6 * SS, (H * SS - 108 * scale) / 2
P = lambda x, y: (ox + x * scale, oy + y * scale)
d.polygon([P(54, 44), P(60, 80), P(48, 80)], fill="white")
def arc(cx, r, start, end, colour):
    box = [P(cx - r, 48 - r), P(cx + r, 48 + r)]
    d.arc([box[0][0], box[0][1], box[1][0], box[1][1]], start, end, fill=colour, width=int(4 * scale))
# Each pair is one ring of the mast's signal, inner amber, outer orange,
# centred on the mast head (54, 48) as the vector's arc endpoints are.
arc(54, 14.1, 135, 225, "#FFD54F"); arc(54, 14.1, -45, 45, "#FFD54F")
arc(54, 25.5, 135, 225, "#FF7043"); arc(54, 25.5, -45, 45, "#FF7043")

tx = ox + 100 * scale
# The name as large as fits between the icon and a right margin.
size = 80
while ImageFont.truetype(FONT, size * SS).getlength("OpenHamShack") > W * SS - tx - 28 * SS:
    size -= 2
f1 = ImageFont.truetype(FONT, size * SS)
f2 = ImageFont.truetype(FONT, int(size * 0.56) * SS)
d.text((tx, H * SS / 2 - 8 * SS), "OpenHamShack", font=f1, fill="white", anchor="ls")
d.text((tx + 4 * SS, H * SS / 2 + 56 * SS), "Remote", font=f2, fill="#FFD54F", anchor="ls")

OUT.parent.mkdir(parents=True, exist_ok=True)
img.resize((W, H), Image.LANCZOS).save(OUT, optimize=True)
print(OUT)
