#!/usr/bin/env python3
"""Draw the desktop's window-border theme for xfwm4.

xfwm4's stock themes look twenty years old, and Ubuntu's Yaru has none for
it. This draws one in Yaru's colours - a light, flat title bar, a round
orange close button and round grey minimise and maximise buttons - in two
sizes: "Linx" for a monitor and "Linx-xhdpi" (twice the size) for the
phone. It writes PNGs with nothing but the standard library, and only when
the theme is missing or older than this script's VERSION.

    make-xfwm4-theme.py [themes-dir]     (default /usr/share/themes)
"""
import math
import os
import struct
import sys
import zlib

VERSION = "1"

BG_ACTIVE = (0xED, 0xED, 0xED)
BG_INACTIVE = (0xF7, 0xF7, 0xF7)
BORDER = (0xC4, 0xC4, 0xC4)
ORANGE = (0xE9, 0x54, 0x20)
ORANGE_HOVER = (0xF0, 0x6B, 0x3B)
ORANGE_PRESSED = (0xC7, 0x47, 0x1A)
GREY = (0xDA, 0xDA, 0xDA)
GREY_HOVER = (0xCC, 0xCC, 0xCC)
GREY_PRESSED = (0xBA, 0xBA, 0xBA)
GLYPH = (0x3D, 0x3D, 0x3D)
GLYPH_INACTIVE = (0xA0, 0xA0, 0xA0)
WHITE = (0xFF, 0xFF, 0xFF)

SUPERSAMPLE = 3


def write_png(path, width, height, pixel):
    """pixel(x, y) -> (r, g, b)."""
    rows = []
    for y in range(height):
        row = bytearray([0])
        for x in range(width):
            row += bytes(pixel(x, y))
        rows.append(bytes(row))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(b"".join(rows), 9)))
        f.write(chunk(b"IEND", b""))


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def seg_dist(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy or 1)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def render(size, shapes, bg):
    """shapes: list of (inside(x, y) -> bool, colour), painted in order."""
    n = SUPERSAMPLE

    def pixel(x, y):
        acc = [0, 0, 0]
        for sy in range(n):
            for sx in range(n):
                fx = x + (sx + 0.5) / n
                fy = y + (sy + 0.5) / n
                c = bg
                for inside, colour in shapes:
                    if inside(fx, fy):
                        c = colour
                for i in range(3):
                    acc[i] += c[i]
        return tuple(v // (n * n) for v in acc)

    return pixel


def button_shapes(kind, t, circle, glyph):
    """The shapes of a button: a circle and its glyph."""
    cx = cy = t / 2
    r = t * 0.30
    th = max(1.2, t * 0.055)
    g = r * 0.48
    shapes = []
    if circle is not None:
        shapes.append((lambda x, y: math.hypot(x - cx, y - cy) <= r, circle))
    if kind == "close":
        shapes.append((lambda x, y: seg_dist(x, y, cx - g, cy - g, cx + g, cy + g) <= th / 2
                       or seg_dist(x, y, cx - g, cy + g, cx + g, cy - g) <= th / 2, glyph))
    elif kind == "hide":
        shapes.append((lambda x, y: seg_dist(x, y, cx - g, cy + g * 0.2, cx + g, cy + g * 0.2) <= th / 2, glyph))
    elif kind == "maximize":
        s = g * 0.9
        shapes.append((lambda x, y: abs(max(abs(x - cx), abs(y - cy)) - s) <= th / 2, glyph))
    elif kind == "maximize-toggled":
        s = g * 0.7
        o = g * 0.3

        def restore(x, y):
            front = abs(max(abs(x - (cx - o)), abs(y - (cy + o))) - s) <= th / 2
            back = abs(max(abs(x - (cx + o)), abs(y - (cy - o))) - s) <= th / 2
            hidden = max(abs(x - (cx - o)), abs(y - (cy + o))) < s - th / 2
            return front or (back and not hidden)

        shapes.append((restore, glyph))
    return shapes


def make_theme(path, t, border):
    os.makedirs(path, exist_ok=True)
    for state, bg in (("active", BG_ACTIVE), ("inactive", BG_INACTIVE)):
        # The title bar, with a hairline under it.
        def title(x, y, bg=bg):
            return BORDER if y >= t - border else bg

        for i in range(1, 6):
            write_png(f"{path}/title-{i}-{state}.png", 4, t, title)

        def corner(x, y, bg=bg):
            return BORDER if y >= t - border else bg

        write_png(f"{path}/top-left-{state}.png", border, t, corner)
        write_png(f"{path}/top-right-{state}.png", border, t, corner)
        for name, w, h in (("left", border, 4), ("right", border, 4), ("bottom", 4, border),
                           ("bottom-left", border, border), ("bottom-right", border, border)):
            write_png(f"{path}/{name}-{state}.png", w, h, lambda x, y: BORDER)

    variants = {
        "active": (GREY, ORANGE, GLYPH),
        "prelight": (GREY_HOVER, ORANGE_HOVER, GLYPH),
        "pressed": (GREY_PRESSED, ORANGE_PRESSED, GLYPH),
        "inactive": (None, GREY, GLYPH_INACTIVE),
    }
    for kind in ("close", "hide", "maximize", "maximize-toggled"):
        for variant, (grey, orange, glyph) in variants.items():
            bg = BG_INACTIVE if variant == "inactive" else BG_ACTIVE
            if kind == "close":
                circle = orange
                mark = WHITE if variant != "inactive" else GLYPH_INACTIVE
            else:
                circle = grey
                mark = glyph
            write_png(f"{path}/{kind}-{variant}.png", t, t, render(t, button_shapes(kind, t, circle, mark), bg))
    # Buttons the layout does not show still need images.
    for kind in ("menu", "shade", "shade-toggled", "stick", "stick-toggled"):
        for variant in variants:
            bg = BG_INACTIVE if variant == "inactive" else BG_ACTIVE
            write_png(f"{path}/{kind}-{variant}.png", t, t, lambda x, y, bg=bg: bg)

    with open(f"{path}/themerc", "w") as f:
        f.write(f"""# Drawn by make-xfwm4-theme.py, version {VERSION}
button_offset={max(2, t // 8)}
button_spacing=0
full_width_title=true
title_alignment=center
title_horizontal_offset=0
title_vertical_offset_active=0
title_vertical_offset_inactive=0
active_text_color=#3D3D3D
inactive_text_color=#8B8B8B
title_shadow_active=false
title_shadow_inactive=false
show_app_icon=true
maximized_offset=0
""")


def main():
    themes = sys.argv[1] if len(sys.argv) > 1 else "/usr/share/themes"
    stamp = f"{themes}/Linx/.version"
    try:
        with open(stamp) as f:
            if f.read().strip() == VERSION:
                return
    except OSError:
        pass
    make_theme(f"{themes}/Linx/xfwm4", 28, 1)
    make_theme(f"{themes}/Linx-xhdpi/xfwm4", 54, 2)
    with open(stamp, "w") as f:
        f.write(VERSION)


if __name__ == "__main__":
    main()
