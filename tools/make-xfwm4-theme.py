#!/usr/bin/env python3
"""Draw the desktop's window-border theme for xfwm4.

xfwm4's stock themes look twenty years old, and Ubuntu's Yaru has none for
it. This draws one in Yaru's colours - a light, flat title bar, a round
orange close button and round grey minimise and maximise buttons - in two
sizes: "Linx" for a monitor and "Linx-xhdpi" (twice the size) for the
phone. It writes PNGs with nothing but the standard library, and only when
the theme is missing or older than this script's VERSION.

    make-xfwm4-theme.py [--dark] [--accent RRGGBB] [themes-dir]
                                         (default /usr/share/themes)

The close button takes the accent colour (Ubuntu orange by default), and
--dark draws Yaru-dark's colours.
"""
import math
import os
import struct
import sys
import zlib

VERSION = "2"

LIGHT = {
    "bg_active": (0xED, 0xED, 0xED),
    "bg_inactive": (0xF7, 0xF7, 0xF7),
    "border": (0xC4, 0xC4, 0xC4),
    "grey": (0xDA, 0xDA, 0xDA),
    "grey_hover": (0xCC, 0xCC, 0xCC),
    "grey_pressed": (0xBA, 0xBA, 0xBA),
    "glyph": (0x3D, 0x3D, 0x3D),
    "glyph_inactive": (0xA0, 0xA0, 0xA0),
    "text_active": "#3D3D3D",
    "text_inactive": "#8B8B8B",
}
DARK = {
    "bg_active": (0x2C, 0x2C, 0x2C),
    "bg_inactive": (0x24, 0x24, 0x24),
    "border": (0x18, 0x18, 0x18),
    "grey": (0x45, 0x45, 0x45),
    "grey_hover": (0x52, 0x52, 0x52),
    "grey_pressed": (0x5E, 0x5E, 0x5E),
    "glyph": (0xF2, 0xF2, 0xF2),
    "glyph_inactive": (0x80, 0x80, 0x80),
    "text_active": "#F2F2F2",
    "text_inactive": "#8B8B8B",
}
ORANGE = (0xE9, 0x54, 0x20)
WHITE = (0xFF, 0xFF, 0xFF)
BLACK = (0x00, 0x00, 0x00)

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


def make_theme(path, t, border, pal, accent):
    os.makedirs(path, exist_ok=True)
    bg_active, bg_inactive, edge = pal["bg_active"], pal["bg_inactive"], pal["border"]
    for state, bg in (("active", bg_active), ("inactive", bg_inactive)):
        # The title bar, with a hairline under it.
        def title(x, y, bg=bg):
            return edge if y >= t - border else bg

        for i in range(1, 6):
            write_png(f"{path}/title-{i}-{state}.png", 4, t, title)

        def corner(x, y, bg=bg):
            return edge if y >= t - border else bg

        write_png(f"{path}/top-left-{state}.png", border, t, corner)
        write_png(f"{path}/top-right-{state}.png", border, t, corner)
        for name, w, h in (("left", border, 4), ("right", border, 4), ("bottom", 4, border),
                           ("bottom-left", border, border), ("bottom-right", border, border)):
            write_png(f"{path}/{name}-{state}.png", w, h, lambda x, y: edge)

    variants = {
        "active": (pal["grey"], accent, pal["glyph"]),
        "prelight": (pal["grey_hover"], mix(accent, WHITE, 0.15), pal["glyph"]),
        "pressed": (pal["grey_pressed"], mix(accent, BLACK, 0.15), pal["glyph"]),
        "inactive": (None, pal["grey"], pal["glyph_inactive"]),
    }
    for kind in ("close", "hide", "maximize", "maximize-toggled"):
        for variant, (grey, orange, glyph) in variants.items():
            bg = bg_inactive if variant == "inactive" else bg_active
            if kind == "close":
                circle = orange
                mark = WHITE if variant != "inactive" else pal["glyph_inactive"]
            else:
                circle = grey
                mark = glyph
            write_png(f"{path}/{kind}-{variant}.png", t, t, render(t, button_shapes(kind, t, circle, mark), bg))
    # Buttons the layout does not show still need images.
    for kind in ("menu", "shade", "shade-toggled", "stick", "stick-toggled"):
        for variant in variants:
            bg = bg_inactive if variant == "inactive" else bg_active
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
active_text_color={pal["text_active"]}
inactive_text_color={pal["text_inactive"]}
title_shadow_active=false
title_shadow_inactive=false
show_app_icon=true
maximized_offset=0
""")


def main():
    args = sys.argv[1:]
    dark = "--dark" in args
    accent = ORANGE
    if "--accent" in args:
        value = args[args.index("--accent") + 1].lstrip("#")
        accent = tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))
    rest = [a for i, a in enumerate(args)
            if not a.startswith("--") and (i == 0 or args[i - 1] != "--accent")]
    themes = rest[0] if rest else "/usr/share/themes"
    key = "%s %s %02x%02x%02x" % (VERSION, "dark" if dark else "light", *accent)
    stamp = f"{themes}/Linx/.version"
    try:
        with open(stamp) as f:
            if f.read().strip() == key:
                return
    except OSError:
        pass
    pal = DARK if dark else LIGHT
    make_theme(f"{themes}/Linx/xfwm4", 28, 1, pal, accent)
    make_theme(f"{themes}/Linx-xhdpi/xfwm4", 54, 2, pal, accent)
    with open(stamp, "w") as f:
        f.write(key)
    print("REDRAWN")


if __name__ == "__main__":
    main()
