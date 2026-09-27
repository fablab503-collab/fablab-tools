"""Draws Eco's app icon (Resources/AppIcon.png, 1024 px): a voice waveform whose echo ripples out.

Run with Python and Pillow; build.sh turns the PNG into AppIcon.icns on the Mac.
"""
from PIL import Image, ImageDraw, ImageFilter

S = 4096  # drawn at 4x, then scaled down for smooth edges
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

# macOS icon grid: an 824/1024 rounded square, centred, with a soft shadow below it.
body = int(S * 824 / 1024)
x0 = (S - body) // 2
y0 = x0 - int(S * 0.012)
radius = int(body * 0.225)

shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
ImageDraw.Draw(shadow).rounded_rectangle(
    (x0, y0 + int(S * 0.02), x0 + body, y0 + body + int(S * 0.02)), radius, fill=(0, 0, 0, 110))
img = Image.alpha_composite(img, shadow.filter(ImageFilter.GaussianBlur(S * 0.018)))

# Background: indigo to violet, top left to bottom right.
grad = Image.new("RGBA", (body, body))
top, bottom = (58, 44, 168), (148, 64, 214)
px = grad.load()
for y in range(body):
    for x in range(0, body, 4):
        t = (x + y) / (2 * body)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,)
        for k in range(4):
            if x + k < body:
                px[x + k, y] = c
mask = Image.new("L", (body, body), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, body - 1, body - 1), radius, fill=255)
img.paste(grad, (x0, y0), mask)

d = ImageDraw.Draw(img)
cx, cy = x0 + body * 0.525, y0 + body * 0.5

# The voice: rounded waveform bars.
bar_w = body * 0.052
gap = body * 0.030
heights = [0.16, 0.30, 0.48, 0.34, 0.22]
start = cx - (len(heights) * bar_w + (len(heights) - 1) * gap) - body * 0.02
for i, h in enumerate(heights):
    bx = start + i * (bar_w + gap)
    hh = body * h
    d.rounded_rectangle((bx, cy - hh / 2, bx + bar_w, cy + hh / 2), bar_w / 2, fill=(255, 255, 255, 255))

# Its echo: three arcs rippling outward, fading.
for i, alpha in enumerate([255, 185, 115]):
    r = body * (0.14 + i * 0.105)
    w = int(body * 0.045)
    d.arc((cx - r, cy - r, cx + r, cy + r), start=-50, end=50, fill=(255, 255, 255, alpha), width=w)

img.resize((1024, 1024), Image.LANCZOS).save("Resources/AppIcon.png")
print("wrote Resources/AppIcon.png")
