"""Render earslate Play Store assets: 512 hi-res icon + 1024x500 feature graphic.

Icon = the REAL app launcher icon (adaptive): two mirrored brackets (#D3CCC1)
on a solid ember (#C2410C) field. Geometry taken from earslate-icon-master.svg
(1024 viewport) so the flat Play icon matches the on-device adaptive icon after
the launcher's safe-zone zoom. Supersampled 4x for crisp edges.
"""
import glob, os, sys
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.dirname(os.path.abspath(__file__))

# ---- brand palette (from app colors.xml / master svg) ----
EMBER   = (194, 65, 12)    # #C2410C  earslate_ember (real icon background)
CREAM   = (211, 204, 193)  # #D3CCC1  adaptive foreground (real bracket color)
TEXT    = (236, 227, 210)  # #ECE3D2  bright cream for wordmark
SUBTLE  = (184, 170, 142)  # #B8AA8E  secondary text
DARK_TOP = (20, 16, 12)    # #14100C  warm near-black (matches screenshots)
DARK_BOT = (10, 8, 6)      # #0A0806

# Bracket geometry in the 1024 master viewport (mirrored "[ ]" speech mark).
LEFT_1024  = [(172,164),(460,164),(460,440),(360,440),(360,584),(460,584),(460,860),(172,860)]
RIGHT_1024 = [(564,164),(852,164),(852,860),(564,860),(564,584),(664,584),(664,440),(564,440)]

def scaled(pts, s, ox=0, oy=0):
    return [(x*s+ox, y*s+oy) for (x, y) in pts]

def pick_font(size):
    candidates = [
        *glob.glob(os.path.join(OUT, "..", "app", "src", "main", "res", "font", "*.ttf")),
        os.path.join(OUT, "..", "..", "..", "folio-pdf", "core", "design", "src", "main", "res", "font", "space_grotesk.ttf"),
        r"C:\Windows\Fonts\arialbd.ttf", r"C:\Windows\Fonts\arial.ttf",
    ]
    for c in candidates:
        if os.path.exists(c):
            try:
                return ImageFont.truetype(c, size)
            except Exception:
                continue
    return ImageFont.load_default()

# ---------------- ICON 512x512 (supersample 4x) ----------------
def render_icon(px=512, ss=4):
    S = px * ss
    img = Image.new("RGB", (S, S), EMBER)         # full-bleed ember, no transparency
    d = ImageDraw.Draw(img)
    scale = S / 1024.0
    d.polygon(scaled(LEFT_1024, scale), fill=CREAM)
    d.polygon(scaled(RIGHT_1024, scale), fill=CREAM)
    img = img.resize((px, px), Image.LANCZOS)
    p = os.path.join(OUT, "earslate-play-icon-512.png")
    img.save(p, "PNG", optimize=True)
    print("wrote", p, img.size)

# ---------------- FEATURE GRAPHIC 1024x500 ----------------
def render_feature(W=1024, H=500, ss=2):
    SW, SH = W*ss, H*ss
    img = Image.new("RGB", (SW, SH), DARK_TOP)
    d = ImageDraw.Draw(img)
    # vertical warm gradient
    for y in range(SH):
        t = y / SH
        d.line([(0, y), (SW, y)], fill=tuple(int(DARK_TOP[i] + (DARK_BOT[i]-DARK_TOP[i])*t) for i in range(3)))
    # left: ember icon tile (the app icon) with rounded corners
    tile = int(300*ss); tx = int(70*ss); ty = (SH - tile)//2
    d.rounded_rectangle([tx, ty, tx+tile, ty+tile], radius=int(66*ss), fill=EMBER)
    isc = tile/1024.0
    d.polygon(scaled(LEFT_1024, isc, tx, ty), fill=CREAM)
    d.polygon(scaled(RIGHT_1024, isc, tx, ty), fill=CREAM)
    # right: wordmark + tagline
    TX = int(440*ss)
    f_kick = pick_font(int(20*ss)); f_word = pick_font(int(96*ss))
    f_tag = pick_font(int(34*ss));  f_sub = pick_font(int(23*ss))
    d.text((TX, int(120*ss)), "C L A S S E V E", font=f_kick, fill=SUBTLE)
    d.text((TX, int(150*ss)), "earslate", font=f_word, fill=TEXT)
    d.rectangle([(TX, int(272*ss)), (TX+int(64*ss), int(279*ss))], fill=EMBER)
    d.text((TX, int(298*ss)), "Live speech translator", font=f_tag, fill=TEXT)
    d.text((TX, int(352*ss)), "Hear nearby speech in your language", font=f_sub, fill=SUBTLE)
    d.text((TX, int(384*ss)), "150+ languages  ·  Real-time  ·  Earbud-ready", font=f_sub, fill=SUBTLE)
    img = img.resize((W, H), Image.LANCZOS)
    p = os.path.join(OUT, "earslate-feature-graphic-1024x500.png")
    img.save(p, "PNG", optimize=True)
    print("wrote", p, img.size)

if __name__ == "__main__":
    render_icon()
    render_feature()
